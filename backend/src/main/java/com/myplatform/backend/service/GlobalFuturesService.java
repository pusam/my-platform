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
 * - 코스피200 지수 (^KS200) — KRX 장중(09:00~15:30)만 업데이트, 야간선물 미지원
 * - 나스닥100 선물 (NQ=F)
 * - S&P500 선물 (ES=F)
 * - 다우 선물 (YM=F)
 * - WTI 원유 선물 (CL=F)
 * - 금 선물 (GC=F)
 * - 구리 선물 (HG=F)
 * - 달러 인덱스 (DX-Y.NYB)
 * - 유로/달러 (EURUSD=X), 달러/엔 (USDJPY=X)
 * - 달러/원 (KRW=X)
 * - VIX 공포지수 (^VIX)
 * - 미국 10년물 금리 (^TNX)
 * - CNN Fear & Greed Index
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
        FUTURES_MAP.put("KM", new FuturesInfo("KM", "^KS200", "KOSPI200 지수", "코스피200", "index", "KRX"));
        FUTURES_MAP.put("NQ", new FuturesInfo("NQ", "NQ=F", "나스닥100 선물", "나스닥100", "index", "CME"));
        FUTURES_MAP.put("ES", new FuturesInfo("ES", "ES=F", "S&P500 E-mini", "S&P500", "index", "CME"));
        FUTURES_MAP.put("YM", new FuturesInfo("YM", "YM=F", "다우 E-mini", "다우존스", "index", "CBOT"));
        FUTURES_MAP.put("CL", new FuturesInfo("CL", "CL=F", "WTI 원유", "WTI", "commodity", "NYMEX"));
        FUTURES_MAP.put("BZ", new FuturesInfo("BZ", "BZ=F", "브렌트유", "Brent", "commodity", "ICE"));
        FUTURES_MAP.put("GC", new FuturesInfo("GC", "GC=F", "금 선물", "Gold", "commodity", "COMEX"));
        FUTURES_MAP.put("HG", new FuturesInfo("HG", "HG=F", "구리 선물", "Copper", "commodity", "COMEX"));
        FUTURES_MAP.put("DXY", new FuturesInfo("DXY", "DX-Y.NYB", "달러 인덱스", "DXY", "currency", "ICE"));
        FUTURES_MAP.put("6E", new FuturesInfo("6E", "EURUSD=X", "유로/달러", "EUR/USD", "currency", "CME"));
        FUTURES_MAP.put("6J", new FuturesInfo("6J", "USDJPY=X", "달러/엔", "USD/JPY", "currency", "CME"));
        FUTURES_MAP.put("KRW", new FuturesInfo("KRW", "KRW=X", "달러/원", "USD/KRW", "currency", "FX"));
        FUTURES_MAP.put("VIX", new FuturesInfo("VIX", "^VIX", "VIX 공포지수", "VIX", "volatility", "CBOE"));
        FUTURES_MAP.put("US10Y", new FuturesInfo("US10Y", "^TNX", "미국 10년물 금리", "US10Y", "bond", "CBOE"));
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
        private long dataAgeMinutes;  // 데이터 경과 시간 (분)
        private boolean stale;        // 데이터가 오래되었는지 (30분 이상)
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

            // 데이터 경과 시간 계산
            long dataAgeMinutes = 0;
            boolean stale = false;
            if (regularMarketTimeEpoch > 0) {
                long nowEpoch = Instant.now().getEpochSecond();
                dataAgeMinutes = (nowEpoch - regularMarketTimeEpoch) / 60;
                stale = dataAgeMinutes > 30; // 30분 이상 경과 시 stale
            }

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
                    .dataAgeMinutes(dataAgeMinutes)
                    .stale(stale)
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
     * 코스피 영향 예측 생성 (복합 가중치 모델)
     *
     * 가중치 배분:
     * - 나스닥100 선물: 35% (미국 기술주 연동)
     * - S&P500 선물: 15% (글로벌 대형주)
     * - WTI 원유: 15% (에너지·인플레 리스크)
     * - 달러/원 환율: 20% (외국인 자금 흐름 핵심)
     * - VIX 공포지수: 15% (시장 변동성)
     */
    public Map<String, Object> getKospiImpactAnalysis() {
        List<FuturesQuote> quotes = getAllFuturesQuotes();
        Map<String, Object> analysis = new LinkedHashMap<>();

        // 종목별 추출
        Map<String, FuturesQuote> quoteMap = new LinkedHashMap<>();
        for (FuturesQuote q : quotes) {
            if (q.isSuccess()) quoteMap.put(q.getSymbol(), q);
        }

        // 복합 점수 계산 (0~100, 50=중립)
        double weightedScore = 50.0;
        List<Map<String, Object>> riskFactors = new ArrayList<>();

        // 1) 나스닥100 선물 (가중치 35%)
        FuturesQuote nq = quoteMap.get("NQ");
        if (nq != null && nq.getChangeRate() != null) {
            double nqRate = nq.getChangeRate().doubleValue();
            double nqContrib = clampContrib(nqRate * 7.0); // ±1% → ±7점
            weightedScore += nqContrib * 0.35;
            riskFactors.add(createFactor("나스닥100", nqRate, nqContrib > 0 ? "POSITIVE" : nqContrib < 0 ? "NEGATIVE" : "NEUTRAL", 35));
        }

        // 2) S&P500 선물 (가중치 15%)
        FuturesQuote es = quoteMap.get("ES");
        if (es != null && es.getChangeRate() != null) {
            double esRate = es.getChangeRate().doubleValue();
            double esContrib = clampContrib(esRate * 6.0);
            weightedScore += esContrib * 0.15;
            riskFactors.add(createFactor("S&P500", esRate, esContrib > 0 ? "POSITIVE" : esContrib < 0 ? "NEGATIVE" : "NEUTRAL", 15));
        }

        // 3) WTI 원유 (가중치 15%) - 유가 급등은 코스피에 악재
        FuturesQuote cl = quoteMap.get("CL");
        if (cl != null && cl.getChangeRate() != null) {
            double clRate = cl.getChangeRate().doubleValue();
            // 유가 상승 → 코스피 약세 (역방향), 단 소폭(±2% 이내)은 무시
            double clContrib = 0;
            if (Math.abs(clRate) > 2.0) {
                clContrib = clampContrib(-clRate * 3.0); // 유가 5% 급등 → -15점
            } else {
                clContrib = clampContrib(-clRate * 1.5);
            }
            weightedScore += clContrib * 0.15;
            String clSignal = clRate > 3.0 ? "NEGATIVE" : clRate < -3.0 ? "POSITIVE" : "NEUTRAL";
            riskFactors.add(createFactor("WTI 원유", clRate, clSignal, 15));
        }

        // 4) 달러/원 환율 (가중치 20%) - 원화 약세(환율 상승)는 외국인 이탈 → 코스피 약세
        FuturesQuote krw = quoteMap.get("KRW");
        if (krw != null && krw.getChangeRate() != null) {
            double krwRate = krw.getChangeRate().doubleValue();
            // 환율 상승(원화 약세) → 코스피 하락
            double krwContrib = clampContrib(-krwRate * 8.0); // ±0.5% → ±4점
            weightedScore += krwContrib * 0.20;
            String krwSignal = krwRate > 0.3 ? "NEGATIVE" : krwRate < -0.3 ? "POSITIVE" : "NEUTRAL";
            riskFactors.add(createFactor("달러/원", krwRate, krwSignal, 20));
        }

        // 5) VIX 공포지수 (가중치 15%) - VIX 상승 → 공포 → 코스피 약세
        FuturesQuote vix = quoteMap.get("VIX");
        if (vix != null && vix.getCurrentPrice() != null) {
            double vixLevel = vix.getCurrentPrice().doubleValue();
            double vixRate = vix.getChangeRate() != null ? vix.getChangeRate().doubleValue() : 0;
            // VIX 절대 수준 + 변동률 복합
            double vixContrib = 0;
            if (vixLevel >= 30) vixContrib = -15; // 극심한 공포
            else if (vixLevel >= 25) vixContrib = -10;
            else if (vixLevel >= 20) vixContrib = -5;
            else if (vixLevel < 15) vixContrib = 3; // 안정

            // VIX 급등 시 추가 약세 반영
            if (vixRate > 10) vixContrib -= 8;
            else if (vixRate > 5) vixContrib -= 4;
            // VIX 급락 시 진정 국면 반영 — 절대값이 높아도 빠르게 하락 중이면 페널티 경감
            else if (vixRate <= -10) vixContrib += 8;  // VIX 10%+ 급락 → 큰 진정
            else if (vixRate <= -5) vixContrib += 5;   // VIX 5%+ 급락 → 진정

            vixContrib = clampContrib(vixContrib);
            weightedScore += vixContrib * 0.15;

            // VIX 시그널: 절대값 기준 (25 이상이면 무조건 부정)
            String vixSignal;
            if (vixLevel >= 25) {
                vixSignal = "NEGATIVE"; // 공포 구간 → 항상 부정
            } else if (vixLevel < 18) {
                vixSignal = "POSITIVE";
            } else {
                vixSignal = "NEUTRAL";
            }
            riskFactors.add(createFactor("VIX", vixRate, vixSignal, 15));
        }

        // KOSPI200 보정
        FuturesQuote kospi = quoteMap.get("KM");
        if (kospi != null && kospi.getChangeRate() != null) {
            double kRate = kospi.getChangeRate().doubleValue();
            weightedScore += clampContrib(kRate * 3.0) * 0.10; // 소폭 보정
        }

        // 최종 점수 클램핑 (5~95)
        int impactScore = (int) Math.max(5, Math.min(95, Math.round(weightedScore)));

        // ============================================================
        // 임계값 오버라이드: 극단적 시장 상황 감지 시 가중치 점수 무시
        // ============================================================
        boolean thresholdOverride = false;
        List<String> overrideReasons = new ArrayList<>();

        // 조건 1: VIX >= 25 (공포 구간)
        // 예외: VIX가 전일 대비 5% 이상 하락 중이고, 하위 지표(WTI/환율)가 긍정이면
        //       시장 진정 국면으로 판단 → 폭락 경계 오버라이드 해제
        if (vix != null && vix.getCurrentPrice() != null) {
            double vixLevel = vix.getCurrentPrice().doubleValue();
            double vixChangeRate = vix.getChangeRate() != null ? vix.getChangeRate().doubleValue() : 0;

            boolean isVixCalming = vixChangeRate <= -5.0; // VIX 전일 대비 5% 이상 급락 중

            // 하위 지표 긍정 여부 체크 (WTI 안정 + 환율 안정/강세)
            boolean subIndicatorsPositive = false;
            if (isVixCalming) {
                boolean wtiOk = true;  // WTI가 폭등하지 않으면 OK
                boolean krwOk = true;  // 환율이 급등하지 않으면 OK
                if (cl != null && cl.getChangeRate() != null) {
                    wtiOk = cl.getChangeRate().doubleValue() < 5.0; // WTI +5% 미만
                }
                if (krw != null && krw.getChangeRate() != null) {
                    krwOk = krw.getChangeRate().doubleValue() < 0.5; // 환율 +0.5% 미만 (원화 약세 제한적)
                }
                subIndicatorsPositive = wtiOk && krwOk;
            }

            boolean marketCalming = isVixCalming && subIndicatorsPositive;

            if (vixLevel >= 30 && !marketCalming) {
                thresholdOverride = true;
                overrideReasons.add(String.format("VIX %.1f (극심한 공포)", vixLevel));
            } else if (vixLevel >= 25 && !marketCalming) {
                thresholdOverride = true;
                overrideReasons.add(String.format("VIX %.1f (공포)", vixLevel));
            } else if (vixLevel >= 25 && marketCalming) {
                log.info("[코스피 전망] VIX {} (≥25) but 진정 국면 — VIX 변동 {}%, 오버라이드 해제",
                        String.format("%.1f", vixLevel), String.format("%.1f", vixChangeRate));
            }
        }

        // 조건 2: WTI 원유 +10% 이상 폭등
        if (cl != null && cl.getChangeRate() != null) {
            double clRate = cl.getChangeRate().doubleValue();
            if (clRate >= 10.0) {
                thresholdOverride = true;
                overrideReasons.add(String.format("WTI 원유 +%.1f%% 폭등", clRate));
            }
        }

        // 조건 3: 브렌트유 +10% 이상 폭등
        FuturesQuote bz = quoteMap.get("BZ");
        if (bz != null && bz.getChangeRate() != null) {
            double bzRate = bz.getChangeRate().doubleValue();
            if (bzRate >= 10.0) {
                thresholdOverride = true;
                overrideReasons.add(String.format("브렌트유 +%.1f%% 폭등", bzRate));
            }
        }

        // 조건 4: 나스닥 -3% 이상 폭락
        if (nq != null && nq.getChangeRate() != null) {
            double nqRate = nq.getChangeRate().doubleValue();
            if (nqRate <= -3.0) {
                thresholdOverride = true;
                overrideReasons.add(String.format("나스닥 %.1f%% 폭락", nqRate));
            }
        }

        // 등급 + 코멘트 결정
        String impact;
        String alertLevel;
        String comment;

        if (thresholdOverride) {
            // 임계값 오버라이드 → 강제 최악 등급
            impact = "NEGATIVE";
            alertLevel = "CRISIS";
            impactScore = 3; // 게이지 바 좌측 극단
            String reasons = String.join(" + ", overrideReasons);
            comment = buildComment(quoteMap, "폭락 경계 — " + reasons);
            log.warn("[코스피 전망] 임계값 오버라이드 발동: {}", reasons);
        } else if (impactScore <= 20) {
            impact = "NEGATIVE";
            alertLevel = "EXTREME_NEGATIVE";
            comment = buildComment(quoteMap, "극심한 하방 변동성 경고");
        } else if (impactScore <= 35) {
            impact = "NEGATIVE";
            alertLevel = "NEGATIVE";
            comment = buildComment(quoteMap, "코스피 하방 압력 주의");
        } else if (impactScore <= 45) {
            impact = "NEGATIVE";
            alertLevel = "WEAK_NEGATIVE";
            comment = buildComment(quoteMap, "소폭 약세 출발 예상");
        } else if (impactScore <= 55) {
            impact = "NEUTRAL";
            alertLevel = "NEUTRAL";
            comment = buildComment(quoteMap, "보합 출발 예상");
        } else if (impactScore <= 65) {
            impact = "POSITIVE";
            alertLevel = "WEAK_POSITIVE";
            comment = buildComment(quoteMap, "소폭 강세 출발 예상");
        } else if (impactScore <= 80) {
            impact = "POSITIVE";
            alertLevel = "POSITIVE";
            comment = buildComment(quoteMap, "코스피 긍정적 흐름 예상");
        } else {
            impact = "POSITIVE";
            alertLevel = "EXTREME_POSITIVE";
            comment = buildComment(quoteMap, "강한 상승 모멘텀 예상");
        }

        analysis.put("impact", impact);
        analysis.put("alertLevel", alertLevel);
        analysis.put("impactScore", impactScore);
        analysis.put("comment", comment);
        analysis.put("riskFactors", riskFactors);
        analysis.put("quotes", quotes);
        analysis.put("fearGreed", getFearGreedIndex());
        analysis.put("fetchedAt", LocalDateTime.now());

        return analysis;
    }

    private double clampContrib(double value) {
        return Math.max(-20, Math.min(20, value));
    }

    private Map<String, Object> createFactor(String name, double changeRate, String signal, int weight) {
        Map<String, Object> factor = new LinkedHashMap<>();
        factor.put("name", name);
        factor.put("changeRate", Math.round(changeRate * 100.0) / 100.0);
        factor.put("signal", signal);
        factor.put("weight", weight);
        return factor;
    }

    private String buildComment(Map<String, FuturesQuote> quoteMap, String headline) {
        StringBuilder sb = new StringBuilder(headline).append(".");

        FuturesQuote nq = quoteMap.get("NQ");
        if (nq != null && nq.getChangeRate() != null) {
            sb.append(String.format(" 나스닥 %+.2f%%", nq.getChangeRate().doubleValue()));
        }

        FuturesQuote cl = quoteMap.get("CL");
        if (cl != null && cl.getChangeRate() != null && Math.abs(cl.getChangeRate().doubleValue()) > 1.0) {
            sb.append(String.format(", WTI %+.2f%%", cl.getChangeRate().doubleValue()));
        }

        FuturesQuote krw = quoteMap.get("KRW");
        if (krw != null && krw.getCurrentPrice() != null) {
            sb.append(String.format(", 원/달러 %.0f원", krw.getCurrentPrice().doubleValue()));
        }

        FuturesQuote vix = quoteMap.get("VIX");
        if (vix != null && vix.getCurrentPrice() != null) {
            double vixLevel = vix.getCurrentPrice().doubleValue();
            sb.append(String.format(", VIX %.1f", vixLevel));
            if (vixLevel >= 30) sb.append("(극심한 공포)");
            else if (vixLevel >= 25) sb.append("(공포)");
            else if (vixLevel >= 20) sb.append("(경계)");
            else sb.append("(안정)");
        }

        sb.append(".");
        return sb.toString();
    }

    public void clearCache() {
        cache.clear();
        fearGreedCache = null;
        log.info("해외선물 캐시 클리어됨");
    }

    // ==================== CNN Fear & Greed Index ====================
    private static final String FEAR_GREED_URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";
    private volatile Map<String, Object> fearGreedCache = null;
    private volatile long fearGreedCacheTime = 0;
    private static final long FEAR_GREED_CACHE_MS = 5 * 60 * 1000; // 5분 캐시

    /**
     * CNN Fear & Greed Index 조회
     * @return { score: 25, rating: "Extreme Fear", previousClose: 28, change: -3 }
     */
    public Map<String, Object> getFearGreedIndex() {
        if (fearGreedCache != null && System.currentTimeMillis() - fearGreedCacheTime < FEAR_GREED_CACHE_MS) {
            return fearGreedCache;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            HttpEntity<String> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(FEAR_GREED_URL, HttpMethod.GET, request, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[Fear&Greed] API 응답 오류: {}", response.getStatusCode());
                return createFearGreedError("API 응답 오류");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode fng = root.path("fear_and_greed");

            if (fng.isMissingNode()) {
                return createFearGreedError("데이터 없음");
            }

            double score = fng.path("score").asDouble(0);
            String rating = fng.path("rating").asText("Unknown");
            double previousClose = fng.path("previous_close").asDouble(0);
            double change = score - previousClose;

            // 한국어 등급 매핑
            String ratingKr;
            String level;
            if (score <= 25) { ratingKr = "극심한 공포"; level = "extreme-fear"; }
            else if (score <= 45) { ratingKr = "공포"; level = "fear"; }
            else if (score <= 55) { ratingKr = "중립"; level = "neutral"; }
            else if (score <= 75) { ratingKr = "탐욕"; level = "greed"; }
            else { ratingKr = "극심한 탐욕"; level = "extreme-greed"; }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("score", Math.round(score));
            result.put("rating", rating);
            result.put("ratingKr", ratingKr);
            result.put("level", level);
            result.put("previousClose", Math.round(previousClose));
            result.put("change", Math.round(change));
            result.put("fetchedAt", LocalDateTime.now().toString());

            fearGreedCache = result;
            fearGreedCacheTime = System.currentTimeMillis();
            log.debug("[Fear&Greed] Score: {} ({})", Math.round(score), rating);
            return result;

        } catch (Exception e) {
            log.error("[Fear&Greed] 조회 실패: {}", e.getMessage());
            return createFearGreedError("조회 실패: " + e.getMessage());
        }
    }

    private Map<String, Object> createFearGreedError(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("errorMessage", message);
        result.put("score", 50);
        result.put("ratingKr", "데이터 없음");
        result.put("level", "neutral");
        result.put("change", 0);
        return result;
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
