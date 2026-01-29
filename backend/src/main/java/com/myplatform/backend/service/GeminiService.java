package com.myplatform.backend.service;

import com.myplatform.backend.dto.ScreenerResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Google Gemini AI 서비스
 * - 스크리너 결과 분석 및 AI 추천 제공
 * - Rate Limit 처리 (지수 백오프 재시도)
 * - Ollama 폴백 지원
 */
@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent}")
    private String apiUrl;

    @Value("${gemini.fallback.enabled:true}")
    private boolean fallbackEnabled;

    private final RestTemplate restTemplate;
    private final OllamaService ollamaService;

    // Rate Limit 관리
    private static final int MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 20000; // 20초
    private static final long MIN_REQUEST_INTERVAL_MS = 2000; // 요청 간 최소 2초 간격
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("retry in ([\\d.]+)s");

    private volatile LocalDateTime lastRequestTime = null;
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private volatile LocalDateTime quotaResetTime = null;

    public GeminiService(OllamaService ollamaService) {
        this.restTemplate = new RestTemplate();
        this.ollamaService = ollamaService;
    }

    /**
     * 마법의 공식 스크리너 결과 분석
     */
    public String analyzeMagicFormula(List<ScreenerResultDto> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "분석할 종목이 없습니다.";
        }

        String stockData = stocks.stream()
                .limit(10)
                .map(s -> String.format(
                        "- %s(%s): PER %.1f, PBR %.1f, ROE %.1f%%, 영업이익률 %.1f%%, 순위 %d위",
                        s.getStockName(), s.getStockCode(),
                        s.getPer() != null ? s.getPer().doubleValue() : 0,
                        s.getPbr() != null ? s.getPbr().doubleValue() : 0,
                        s.getRoe() != null ? s.getRoe().doubleValue() : 0,
                        s.getOperatingMargin() != null ? s.getOperatingMargin().doubleValue() : 0,
                        s.getMagicFormulaRank()
                ))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.

                아래는 '마법의 공식' 스크리너로 선별된 저평가 우량주 목록입니다.
                마법의 공식은 ROE(자기자본이익률)와 영업이익률이 높으면서 PER이 낮은 종목을 찾는 전략입니다.

                [스크리닝 결과 - 상위 종목]
                %s

                위 종목들을 분석하여 다음 내용을 포함해 간결하게 추천해주세요:
                1. 상위 3개 종목에 대한 간단한 투자 포인트
                2. 주의해야 할 리스크 요인
                3. 전반적인 시장 관점에서의 조언

                반드시 한국어로 답변하고, 300자 이내로 요약해주세요.
                """, stockData);

        return callWithFallback(prompt, "마법의 공식 분석");
    }

    /**
     * PEG 스크리너 결과 분석
     */
    public String analyzePegStocks(List<ScreenerResultDto> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "분석할 종목이 없습니다.";
        }

        String stockData = stocks.stream()
                .limit(10)
                .map(s -> String.format(
                        "- %s(%s): PEG %.2f, PER %.1f, EPS성장률 %.1f%%, ROE %.1f%%",
                        s.getStockName(), s.getStockCode(),
                        s.getPeg() != null ? s.getPeg().doubleValue() : 0,
                        s.getPer() != null ? s.getPer().doubleValue() : 0,
                        s.getEpsGrowth() != null ? s.getEpsGrowth().doubleValue() : 0,
                        s.getRoe() != null ? s.getRoe().doubleValue() : 0
                ))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.

                아래는 PEG(PER/EPS성장률) 기준 저평가 성장주 목록입니다.
                PEG가 1 미만이면 성장률 대비 저평가된 종목으로 간주합니다.

                [PEG 스크리닝 결과]
                %s

                위 종목들을 분석하여 다음 내용을 포함해 간결하게 추천해주세요:
                1. 성장성 대비 가장 저평가된 Top 3 종목과 투자 포인트
                2. 성장주 투자 시 주의사항
                3. 포트폴리오 구성 조언

                반드시 한국어로 답변하고, 300자 이내로 요약해주세요.
                """, stockData);

        return callWithFallback(prompt, "PEG 분석");
    }

    /**
     * 턴어라운드 스크리너 결과 분석
     */
    public String analyzeTurnaroundStocks(List<ScreenerResultDto> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "분석할 종목이 없습니다.";
        }

        String stockData = stocks.stream()
                .limit(10)
                .map(s -> String.format(
                        "- %s(%s): %s, 이전순이익 %.0f억 → 현재순이익 %.0f억, PER %.1f",
                        s.getStockName(), s.getStockCode(),
                        "LOSS_TO_PROFIT".equals(s.getTurnaroundType()) ? "흑자전환" : "이익급증",
                        s.getPreviousNetIncome() != null ? s.getPreviousNetIncome().doubleValue() : 0,
                        s.getCurrentNetIncome() != null ? s.getCurrentNetIncome().doubleValue() : 0,
                        s.getPer() != null ? s.getPer().doubleValue() : 0
                ))
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                당신은 한국 주식시장 전문 애널리스트입니다.

                아래는 실적 턴어라운드(적자→흑자 전환 또는 이익 급증) 종목 목록입니다.

                [턴어라운드 스크리닝 결과]
                %s

                위 종목들을 분석하여 다음 내용을 포함해 간결하게 추천해주세요:
                1. 가장 주목할 만한 턴어라운드 종목 Top 3와 이유
                2. 턴어라운드 지속 가능성 판단 기준
                3. 턴어라운드 종목 투자 시 주의사항

                반드시 한국어로 답변하고, 300자 이내로 요약해주세요.
                """, stockData);

        return callWithFallback(prompt, "턴어라운드 분석");
    }

    /**
     * Gemini API 호출 (Rate Limit 처리 + Ollama 폴백)
     */
    private String callWithFallback(String prompt, String analysisType) {
        // 1. 쿼터 리셋 시간 체크
        if (quotaResetTime != null && LocalDateTime.now().isBefore(quotaResetTime)) {
            log.warn("Gemini 쿼터 제한 중 (리셋: {}), Ollama 폴백 사용", quotaResetTime);
            return callOllamaFallback(prompt, analysisType);
        }

        // 2. Gemini API 호출 시도 (재시도 로직 포함)
        String result = callGeminiApiWithRetry(prompt);

        // 3. 실패 시 Ollama 폴백
        if (result == null || result.startsWith("AI 서버") || result.startsWith("Rate Limit")) {
            if (fallbackEnabled && ollamaService != null) {
                log.info("Gemini 실패, Ollama 폴백 사용: {}", analysisType);
                return callOllamaFallback(prompt, analysisType);
            }
        }

        return result;
    }

    /**
     * Gemini API 호출 (지수 백오프 재시도)
     */
    private String callGeminiApiWithRetry(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API 키가 설정되지 않았습니다.");
            return null;
        }

        // 요청 간 최소 간격 유지
        enforceRateLimit();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String result = callGeminiApi(prompt);
                consecutiveErrors.set(0); // 성공 시 에러 카운터 리셋
                return result;

            } catch (HttpClientErrorException.TooManyRequests e) {
                long retryDelay = parseRetryDelay(e.getMessage());
                consecutiveErrors.incrementAndGet();

                log.warn("Gemini Rate Limit (시도 {}/{}), {}ms 후 재시도...",
                        attempt + 1, MAX_RETRIES, retryDelay);

                // 연속 에러가 많으면 쿼터 리셋 시간 설정 (1분)
                if (consecutiveErrors.get() >= 3) {
                    quotaResetTime = LocalDateTime.now().plusMinutes(1);
                    log.warn("연속 Rate Limit 발생, {}까지 Gemini 일시 중단", quotaResetTime);
                    return null;
                }

                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }

            } catch (Exception e) {
                log.error("Gemini API 호출 실패: {}", e.getMessage());
                return null;
            }
        }

        return "Rate Limit 초과로 분석을 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.";
    }

    /**
     * Gemini API 직접 호출
     */
    private String callGeminiApi(String prompt) {
        String url = apiUrl + "?key=" + apiKey;

        // Request body 구성
        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();
        List<Map<String, String>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        // Generation config
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.7);
        generationConfig.put("maxOutputTokens", 1024);
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("Gemini API 호출 시작");
        lastRequestTime = LocalDateTime.now();

        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            Map body = response.getBody();
            List<Map> candidates = (List<Map>) body.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map candidate = candidates.get(0);
                Map contentMap = (Map) candidate.get("content");
                if (contentMap != null) {
                    List<Map> partsList = (List<Map>) contentMap.get("parts");
                    if (partsList != null && !partsList.isEmpty()) {
                        String text = (String) partsList.get(0).get("text");
                        log.info("Gemini API 응답 수신 완료");
                        return text;
                    }
                }
            }
        }

        log.warn("Gemini API 응답 파싱 실패");
        return "AI 분석 결과를 가져오는데 실패했습니다.";
    }

    /**
     * Ollama 폴백 호출
     */
    private String callOllamaFallback(String prompt, String analysisType) {
        if (ollamaService == null) {
            return "AI 분석 서비스가 일시적으로 사용 불가능합니다. (Gemini Rate Limit)";
        }

        try {
            String systemPrompt = """
                    당신은 한국 주식시장 전문 애널리스트입니다.
                    주어진 종목 데이터를 분석하여 투자 조언을 제공합니다.
                    반드시 한국어로 답변하세요.
                    """;

            String result = ollamaService.chat(prompt, systemPrompt);
            if (result != null && !result.isEmpty()) {
                log.info("Ollama 폴백 성공: {}", analysisType);
                return "[Ollama AI 분석]\n" + result;
            }
        } catch (Exception e) {
            log.error("Ollama 폴백 실패: {}", e.getMessage());
        }

        return "AI 분석 서비스가 일시적으로 사용 불가능합니다.";
    }

    /**
     * 요청 간 최소 간격 유지
     */
    private void enforceRateLimit() {
        if (lastRequestTime != null) {
            long elapsed = java.time.Duration.between(lastRequestTime, LocalDateTime.now()).toMillis();
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                try {
                    long waitTime = MIN_REQUEST_INTERVAL_MS - elapsed;
                    log.debug("Rate limit 대기: {}ms", waitTime);
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 에러 메시지에서 retry delay 파싱
     */
    private long parseRetryDelay(String errorMessage) {
        if (errorMessage != null) {
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(errorMessage);
            if (matcher.find()) {
                try {
                    double seconds = Double.parseDouble(matcher.group(1));
                    return (long) (seconds * 1000) + 1000; // 여유 1초 추가
                } catch (NumberFormatException e) {
                    // 파싱 실패 시 기본값 사용
                }
            }
        }
        return DEFAULT_RETRY_DELAY_MS;
    }

    /**
     * API 키 설정 여부 확인
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * 현재 Rate Limit 상태 조회
     */
    public Map<String, Object> getRateLimitStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", isAvailable());
        status.put("consecutiveErrors", consecutiveErrors.get());
        status.put("quotaResetTime", quotaResetTime);
        status.put("lastRequestTime", lastRequestTime);
        status.put("fallbackEnabled", fallbackEnabled);
        return status;
    }
}
