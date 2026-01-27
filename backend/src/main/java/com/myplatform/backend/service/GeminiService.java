package com.myplatform.backend.service;

import com.myplatform.backend.dto.ScreenerResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Google Gemini AI 서비스
 * - 스크리너 결과 분석 및 AI 추천 제공
 */
@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent}")
    private String apiUrl;

    private final RestTemplate restTemplate;

    public GeminiService() {
        this.restTemplate = new RestTemplate();
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

        return callGeminiApi(prompt);
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

        return callGeminiApi(prompt);
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

        return callGeminiApi(prompt);
    }

    /**
     * Gemini API 호출
     */
    private String callGeminiApi(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API 키가 설정되지 않았습니다.");
            return "AI 분석 기능을 사용하려면 Gemini API 키를 설정해주세요.";
        }

        try {
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

        } catch (Exception e) {
            log.error("Gemini API 호출 실패: {}", e.getMessage(), e);
            return "AI 서버 연결에 실패했습니다: " + e.getMessage();
        }
    }

    /**
     * API 키 설정 여부 확인
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }
}
