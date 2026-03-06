package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 글로벌 선물 시세 서비스 (KIS API 해외선물)
 *
 * - 코스피200 야간선물 (KM)
 * - 나스닥100 선물 (NQ)
 * - S&P500 선물 (ES)
 * - 다우 선물 (YM)
 * - WTI 원유 선물 (CL)
 * - 금 선물 (GC)
 * - 유로/달러 (6E), 달러/엔 (6J)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalFuturesService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KoreaInvestmentService koreaInvestmentService;

    @Value("${kis.api.app-key:}")
    private String appKey;

    @Value("${kis.api.app-secret:}")
    private String appSecret;

    @Value("${kis.api.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    // 해외선물 종목 정의
    private static final Map<String, FuturesInfo> FUTURES_MAP = new LinkedHashMap<>();

    // KIS API 거래소코드 매핑
    private static final Map<String, String> KIS_EXCHANGE_MAP = Map.of(
            "CME", "CME",
            "NYMEX", "NYM",
            "COMEX", "CMX",
            "CBOT", "CBT"
    );

    // 선물 만기월 코드: 1월=F, 2월=G, ..., 12월=Z
    private static final char[] MONTH_CODES = {'F', 'G', 'H', 'J', 'K', 'M', 'N', 'Q', 'U', 'V', 'X', 'Z'};

    // 분기월(3,6,9,12) 사용 종목
    private static final Set<String> QUARTERLY_SYMBOLS = Set.of("NQ", "ES", "YM", "6E", "6J", "KM");

    static {
        FUTURES_MAP.put("KM", new FuturesInfo("KM", "KOSPI200 야간선물", "코스피200", "index", "CME"));
        FUTURES_MAP.put("NQ", new FuturesInfo("NQ", "나스닥100 선물", "나스닥100", "index", "CME"));
        FUTURES_MAP.put("ES", new FuturesInfo("ES", "S&P500 E-mini", "S&P500", "index", "CME"));
        FUTURES_MAP.put("YM", new FuturesInfo("YM", "다우 E-mini", "다우존스", "index", "CBT"));
        FUTURES_MAP.put("CL", new FuturesInfo("CL", "WTI 원유", "WTI", "commodity", "NYM"));
        FUTURES_MAP.put("GC", new FuturesInfo("GC", "금 선물", "Gold", "commodity", "CMX"));
        FUTURES_MAP.put("6E", new FuturesInfo("6E", "유로/달러", "EUR/USD", "currency", "CME"));
        FUTURES_MAP.put("6J", new FuturesInfo("6J", "엔/달러", "JPY/USD", "currency", "CME"));
    }

    // 캐시 (30초)
    private final ConcurrentHashMap<String, CachedFutures> cache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 30 * 1000;

    @Data
    @Builder
    public static class FuturesQuote {
        private String symbol;
        private String name;
        private String shortName;
        private String category;      // index, commodity, currency
        private String exchange;
        private BigDecimal currentPrice;
        private BigDecimal changePrice;
        private BigDecimal changeRate;
        private BigDecimal highPrice;
        private BigDecimal lowPrice;
        private BigDecimal volume;
        private String sign;          // 1:상한, 2:상승, 3:보합, 4:하한, 5:하락
        private String tradingTime;   // 체결시간
        private LocalDateTime fetchedAt;
        private boolean success;
        private String errorMessage;
    }

    @Data
    public static class FuturesInfo {
        private final String symbol;
        private final String name;
        private final String shortName;
        private final String category;
        private final String exchange;
    }

    private static class CachedFutures {
        FuturesQuote quote;
        long timestamp;

        CachedFutures(FuturesQuote quote) {
            this.quote = quote;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_DURATION_MS;
        }
    }

    /**
     * 전체 선물 시세 일괄 조회
     */
    public List<FuturesQuote> getAllFuturesQuotes() {
        List<FuturesQuote> quotes = new ArrayList<>();

        for (Map.Entry<String, FuturesInfo> entry : FUTURES_MAP.entrySet()) {
            FuturesQuote quote = getFuturesQuote(entry.getKey());
            quotes.add(quote);

            // KIS API rate limit 방지
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return quotes;
    }

    /**
     * 개별 선물 시세 조회
     */
    public FuturesQuote getFuturesQuote(String symbol) {
        // 캐시 확인
        CachedFutures cached = cache.get(symbol);
        if (cached != null && cached.isValid()) {
            return cached.quote;
        }

        FuturesInfo info = FUTURES_MAP.get(symbol);
        if (info == null) {
            return FuturesQuote.builder()
                    .symbol(symbol)
                    .success(false)
                    .errorMessage("알 수 없는 선물 심볼: " + symbol)
                    .fetchedAt(LocalDateTime.now())
                    .build();
        }

        String token = koreaInvestmentService.getAccessToken();
        if (token == null) {
            return createErrorQuote(info, "KIS 토큰 발급 실패");
        }

        try {
            // KIS 해외선물 현재가 API (HHDFS76200200)
            String excd = info.getExchange();
            String contractSymbol = getActiveContractSymbol(symbol);

            String url = baseUrl + "/uapi/overseas-futures/v1/quotations/inquire-price"
                    + "?EXCD=" + excd
                    + "&SYMB=" + contractSymbol;

            log.debug("[해외선물] {} 조회 요청: EXCD={}, SYMB={}", info.getName(), excd, contractSymbol);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("authorization", "Bearer " + token);
            headers.set("appkey", appKey);
            headers.set("appsecret", appSecret);
            headers.set("tr_id", "HHDFS76200200");
            headers.set("custtype", "P");

            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String rtCd = root.path("rt_cd").asText();

                if ("0".equals(rtCd)) {
                    JsonNode output = root.path("output");

                    BigDecimal currentPrice = parseBd(output.path("last").asText());
                    BigDecimal changePrice = parseBd(output.path("diff").asText());
                    BigDecimal changeRate = parseBd(output.path("rate").asText());
                    BigDecimal highPrice = parseBd(output.path("high").asText());
                    BigDecimal lowPrice = parseBd(output.path("low").asText());
                    BigDecimal volume = parseBd(output.path("tvol").asText());
                    String sign = output.path("sign").asText("");
                    String tradingTime = output.path("tymd").asText("");

                    FuturesQuote quote = FuturesQuote.builder()
                            .symbol(symbol)
                            .name(info.getName())
                            .shortName(info.getShortName())
                            .category(info.getCategory())
                            .exchange(info.getExchange())
                            .currentPrice(currentPrice)
                            .changePrice(changePrice)
                            .changeRate(changeRate)
                            .highPrice(highPrice)
                            .lowPrice(lowPrice)
                            .volume(volume)
                            .sign(sign)
                            .tradingTime(tradingTime)
                            .fetchedAt(LocalDateTime.now())
                            .success(true)
                            .build();

                    cache.put(symbol, new CachedFutures(quote));
                    log.debug("[해외선물] {} - {}p ({}%)", info.getName(), currentPrice, changeRate);
                    return quote;
                } else {
                    String msg = root.path("msg1").asText("API 오류");
                    log.warn("[해외선물] {} API 오류: {}", symbol, msg);
                    return createErrorQuote(info, msg);
                }
            }
        } catch (Exception e) {
            log.error("[해외선물] {} 조회 실패: {}", symbol, e.getMessage());
        }

        return createErrorQuote(info, "조회 실패");
    }

    /**
     * 코스피 영향 예측 생성
     */
    public Map<String, Object> getKospiImpactAnalysis() {
        List<FuturesQuote> quotes = getAllFuturesQuotes();
        Map<String, Object> analysis = new LinkedHashMap<>();

        // 코스피200 야간선물
        FuturesQuote kospi = quotes.stream()
                .filter(q -> "KM".equals(q.getSymbol()) && q.isSuccess())
                .findFirst().orElse(null);

        // 나스닥 선물
        FuturesQuote nasdaq = quotes.stream()
                .filter(q -> "NQ".equals(q.getSymbol()) && q.isSuccess())
                .findFirst().orElse(null);

        // S&P500 선물
        FuturesQuote sp500 = quotes.stream()
                .filter(q -> "ES".equals(q.getSymbol()) && q.isSuccess())
                .findFirst().orElse(null);

        // 영향 분석
        String impact = "NEUTRAL";
        String comment = "글로벌 시장 데이터를 조회할 수 없습니다.";
        int impactScore = 50; // 0~100 (50=중립, 100=매우 긍정)

        if (kospi != null && kospi.getChangeRate() != null) {
            BigDecimal rate = kospi.getChangeRate();
            if (rate.compareTo(new BigDecimal("0.5")) >= 0) {
                impact = "POSITIVE";
                impactScore = 70;
                comment = String.format("코스피200 야간선물 +%.2f%% 상승. 내일 코스피 갭업 가능성.", rate);
            } else if (rate.compareTo(new BigDecimal("-0.5")) <= 0) {
                impact = "NEGATIVE";
                impactScore = 30;
                comment = String.format("코스피200 야간선물 %.2f%% 하락. 내일 코스피 갭다운 주의.", rate);
            } else {
                impact = "NEUTRAL";
                impactScore = 50;
                comment = String.format("코스피200 야간선물 %.2f%% 소폭 변동. 보합 출발 예상.", rate);
            }

            // 미국 지수 반영
            if (nasdaq != null && nasdaq.getChangeRate() != null) {
                BigDecimal nasdaqRate = nasdaq.getChangeRate();
                if (nasdaqRate.compareTo(new BigDecimal("-1.0")) <= 0) {
                    impactScore = Math.max(10, impactScore - 20);
                    comment += String.format(" 나스닥 선물 %.2f%% 급락 주의.", nasdaqRate);
                } else if (nasdaqRate.compareTo(new BigDecimal("1.0")) >= 0) {
                    impactScore = Math.min(90, impactScore + 15);
                    comment += String.format(" 나스닥 선물 +%.2f%% 강세.", nasdaqRate);
                }
            }
        }

        analysis.put("impact", impact);
        analysis.put("impactScore", impactScore);
        analysis.put("comment", comment);
        analysis.put("quotes", quotes);
        analysis.put("fetchedAt", LocalDateTime.now());

        return analysis;
    }

    /**
     * 캐시 클리어
     */
    public void clearCache() {
        cache.clear();
        log.info("해외선물 캐시 클리어됨");
    }

    /**
     * 현재 날짜 기준 활성 계약월 심볼 생성
     * 예: CL → CLJ6 (2026년 4월물), NQ → NQH6 (2026년 3월물, 분기)
     */
    String getActiveContractSymbol(String baseSymbol) {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        int year = now.getYear() % 10; // 마지막 한자리 (2026 → 6)

        if (QUARTERLY_SYMBOLS.contains(baseSymbol)) {
            // 분기 계약: 3(H), 6(M), 9(U), 12(Z)
            int[] qMonths = {3, 6, 9, 12};
            for (int qm : qMonths) {
                if (qm >= month) {
                    return baseSymbol + MONTH_CODES[qm - 1] + year;
                }
            }
            // 12월 이후 → 내년 3월물
            return baseSymbol + "H" + ((year + 1) % 10);
        } else {
            // 월물 계약: 현재월+1 (front month)
            int frontMonth = month + 1;
            int frontYear = year;
            if (frontMonth > 12) {
                frontMonth = 1;
                frontYear = (frontYear + 1) % 10;
            }
            return baseSymbol + MONTH_CODES[frontMonth - 1] + frontYear;
        }
    }

    private FuturesQuote createErrorQuote(FuturesInfo info, String message) {
        return FuturesQuote.builder()
                .symbol(info.getSymbol())
                .name(info.getName())
                .shortName(info.getShortName())
                .category(info.getCategory())
                .exchange(info.getExchange())
                .success(false)
                .errorMessage(message)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    private BigDecimal parseBd(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
