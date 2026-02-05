package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.TradingIndicatorDto.GlobalMarketSignal;
import com.myplatform.backend.dto.TradingIndicatorDto.NasdaqFuturesResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 글로벌 시장 지표 서비스
 *
 * - 나스닥 100 선물 (NQ=F) 지수 조회
 * - S&P 500 선물 (ES=F) 조회
 * - 글로벌 악재 필터 (나스닥 -0.5% 이하 시 매수 보류)
 *
 * 데이터 소스: Yahoo Finance API (무료)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalMarketService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Yahoo Finance API 엔드포인트
    private static final String YAHOO_FINANCE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1d";

    // 심볼
    public static final String NASDAQ_FUTURES = "NQ=F";      // 나스닥 100 선물
    public static final String SP500_FUTURES = "ES=F";       // S&P 500 선물
    public static final String VIX = "^VIX";                 // VIX (공포지수)

    // 글로벌 악재 필터 임계값
    private static final BigDecimal HALT_THRESHOLD = new BigDecimal("-0.5");   // 매수 보류 임계값
    private static final BigDecimal CAUTION_THRESHOLD = new BigDecimal("-0.5");
    private static final BigDecimal NEGATIVE_THRESHOLD = new BigDecimal("-1.0");
    private static final BigDecimal POSITIVE_THRESHOLD = new BigDecimal("0.5");

    // 캐시 (1분)
    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 60 * 1000;  // 1분

    private static class CachedResult {
        NasdaqFuturesResult result;
        long timestamp;

        CachedResult(NasdaqFuturesResult result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_DURATION_MS;
        }
    }

    /**
     * 나스닥 100 선물 지수 조회
     *
     * @return 나스닥 선물 결과
     */
    public NasdaqFuturesResult getNasdaqFutures() {
        return getGlobalIndex(NASDAQ_FUTURES, "Nasdaq 100 Futures");
    }

    /**
     * S&P 500 선물 지수 조회
     */
    public NasdaqFuturesResult getSP500Futures() {
        return getGlobalIndex(SP500_FUTURES, "S&P 500 E-mini Futures");
    }

    /**
     * 글로벌 악재 필터 체크
     * 나스닥 선물이 -0.5% 이하면 매수 보류
     *
     * @return true = 매수 보류, false = 정상
     */
    public boolean shouldHaltBuying() {
        NasdaqFuturesResult nasdaq = getNasdaqFutures();
        if (nasdaq == null || nasdaq.getChangeRate() == null) {
            // 데이터 없으면 기본값 false (매수 허용)
            log.warn("나스닥 선물 데이터 없음 - 글로벌 필터 비활성화");
            return false;
        }
        return nasdaq.isTradingHalt();
    }

    /**
     * 글로벌 지수 조회 (공통)
     */
    private NasdaqFuturesResult getGlobalIndex(String symbol, String name) {
        // 캐시 확인
        CachedResult cached = cache.get(symbol);
        if (cached != null && cached.isValid()) {
            log.debug("글로벌 지수 캐시 히트: {}", symbol);
            return cached.result;
        }

        log.info("글로벌 지수 조회: {} ({})", name, symbol);

        try {
            String url = String.format(YAHOO_FINANCE_URL, symbol);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.error("Yahoo Finance API 응답 오류: {}", response.getStatusCode());
                return createErrorResult(symbol, name, "API 응답 오류");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode chart = root.path("chart");
            JsonNode result = chart.path("result").get(0);

            if (result == null) {
                String error = chart.path("error").path("description").asText("Unknown error");
                log.error("Yahoo Finance API 에러: {}", error);
                return createErrorResult(symbol, name, error);
            }

            // 메타 정보
            JsonNode meta = result.path("meta");
            BigDecimal currentPrice = new BigDecimal(meta.path("regularMarketPrice").asText("0"));
            BigDecimal prevClose = new BigDecimal(meta.path("chartPreviousClose").asText("0"));

            // 등락 계산
            BigDecimal change = currentPrice.subtract(prevClose).setScale(2, RoundingMode.HALF_UP);
            BigDecimal changeRate = BigDecimal.ZERO;
            if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                changeRate = change.divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            // 신호 결정
            GlobalMarketSignal signal = determineSignal(changeRate);
            boolean isHalt = changeRate.compareTo(HALT_THRESHOLD) <= 0;

            // 해석 생성
            String interpretation = generateInterpretation(signal, changeRate, isHalt);

            NasdaqFuturesResult futuresResult = NasdaqFuturesResult.builder()
                    .symbol(symbol)
                    .name(name)
                    .price(currentPrice)
                    .change(change)
                    .changeRate(changeRate)
                    .signal(signal)
                    .interpretation(interpretation)
                    .isTradingHalt(isHalt)
                    .fetchedAt(LocalDateTime.now())
                    .build();

            // 캐시 저장
            cache.put(symbol, new CachedResult(futuresResult));

            log.info("글로벌 지수 조회 완료: {} - {}p ({}%), 신호: {}, 매수보류: {}",
                    name, currentPrice, changeRate, signal, isHalt);

            return futuresResult;

        } catch (Exception e) {
            log.error("글로벌 지수 조회 실패 [{}]: {}", symbol, e.getMessage(), e);
            return createErrorResult(symbol, name, e.getMessage());
        }
    }

    /**
     * 글로벌 시장 신호 결정
     */
    private GlobalMarketSignal determineSignal(BigDecimal changeRate) {
        if (changeRate == null) {
            return GlobalMarketSignal.NEUTRAL;
        }

        if (changeRate.compareTo(POSITIVE_THRESHOLD) >= 0) {
            return GlobalMarketSignal.POSITIVE;
        } else if (changeRate.compareTo(NEGATIVE_THRESHOLD) <= 0) {
            return GlobalMarketSignal.NEGATIVE;
        } else if (changeRate.compareTo(CAUTION_THRESHOLD) <= 0) {
            return GlobalMarketSignal.CAUTION;
        } else {
            return GlobalMarketSignal.NEUTRAL;
        }
    }

    /**
     * 해석 생성
     */
    private String generateInterpretation(GlobalMarketSignal signal, BigDecimal changeRate, boolean isHalt) {
        StringBuilder sb = new StringBuilder();

        String direction = changeRate.compareTo(BigDecimal.ZERO) >= 0 ? "상승" : "하락";
        sb.append(String.format("나스닥 선물 %.2f%% %s. ", changeRate.abs(), direction));

        switch (signal) {
            case POSITIVE:
                sb.append("글로벌 위험선호 심리 확대. 국내 증시 긍정적 영향 예상.");
                break;
            case NEUTRAL:
                sb.append("글로벌 시장 관망세. 국내 증시 독자 흐름 가능.");
                break;
            case CAUTION:
                sb.append("글로벌 불확실성 확대. 매수 시 주의 필요.");
                break;
            case NEGATIVE:
                sb.append("⚠️ 글로벌 리스크 오프! 국내 증시 하방 압력 예상. 매수 보류 권장.");
                break;
        }

        if (isHalt) {
            sb.append(" 🚫 자동매매 매수 보류 상태.");
        }

        return sb.toString();
    }

    /**
     * 에러 결과 생성
     */
    private NasdaqFuturesResult createErrorResult(String symbol, String name, String message) {
        return NasdaqFuturesResult.builder()
                .symbol(symbol)
                .name(name)
                .interpretation("글로벌 지수 조회 실패: " + message)
                .isTradingHalt(false)  // 에러 시 매수 허용 (안전 장치)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 캐시 클리어
     */
    public void clearCache() {
        cache.clear();
        log.info("글로벌 시장 캐시 클리어됨");
    }
}
