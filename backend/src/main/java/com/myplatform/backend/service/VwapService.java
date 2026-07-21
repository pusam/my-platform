package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.TradingIndicatorDto.VwapResult;
import com.myplatform.backend.dto.TradingIndicatorDto.VwapSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * VWAP (Volume Weighted Average Price) 계산 서비스
 *
 * VWAP = Σ(Typical Price × Volume) / Σ(Volume)
 * Typical Price = (High + Low + Close) / 3
 *
 * 활용:
 * - 현재가 > VWAP: 매수 우위 (기관 매수 추정)
 * - 현재가 < VWAP: 매도 우위 (기관 매도 추정)
 *
 * 주의사항:
 * - 장 시작 직후(09:00~09:10)는 데이터 부족으로 신뢰도 낮음
 * - 09:10분 이후부터 신뢰 가능
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VwapService {

    private final KoreaInvestmentService kisService;

    private static final BigDecimal THREE = new BigDecimal("3");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 4;

    // VWAP 신호 임계값
    private static final BigDecimal STRONG_THRESHOLD = new BigDecimal("2.0");  // 2% 괴리 시 강한 신호
    private static final BigDecimal NEUTRAL_THRESHOLD = new BigDecimal("0.3"); // 0.3% 이내는 중립

    // 신뢰 가능 최소 데이터 수 (10분)
    private static final int MIN_RELIABLE_DATA_POINTS = 10;

    /**
     * 특정 종목의 당일 VWAP 계산
     *
     * @param stockCode 종목코드
     * @return VWAP 결과
     */
    public VwapResult calculateVwap(String stockCode) {
        log.debug("VWAP 계산 시작: {}", stockCode);

        if (!kisService.isConfigured()) {
            log.warn("VWAP 계산 불가 - 한투 API 미설정");
            return createErrorResult(stockCode, "API 미설정");
        }

        try {
            // 분봉 데이터 조회
            JsonNode response = kisService.getStockMinuteChart(stockCode);
            if (response == null) {
                return createErrorResult(stockCode, "분봉 데이터 조회 실패");
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                String msg = response.has("msg1") ? response.get("msg1").asText() : "Unknown error";
                log.warn("VWAP 분봉 API 에러 [{}]: {}", stockCode, msg);
                return createErrorResult(stockCode, msg);
            }

            JsonNode output2 = response.get("output2");
            if (output2 == null || !output2.isArray() || output2.isEmpty()) {
                return createErrorResult(stockCode, "분봉 데이터 없음");
            }

            // 종목명 (output1에서 가져옴)
            String stockName = stockCode;
            JsonNode output1 = response.get("output1");
            if (output1 != null && output1.has("hts_kor_isnm")) {
                stockName = output1.get("hts_kor_isnm").asText();
            }

            // VWAP 계산
            BigDecimal sumTypicalPriceVolume = BigDecimal.ZERO;
            BigDecimal sumVolume = BigDecimal.ZERO;
            BigDecimal currentPrice = null;
            int dataCount = 0;

            for (JsonNode candle : output2) {
                BigDecimal high = getDecimalValue(candle, "stck_hgpr");
                BigDecimal low = getDecimalValue(candle, "stck_lwpr");
                BigDecimal close = getDecimalValue(candle, "stck_prpr");
                BigDecimal volume = getDecimalValue(candle, "cntg_vol");

                if (high == null || low == null || close == null || volume == null) {
                    continue;
                }

                // 현재가는 첫 번째 캔들(최신)에서 가져옴
                if (currentPrice == null) {
                    currentPrice = close;
                }

                // Typical Price = (H + L + C) / 3
                BigDecimal typicalPrice = high.add(low).add(close)
                        .divide(THREE, SCALE, RoundingMode.HALF_UP);

                // 누적 계산
                sumTypicalPriceVolume = sumTypicalPriceVolume.add(typicalPrice.multiply(volume));
                sumVolume = sumVolume.add(volume);
                dataCount++;
            }

            if (sumVolume.compareTo(BigDecimal.ZERO) == 0 || currentPrice == null) {
                return createErrorResult(stockCode, "유효한 거래량 데이터 없음");
            }

            // VWAP = Σ(TP × V) / Σ(V)
            BigDecimal vwap = sumTypicalPriceVolume.divide(sumVolume, 0, RoundingMode.HALF_UP);

            // 괴리율 계산: (현재가 - VWAP) / VWAP × 100
            BigDecimal deviation = currentPrice.subtract(vwap)
                    .divide(vwap, SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .setScale(2, RoundingMode.HALF_UP);

            // 신호 결정
            VwapSignal signal = determineSignal(deviation);

            // 신뢰 가능 여부 (09:10 이후 또는 데이터 10개 이상)
            boolean isReliable = isMarketReliableTime() && dataCount >= MIN_RELIABLE_DATA_POINTS;

            // 해석 생성
            String interpretation = generateInterpretation(signal, deviation, isReliable);

            VwapResult result = VwapResult.builder()
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .vwap(vwap)
                    .currentPrice(currentPrice)
                    .deviation(deviation)
                    .signal(signal)
                    .interpretation(interpretation)
                    .dataPointCount(dataCount)
                    .isReliable(isReliable)
                    .calculatedAt(LocalDateTime.now())
                    .build();

            log.info("VWAP 계산 완료: {} - VWAP={}, 현재가={}, 괴리율={}%, 신호={}",
                    stockCode, vwap, currentPrice, deviation, signal);

            return result;

        } catch (Exception e) {
            log.error("VWAP 계산 실패 [{}]: {}", stockCode, e.getMessage(), e);
            return createErrorResult(stockCode, e.getMessage());
        }
    }

    /**
     * VWAP 신호 결정
     */
    private VwapSignal determineSignal(BigDecimal deviation) {
        if (deviation == null) {
            return VwapSignal.NEUTRAL;
        }

        BigDecimal absDeviation = deviation.abs();

        if (deviation.compareTo(STRONG_THRESHOLD) >= 0) {
            return VwapSignal.STRONG_BUY;
        } else if (deviation.compareTo(BigDecimal.ZERO) > 0 && absDeviation.compareTo(NEUTRAL_THRESHOLD) > 0) {
            return VwapSignal.BUY;
        } else if (deviation.negate().compareTo(STRONG_THRESHOLD) >= 0) {
            return VwapSignal.STRONG_SELL;
        } else if (deviation.compareTo(BigDecimal.ZERO) < 0 && absDeviation.compareTo(NEUTRAL_THRESHOLD) > 0) {
            return VwapSignal.SELL;
        } else {
            return VwapSignal.NEUTRAL;
        }
    }

    /**
     * 장 시작 후 10분 경과 여부 (09:10 이후)
     */
    private boolean isMarketReliableTime() {
        // KST 명시 — 코드베이스 관례(DateTimeUtil.kstNow 계열)와 통일. JVM 기본 TZ 가 KST 아니면 오판정.
        LocalTime now = LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        LocalTime reliableStart = LocalTime.of(9, 10);
        LocalTime marketClose = LocalTime.of(15, 30);

        return now.isAfter(reliableStart) && now.isBefore(marketClose);
    }

    /**
     * 해석 생성
     */
    private String generateInterpretation(VwapSignal signal, BigDecimal deviation, boolean isReliable) {
        StringBuilder sb = new StringBuilder();

        if (!isReliable) {
            sb.append("⚠️ 장 초반 데이터로 신뢰도 낮음. ");
        }

        switch (signal) {
            case STRONG_BUY:
                sb.append(String.format("현재가가 VWAP 대비 %.1f%% 상회. 기관 매집 추정, 추가 상승 기대.", deviation));
                break;
            case BUY:
                sb.append(String.format("현재가가 VWAP 상회(%.1f%%). 매수 세력 우위.", deviation));
                break;
            case NEUTRAL:
                sb.append("현재가가 VWAP 근처. 방향성 탐색 중.");
                break;
            case SELL:
                sb.append(String.format("현재가가 VWAP 하회(%.1f%%). 매도 세력 우위.", deviation));
                break;
            case STRONG_SELL:
                sb.append(String.format("현재가가 VWAP 대비 %.1f%% 하회. 기관 매도 추정, 하락 주의.", deviation));
                break;
        }

        return sb.toString();
    }

    /**
     * JsonNode에서 BigDecimal 값 추출
     */
    private BigDecimal getDecimalValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        try {
            String value = node.get(field).asText().replace(",", "");
            if (value.isEmpty()) return null;
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 에러 결과 생성
     */
    private VwapResult createErrorResult(String stockCode, String message) {
        return VwapResult.builder()
                .stockCode(stockCode)
                .interpretation("VWAP 계산 실패: " + message)
                .isReliable(false)
                .calculatedAt(LocalDateTime.now())
                .build();
    }
}
