package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 글로벌 선물 시세 서비스 (Yahoo Finance 기반)
 *
 * - 코스피200 야간선물 → 미지원 (KRX 전용), 대신 코스피 ETF 대체
 * - 나스닥100 선물 (NQ=F)
 * - S&P500 선물 (ES=F)
 * - 다우 선물 (YM=F)
 * - WTI 원유 선물 (CL=F)
 * - 금 선물 (GC=F)
 * - 유로/달러 (EURUSD=X), 달러/엔 (USDJPY=X)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalFuturesService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String YAHOO_FINANCE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1d";

    // 선물 종목 정의 (Yahoo Finance 심볼)
    private static final Map<String, FuturesInfo> FUTURES_MAP = new LinkedHashMap<>();

    static {
        FUTURES_MAP.put("KM", new FuturesInfo("KM", "^KS200", "KOSPI200 야간선물", "코스피200", "index", "KRX"));
        FUTURES_MAP.put("NQ", new FuturesInfo("NQ", "NQ=F", "나스닥100 선물", "나스닥100", "index", "CME"));
        FUTURES_MAP.put("ES", new FuturesInfo("ES", "ES=F", "S&P500 E-mini", "S&P500", "index", "CME"));
        FUTURES_MAP.put("YM", new FuturesInfo("YM", "YM=F", "다우 E-mini", "다우존스", "index", "CBOT"));
        FUTURES_MAP.put("CL", new FuturesInfo("CL", "CL=F", "WTI 원유", "WTI", "commodity", "NYMEX"));
        FUTURES_MAP.put("GC", new FuturesInfo("GC", "GC=F", "금 선물", "Gold", "commodity", "COMEX"));
        FUTURES_MAP.put("6E", new FuturesInfo("6E", "EURUSD=X", "유로/달러", "EUR/USD", "currency", "CME"));
        FUTURES_MAP.put("6J", new FuturesInfo("6J", "USDJPY=X", "달러/엔", "USD/JPY", "currency", "CME"));
    }

    // 캐시 (60초)
    private final ConcurrentHashMap<String, CachedFutures> cache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 60 * 1000;

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
        private String tradingTime;   // 실제 마켓 체결 시간 (Yahoo regularMarketTime)
        private String marketStatus;  // OPEN, CLOSED, PRE, POST
        private LocalDateTime fetchedAt;
        private boolean success;
        private String errorMessage;
    }

    @Data
    public static class FuturesInfo {
        private final String symbol;        // 내부 키 (KM, NQ, CL 등)
        private final String yahooSymbol;   // Yahoo Finance 심볼
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
        }

        return quotes;
    }

    /**
     * 개별 선물 시세 조회 (Yahoo Finance)
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

        try {
            String url = String.format(YAHOO_FINANCE_URL, info.getYahooSymbol());

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            HttpEntity<String> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[해외선물] {} Yahoo Finance 응답 오류: {}", symbol, response.getStatusCode());
                return createErrorQuote(info, "API 응답 오류");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode result = root.path("chart").path("result").get(0);

            if (result == null) {
                log.warn("[해외선물] {} Yahoo Finance 결과 없음", symbol);
                return createErrorQuote(info, "데이터 없음");
            }

            JsonNode meta = result.path("meta");
            BigDecimal currentPrice = parseBd(meta.path("regularMarketPrice").asText());
            BigDecimal prevClose = parseBd(meta.path("chartPreviousClose").asText());

            if (currentPrice == null) {
                return createErrorQuote(info, "현재가 없음");
            }

            // 등락 계산
            BigDecimal changePrice = BigDecimal.ZERO;
            BigDecimal changeRate = BigDecimal.ZERO;
            if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                changePrice = currentPrice.subtract(prevClose).setScale(2, RoundingMode.HALF_UP);
                changeRate = changePrice.divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            // 고가/저가
            BigDecimal highPrice = parseBd(meta.path("regularMarketDayHigh").asText());
            BigDecimal lowPrice = parseBd(meta.path("regularMarketDayLow").asText());
            BigDecimal volumeBd = parseBd(meta.path("regularMarketVolume").asText());

            // indicators fallback
            if (highPrice == null || lowPrice == null) {
                JsonNode indicators = result.path("indicators").path("quote").get(0);
                if (indicators != null) {
                    if (highPrice == null) highPrice = getLastFromArray(indicators.path("high"));
                    if (lowPrice == null) lowPrice = getLastFromArray(indicators.path("low"));
                    if (volumeBd == null) volumeBd = getLastFromArray(indicators.path("volume"));
                }
            }

            // 실제 마켓 체결 시간 추출
            String tradingTime = null;
            long regularMarketTimeEpoch = meta.path("regularMarketTime").asLong(0);
            if (regularMarketTimeEpoch > 0) {
                String timezone = meta.path("exchangeTimezoneName").asText("America/New_York");
                try {
                    ZonedDateTime marketTime = Instant.ofEpochSecond(regularMarketTimeEpoch)
                            .atZone(ZoneId.of(timezone));
                    ZonedDateTime kst = marketTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
                    tradingTime = kst.format(DateTimeFormatter.ofPattern("MM/dd HH:mm (E)", Locale.KOREAN)) + " KST";
                } catch (Exception e) {
                    log.debug("시간 변환 실패: {}", e.getMessage());
                }
            }

            // 마켓 상태
            String marketStatus = meta.path("marketState").asText("CLOSED");

            // sign 결정
            String sign = "3"; // 보합
            if (changeRate.compareTo(BigDecimal.ZERO) > 0) sign = "2"; // 상승
            else if (changeRate.compareTo(BigDecimal.ZERO) < 0) sign = "5"; // 하락

            FuturesQuote quote = FuturesQuote.builder()
                    .symbol(symbol)
                    .name(info.getName())
                    .shortName(info.getShortName())
                    .category(info.getCategory())
                    .exchange(info.getExchange())
                    .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP))
                    .changePrice(changePrice)
                    .changeRate(changeRate)
                    .highPrice(highPrice != null ? highPrice.setScale(2, RoundingMode.HALF_UP) : null)
                    .lowPrice(lowPrice != null ? lowPrice.setScale(2, RoundingMode.HALF_UP) : null)
                    .volume(volumeBd)
                    .sign(sign)
                    .tradingTime(tradingTime)
                    .marketStatus(marketStatus)
                    .fetchedAt(LocalDateTime.now())
                    .success(true)
                    .build();

            cache.put(symbol, new CachedFutures(quote));
            log.debug("[해외선물] {} - {}p ({}%)", info.getName(), currentPrice, changeRate);
            return quote;

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

        FuturesQuote kospi = quotes.stream()
                .filter(q -> "KM".equals(q.getSymbol()) && q.isSuccess())
                .findFirst().orElse(null);

        FuturesQuote nasdaq = quotes.stream()
                .filter(q -> "NQ".equals(q.getSymbol()) && q.isSuccess())
                .findFirst().orElse(null);

        String impact = "NEUTRAL";
        String comment = "글로벌 시장 데이터를 조회할 수 없습니다.";
        int impactScore = 50;

        // 나스닥 선물 기준으로 영향 분석
        if (nasdaq != null && nasdaq.getChangeRate() != null) {
            BigDecimal rate = nasdaq.getChangeRate();
            if (rate.compareTo(new BigDecimal("0.5")) >= 0) {
                impact = "POSITIVE";
                impactScore = 70;
                comment = String.format("나스닥 선물 +%.2f%% 상승. 코스피 긍정적 영향 예상.", rate);
            } else if (rate.compareTo(new BigDecimal("-0.5")) <= 0) {
                impact = "NEGATIVE";
                impactScore = 30;
                comment = String.format("나스닥 선물 %.2f%% 하락. 코스피 하방 압력 주의.", rate);
            } else {
                comment = String.format("나스닥 선물 %.2f%% 소폭 변동. 보합 출발 예상.", rate);
            }
        }

        // KOSPI200 데이터가 있으면 보정
        if (kospi != null && kospi.getChangeRate() != null) {
            BigDecimal kRate = kospi.getChangeRate();
            if (kRate.compareTo(new BigDecimal("0.5")) >= 0) {
                impactScore = Math.min(90, impactScore + 10);
                comment += String.format(" KOSPI200 +%.2f%% 강세.", kRate);
            } else if (kRate.compareTo(new BigDecimal("-0.5")) <= 0) {
                impactScore = Math.max(10, impactScore - 10);
                comment += String.format(" KOSPI200 %.2f%% 약세.", kRate);
            }
        }

        analysis.put("impact", impact);
        analysis.put("impactScore", impactScore);
        analysis.put("comment", comment);
        analysis.put("quotes", quotes);
        analysis.put("fetchedAt", LocalDateTime.now());

        return analysis;
    }

    public void clearCache() {
        cache.clear();
        log.info("해외선물 캐시 클리어됨");
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
        if (value == null || value.isEmpty() || "null".equals(value) || "-".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal getLastFromArray(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) return null;
        JsonNode last = array.get(array.size() - 1);
        if (last == null || last.isNull()) return null;
        return parseBd(last.asText());
    }
}
