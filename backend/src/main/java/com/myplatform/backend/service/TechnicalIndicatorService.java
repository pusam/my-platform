package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.dto.TechnicalIndicatorsDto.MfiStatus;
import com.myplatform.backend.dto.TechnicalIndicatorsDto.RsiStatus;
import com.myplatform.backend.dto.TechnicalIndicatorsDto.TechnicalSignal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 기술적 분석 지표 계산 서비스
 *
 * 모든 도메인(공매도, 퀀트, 수급 등)에서 공통으로 사용할 수 있는
 * 기술적 분석 지표를 계산합니다.
 *
 * 주요 기능:
 * - 이동평균선 (MA): 5일, 20일, 60일, 120일
 * - RSI (14일): 상대강도지수
 * - 시그널 포착: 골든크로스, 정배열, RSI 상태
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TechnicalIndicatorService {

    private final KoreaInvestmentService kisService;

    // ========== 기간 상수 ==========
    private static final int MA_5 = 5;
    private static final int MA_20 = 20;
    private static final int MA_60 = 60;
    private static final int MA_120 = 120;
    private static final int RSI_PERIOD = 14;

    // ========== RSI 기준값 ==========
    private static final BigDecimal RSI_OVERBOUGHT = new BigDecimal("70");
    private static final BigDecimal RSI_OVERSOLD = new BigDecimal("30");

    // ========== 볼린저 밴드 상수 ==========
    private static final int BB_PERIOD = 20;
    private static final BigDecimal BB_STD_MULTIPLIER = new BigDecimal("2.0");
    private static final BigDecimal BB_SQUEEZE_THRESHOLD = new BigDecimal("0.7");

    // ========== MFI 상수 ==========
    private static final int MFI_PERIOD = 14;
    private static final BigDecimal MFI_OVERBOUGHT = new BigDecimal("80");
    private static final BigDecimal MFI_OVERSOLD = new BigDecimal("20");

    // ========== 연산용 상수 ==========
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final int SCALE = 4;  // 소수점 정밀도

    /**
     * 가격 리스트로부터 기술적 지표 계산
     *
     * @param prices 가격 리스트 (최신 데이터가 index 0, 시간순 내림차순)
     *               예: [오늘가격, 어제가격, 그제가격, ...]
     * @return 계산된 기술적 지표 DTO
     */
    public TechnicalIndicatorsDto calculate(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            log.debug("가격 데이터가 없어 기술적 지표를 계산할 수 없습니다.");
            return createEmptyIndicators(0);
        }

        int dataCount = prices.size();
        BigDecimal currentPrice = prices.get(0);

        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("현재 가격이 유효하지 않습니다: {}", currentPrice);
            return createEmptyIndicators(dataCount);
        }

        TechnicalIndicatorsDto.TechnicalIndicatorsDtoBuilder builder = TechnicalIndicatorsDto.builder();
        builder.dataCount(dataCount);

        // ========== 1. 이동평균선 계산 ==========
        BigDecimal ma5 = calculateMA(prices, MA_5);
        BigDecimal ma20 = calculateMA(prices, MA_20);
        BigDecimal ma60 = calculateMA(prices, MA_60);
        BigDecimal ma120 = calculateMA(prices, MA_120);

        builder.ma5(ma5);
        builder.ma20(ma20);
        builder.ma60(ma60);
        builder.ma120(ma120);
        builder.hasEnoughDataFor120Ma(dataCount >= MA_120);

        // ========== 2. 이격도 계산 ==========
        builder.disparity5(calculateDisparity(currentPrice, ma5));
        builder.disparity20(calculateDisparity(currentPrice, ma20));
        builder.disparity60(calculateDisparity(currentPrice, ma60));

        // ========== 3. RSI 계산 ==========
        BigDecimal rsi14 = calculateRSI(prices, RSI_PERIOD);
        builder.rsi14(rsi14);
        builder.rsiStatus(determineRsiStatus(rsi14));

        // ========== 4. 이동평균 시그널 ==========
        builder.isAboveMa5(isAbove(currentPrice, ma5));
        builder.isAboveMa20(isAbove(currentPrice, ma20));
        builder.isAboveMa60(isAbove(currentPrice, ma60));

        // 골든크로스/데드크로스 판단 (5일선과 20일선 비교)
        Boolean isGoldenCross = null;
        Boolean isDeadCross = null;
        if (ma5 != null && ma20 != null && prices.size() >= 2) {
            // 이전일 5일선과 20일선 계산
            List<BigDecimal> yesterdayPrices = prices.subList(1, prices.size());
            BigDecimal prevMa5 = calculateMA(yesterdayPrices, MA_5);
            BigDecimal prevMa20 = calculateMA(yesterdayPrices, MA_20);

            if (prevMa5 != null && prevMa20 != null) {
                // 골든크로스: 이전에 5일선 < 20일선 → 현재 5일선 > 20일선
                boolean wasBelowYesterday = prevMa5.compareTo(prevMa20) < 0;
                boolean isAboveToday = ma5.compareTo(ma20) > 0;
                isGoldenCross = wasBelowYesterday && isAboveToday;

                // 데드크로스: 이전에 5일선 > 20일선 → 현재 5일선 < 20일선
                boolean wasAboveYesterday = prevMa5.compareTo(prevMa20) > 0;
                boolean isBelowToday = ma5.compareTo(ma20) < 0;
                isDeadCross = wasAboveYesterday && isBelowToday;
            }
        }
        builder.isGoldenCross(isGoldenCross);
        builder.isDeadCross(isDeadCross);

        // 정배열/역배열 판단
        Boolean isArrangedUp = null;
        Boolean isArrangedDown = null;
        if (ma5 != null && ma20 != null && ma60 != null) {
            // 정배열: 5일 > 20일 > 60일
            isArrangedUp = ma5.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0;
            // 역배열: 5일 < 20일 < 60일
            isArrangedDown = ma5.compareTo(ma20) < 0 && ma20.compareTo(ma60) < 0;
        }
        builder.isArrangedUp(isArrangedUp);
        builder.isArrangedDown(isArrangedDown);

        // ========== 5. 종합 신호 계산 ==========
        int buySignalStrength = calculateBuySignalStrength(
                currentPrice, ma5, ma20, ma60, rsi14,
                isGoldenCross, isArrangedUp);
        builder.buySignalStrength(buySignalStrength);

        TechnicalSignal overallSignal = determineOverallSignal(buySignalStrength);
        builder.overallSignal(overallSignal);
        builder.signalDescription(generateSignalDescription(
                isGoldenCross, isDeadCross, isArrangedUp, isArrangedDown, rsi14));

        return builder.build();
    }

    /**
     * 이동평균 계산
     *
     * @param prices 가격 리스트 (index 0이 최신)
     * @param period 이동평균 기간
     * @return 이동평균값 (데이터 부족 시 null)
     */
    public BigDecimal calculateMA(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) {
            return null;
        }

        BigDecimal sum = BigDecimal.ZERO;
        int validCount = 0;

        for (int i = 0; i < period && i < prices.size(); i++) {
            BigDecimal price = prices.get(i);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.add(price);
                validCount++;
            }
        }

        if (validCount == 0) {
            return null;
        }

        return sum.divide(BigDecimal.valueOf(validCount), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * RSI (Relative Strength Index) 계산
     *
     * RSI = 100 - (100 / (1 + RS))
     * RS = 평균 상승폭 / 평균 하락폭
     *
     * @param prices 가격 리스트 (index 0이 최신)
     * @param period RSI 기간 (일반적으로 14일)
     * @return RSI 값 (0~100), 계산 불가 시 null
     */
    public BigDecimal calculateRSI(List<BigDecimal> prices, int period) {
        // RSI 계산을 위해서는 period + 1 개의 데이터가 필요 (변화량 계산)
        if (prices == null || prices.size() < period + 1) {
            return null;
        }

        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int validChanges = 0;

        // 가격 변화량 계산 (최신 데이터부터)
        for (int i = 0; i < period && i < prices.size() - 1; i++) {
            BigDecimal current = prices.get(i);
            BigDecimal previous = prices.get(i + 1);

            if (current == null || previous == null ||
                previous.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal change = current.subtract(previous);
            validChanges++;

            if (change.compareTo(BigDecimal.ZERO) > 0) {
                totalGain = totalGain.add(change);
            } else {
                totalLoss = totalLoss.add(change.abs());
            }
        }

        if (validChanges == 0) {
            return null;
        }

        BigDecimal avgGain = totalGain.divide(BigDecimal.valueOf(validChanges), SCALE, RoundingMode.HALF_UP);
        BigDecimal avgLoss = totalLoss.divide(BigDecimal.valueOf(validChanges), SCALE, RoundingMode.HALF_UP);

        // 하락이 전혀 없는 경우 RSI = 100
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return HUNDRED;
        }

        // 상승이 전혀 없는 경우 RSI = 0
        if (avgGain.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // RS = 평균 상승 / 평균 하락
        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);

        // RSI = 100 - (100 / (1 + RS))
        BigDecimal rsi = HUNDRED.subtract(
                HUNDRED.divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP)
        );

        // 범위 보정 (0 ~ 100)
        if (rsi.compareTo(BigDecimal.ZERO) < 0) {
            rsi = BigDecimal.ZERO;
        } else if (rsi.compareTo(HUNDRED) > 0) {
            rsi = HUNDRED;
        }

        return rsi.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 이격도 계산
     *
     * 이격도 = (현재가 - 이동평균) / 이동평균 * 100
     *
     * @param currentPrice 현재가
     * @param ma           이동평균
     * @return 이격도 (%), null if 계산 불가
     */
    private BigDecimal calculateDisparity(BigDecimal currentPrice, BigDecimal ma) {
        if (currentPrice == null || ma == null || ma.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return currentPrice.subtract(ma)
                .divide(ma, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * RSI 상태 판단
     */
    private RsiStatus determineRsiStatus(BigDecimal rsi) {
        if (rsi == null) {
            return null;
        }

        if (rsi.compareTo(RSI_OVERBOUGHT) >= 0) {
            return RsiStatus.OVERBOUGHT;
        } else if (rsi.compareTo(RSI_OVERSOLD) <= 0) {
            return RsiStatus.OVERSOLD;
        } else {
            return RsiStatus.NEUTRAL;
        }
    }

    /**
     * 현재가가 이동평균 위에 있는지 판단
     */
    private Boolean isAbove(BigDecimal price, BigDecimal ma) {
        if (price == null || ma == null) {
            return null;
        }
        return price.compareTo(ma) > 0;
    }

    /**
     * 매수 신호 강도 계산 (0~100)
     */
    private int calculateBuySignalStrength(BigDecimal currentPrice,
                                            BigDecimal ma5, BigDecimal ma20, BigDecimal ma60,
                                            BigDecimal rsi,
                                            Boolean isGoldenCross, Boolean isArrangedUp) {
        int score = 50;  // 기본값 (중립)

        // 1. 이동평균 위치 (30점)
        if (currentPrice != null) {
            if (ma5 != null && currentPrice.compareTo(ma5) > 0) score += 5;
            if (ma20 != null && currentPrice.compareTo(ma20) > 0) score += 10;
            if (ma60 != null && currentPrice.compareTo(ma60) > 0) score += 15;

            if (ma5 != null && currentPrice.compareTo(ma5) < 0) score -= 5;
            if (ma20 != null && currentPrice.compareTo(ma20) < 0) score -= 10;
            if (ma60 != null && currentPrice.compareTo(ma60) < 0) score -= 15;
        }

        // 2. 골든크로스/데드크로스 (15점)
        if (Boolean.TRUE.equals(isGoldenCross)) {
            score += 15;
        }

        // 3. 정배열/역배열 (15점)
        if (Boolean.TRUE.equals(isArrangedUp)) {
            score += 15;
        }

        // 4. RSI (20점)
        if (rsi != null) {
            // RSI 30 이하: 매수 기회 (+20)
            if (rsi.compareTo(RSI_OVERSOLD) <= 0) {
                score += 20;
            }
            // RSI 40~60: 중립 (0)
            else if (rsi.compareTo(new BigDecimal("40")) >= 0 &&
                     rsi.compareTo(new BigDecimal("60")) <= 0) {
                // score += 0;
            }
            // RSI 70 이상: 과열 (-10)
            else if (rsi.compareTo(RSI_OVERBOUGHT) >= 0) {
                score -= 10;
            }
        }

        // 범위 보정 (0~100)
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 종합 기술적 신호 결정
     */
    private TechnicalSignal determineOverallSignal(int buySignalStrength) {
        if (buySignalStrength >= 80) {
            return TechnicalSignal.STRONG_BUY;
        } else if (buySignalStrength >= 60) {
            return TechnicalSignal.BUY;
        } else if (buySignalStrength >= 40) {
            return TechnicalSignal.NEUTRAL;
        } else if (buySignalStrength >= 20) {
            return TechnicalSignal.SELL;
        } else {
            return TechnicalSignal.STRONG_SELL;
        }
    }

    /**
     * 신호 설명 생성
     */
    private String generateSignalDescription(Boolean isGoldenCross, Boolean isDeadCross,
                                              Boolean isArrangedUp, Boolean isArrangedDown,
                                              BigDecimal rsi) {
        List<String> signals = new ArrayList<>();

        if (Boolean.TRUE.equals(isGoldenCross)) {
            signals.add("골든크로스 발생");
        }
        if (Boolean.TRUE.equals(isDeadCross)) {
            signals.add("데드크로스 발생");
        }
        if (Boolean.TRUE.equals(isArrangedUp)) {
            signals.add("이평선 정배열");
        }
        if (Boolean.TRUE.equals(isArrangedDown)) {
            signals.add("이평선 역배열");
        }
        if (rsi != null) {
            if (rsi.compareTo(RSI_OVERBOUGHT) >= 0) {
                signals.add("RSI 과열(" + rsi.setScale(1, RoundingMode.HALF_UP) + ")");
            } else if (rsi.compareTo(RSI_OVERSOLD) <= 0) {
                signals.add("RSI 침체(" + rsi.setScale(1, RoundingMode.HALF_UP) + ")");
            }
        }

        if (signals.isEmpty()) {
            return "특이 신호 없음";
        }

        return String.join(" / ", signals);
    }

    /**
     * 빈 지표 생성 (데이터 부족 시)
     */
    private TechnicalIndicatorsDto createEmptyIndicators(int dataCount) {
        return TechnicalIndicatorsDto.builder()
                .dataCount(dataCount)
                .hasEnoughDataFor120Ma(false)
                .overallSignal(TechnicalSignal.NEUTRAL)
                .signalDescription("데이터 부족")
                .build();
    }

    // ========== 추가 유틸리티 메서드 ==========

    /**
     * 특정 종목의 가격 데이터에서 기술적 지표 계산 (StockShortData 활용)
     *
     * @param closePrices 종가 리스트 (index 0이 최신, 날짜 내림차순)
     * @return 기술적 지표 DTO
     */
    public TechnicalIndicatorsDto calculateFromClosePrices(List<BigDecimal> closePrices) {
        return calculate(closePrices);
    }

    /**
     * 간단 지표 계산 (MA5, MA20, RSI만)
     * 경량 분석용
     */
    public TechnicalIndicatorsDto calculateSimple(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return createEmptyIndicators(0);
        }

        BigDecimal currentPrice = prices.get(0);
        BigDecimal ma5 = calculateMA(prices, MA_5);
        BigDecimal ma20 = calculateMA(prices, MA_20);
        BigDecimal rsi14 = calculateRSI(prices, RSI_PERIOD);

        return TechnicalIndicatorsDto.builder()
                .ma5(ma5)
                .ma20(ma20)
                .rsi14(rsi14)
                .rsiStatus(determineRsiStatus(rsi14))
                .isAboveMa5(isAbove(currentPrice, ma5))
                .isAboveMa20(isAbove(currentPrice, ma20))
                .dataCount(prices.size())
                .hasEnoughDataFor120Ma(false)
                .build();
    }

    // ========== 볼린저 밴드 (Bollinger Bands) ==========

    /**
     * 볼린저 밴드 계산
     *
     * @param prices 종가 리스트 (index 0이 최신)
     * @return 볼린저 밴드 결과 (upperBand, middleBand, lowerBand, bandWidth, isSqueeze, isBreakout)
     */
    public BollingerBandsResult calculateBollingerBands(List<BigDecimal> prices) {
        if (prices == null || prices.size() < BB_PERIOD) {
            return null;
        }

        BigDecimal currentPrice = prices.get(0);

        // 1. 중단선 = 20일 SMA
        BigDecimal middleBand = calculateMA(prices, BB_PERIOD);
        if (middleBand == null) {
            return null;
        }

        // 2. 표준편차 계산
        BigDecimal stdDev = calculateStandardDeviation(prices, BB_PERIOD);
        if (stdDev == null || stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // 3. 상단선 = 중단선 + (2 * 표준편차)
        BigDecimal upperBand = middleBand.add(stdDev.multiply(BB_STD_MULTIPLIER));

        // 4. 하단선 = 중단선 - (2 * 표준편차)
        BigDecimal lowerBand = middleBand.subtract(stdDev.multiply(BB_STD_MULTIPLIER));

        // 5. 밴드폭 = (상단 - 하단) / 중단 * 100
        BigDecimal bandWidth = upperBand.subtract(lowerBand)
                .divide(middleBand, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);

        // 6. 스퀴즈 판단: 최근 20일 밴드폭 평균 대비 0.7배 이하
        Boolean isSqueeze = false;
        List<BigDecimal> recentBandWidths = calculateRecentBandWidths(prices, BB_PERIOD);
        if (!recentBandWidths.isEmpty()) {
            BigDecimal avgBandWidth = recentBandWidths.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(recentBandWidths.size()), SCALE, RoundingMode.HALF_UP);

            BigDecimal squeezeThreshold = avgBandWidth.multiply(BB_SQUEEZE_THRESHOLD);
            isSqueeze = bandWidth.compareTo(squeezeThreshold) <= 0;
        }

        // 7. 돌파 판단: 종가 > 상단 밴드
        Boolean isBreakout = currentPrice.compareTo(upperBand) > 0;

        return new BollingerBandsResult(
                upperBand.setScale(0, RoundingMode.HALF_UP),
                middleBand.setScale(0, RoundingMode.HALF_UP),
                lowerBand.setScale(0, RoundingMode.HALF_UP),
                bandWidth,
                isSqueeze,
                isBreakout
        );
    }

    /**
     * 표준편차 계산
     */
    private BigDecimal calculateStandardDeviation(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) {
            return null;
        }

        // 평균 계산
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = 0; i < period; i++) {
            BigDecimal price = prices.get(i);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.add(price);
                count++;
            }
        }

        if (count == 0) {
            return null;
        }

        BigDecimal mean = sum.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);

        // 편차 제곱의 합
        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (int i = 0; i < period && i < prices.size(); i++) {
            BigDecimal price = prices.get(i);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal diff = price.subtract(mean);
                sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
            }
        }

        // 분산 = 편차 제곱 합 / 개수
        BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);

        // 표준편차 = sqrt(분산)
        return sqrt(variance);
    }

    /**
     * 최근 N일의 밴드폭 계산 (스퀴즈 판단용)
     */
    private List<BigDecimal> calculateRecentBandWidths(List<BigDecimal> prices, int days) {
        List<BigDecimal> bandWidths = new ArrayList<>();

        for (int i = 0; i < days && i + BB_PERIOD <= prices.size(); i++) {
            List<BigDecimal> subList = prices.subList(i, Math.min(i + BB_PERIOD + 10, prices.size()));
            if (subList.size() >= BB_PERIOD) {
                BigDecimal ma = calculateMA(subList, BB_PERIOD);
                BigDecimal stdDev = calculateStandardDeviation(subList, BB_PERIOD);

                if (ma != null && stdDev != null && ma.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal upper = ma.add(stdDev.multiply(BB_STD_MULTIPLIER));
                    BigDecimal lower = ma.subtract(stdDev.multiply(BB_STD_MULTIPLIER));
                    BigDecimal width = upper.subtract(lower)
                            .divide(ma, SCALE, RoundingMode.HALF_UP)
                            .multiply(HUNDRED);
                    bandWidths.add(width);
                }
            }
        }

        return bandWidths;
    }

    /**
     * BigDecimal 제곱근 계산
     */
    private BigDecimal sqrt(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        MathContext mc = new MathContext(SCALE + 2);
        BigDecimal x = value;
        BigDecimal two = new BigDecimal("2");

        // Newton-Raphson method
        for (int i = 0; i < 50; i++) {
            BigDecimal x2 = value.divide(x, mc);
            BigDecimal newX = x.add(x2).divide(two, mc);
            if (x.subtract(newX).abs().compareTo(new BigDecimal("0.0001")) < 0) {
                break;
            }
            x = newX;
        }

        return x.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ========== MFI (Money Flow Index) ==========

    /**
     * MFI (Money Flow Index) 계산
     *
     * MFI = 100 - (100 / (1 + Money Flow Ratio))
     * Money Flow Ratio = Positive Money Flow / Negative Money Flow
     * Typical Price = (High + Low + Close) / 3
     * Raw Money Flow = Typical Price * Volume
     *
     * @param ohlcvData OHLCV 데이터 리스트 (최신이 index 0)
     * @return MFI 결과 (mfiScore, mfiStatus)
     */
    public MfiResult calculateMFI(List<OhlcvData> ohlcvData) {
        if (ohlcvData == null || ohlcvData.size() < MFI_PERIOD + 1) {
            return null;
        }

        BigDecimal positiveFlow = BigDecimal.ZERO;
        BigDecimal negativeFlow = BigDecimal.ZERO;

        for (int i = 0; i < MFI_PERIOD && i < ohlcvData.size() - 1; i++) {
            OhlcvData current = ohlcvData.get(i);
            OhlcvData previous = ohlcvData.get(i + 1);

            if (!isValidOhlcv(current) || !isValidOhlcv(previous)) {
                continue;
            }

            // Typical Price = (High + Low + Close) / 3
            BigDecimal typicalPrice = current.getHigh()
                    .add(current.getLow())
                    .add(current.getClose())
                    .divide(THREE, SCALE, RoundingMode.HALF_UP);

            BigDecimal prevTypicalPrice = previous.getHigh()
                    .add(previous.getLow())
                    .add(previous.getClose())
                    .divide(THREE, SCALE, RoundingMode.HALF_UP);

            // Raw Money Flow = Typical Price * Volume
            BigDecimal rawMoneyFlow = typicalPrice.multiply(current.getVolume());

            // Typical Price 상승이면 Positive, 하락이면 Negative
            if (typicalPrice.compareTo(prevTypicalPrice) > 0) {
                positiveFlow = positiveFlow.add(rawMoneyFlow);
            } else if (typicalPrice.compareTo(prevTypicalPrice) < 0) {
                negativeFlow = negativeFlow.add(rawMoneyFlow);
            }
            // 같으면 무시
        }

        // MFI 계산
        BigDecimal mfiScore;
        if (negativeFlow.compareTo(BigDecimal.ZERO) == 0) {
            mfiScore = HUNDRED;  // 모두 상승
        } else if (positiveFlow.compareTo(BigDecimal.ZERO) == 0) {
            mfiScore = BigDecimal.ZERO;  // 모두 하락
        } else {
            BigDecimal moneyFlowRatio = positiveFlow.divide(negativeFlow, SCALE, RoundingMode.HALF_UP);
            mfiScore = HUNDRED.subtract(
                    HUNDRED.divide(BigDecimal.ONE.add(moneyFlowRatio), SCALE, RoundingMode.HALF_UP)
            );
        }

        // 범위 보정
        if (mfiScore.compareTo(BigDecimal.ZERO) < 0) {
            mfiScore = BigDecimal.ZERO;
        } else if (mfiScore.compareTo(HUNDRED) > 0) {
            mfiScore = HUNDRED;
        }

        mfiScore = mfiScore.setScale(2, RoundingMode.HALF_UP);
        MfiStatus mfiStatus = determineMfiStatus(mfiScore);

        return new MfiResult(mfiScore, mfiStatus);
    }

    /**
     * OHLCV 데이터 유효성 검사
     */
    private boolean isValidOhlcv(OhlcvData data) {
        return data != null
                && data.getHigh() != null && data.getHigh().compareTo(BigDecimal.ZERO) > 0
                && data.getLow() != null && data.getLow().compareTo(BigDecimal.ZERO) > 0
                && data.getClose() != null && data.getClose().compareTo(BigDecimal.ZERO) > 0
                && data.getVolume() != null && data.getVolume().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * MFI 상태 판단
     */
    private MfiStatus determineMfiStatus(BigDecimal mfi) {
        if (mfi == null) {
            return null;
        }

        if (mfi.compareTo(MFI_OVERBOUGHT) >= 0) {
            return MfiStatus.OVERBOUGHT;
        } else if (mfi.compareTo(MFI_OVERSOLD) <= 0) {
            return MfiStatus.OVERSOLD;
        } else {
            return MfiStatus.NEUTRAL;
        }
    }

    // ========== 결과 데이터 클래스 ==========

    /**
     * 볼린저 밴드 결과
     */
    @Data
    @AllArgsConstructor
    public static class BollingerBandsResult {
        private BigDecimal upperBand;
        private BigDecimal middleBand;
        private BigDecimal lowerBand;
        private BigDecimal bandWidth;
        private Boolean isSqueeze;
        private Boolean isBreakout;
    }

    /**
     * MFI 결과
     */
    @Data
    @AllArgsConstructor
    public static class MfiResult {
        private BigDecimal mfiScore;
        private MfiStatus mfiStatus;
    }

    /**
     * OHLCV 데이터 (고가, 저가, 종가, 거래량)
     */
    @Data
    @AllArgsConstructor
    public static class OhlcvData {
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
    }

    // ========== RSI 다이버전스 탐지 ==========

    /**
     * RSI 다이버전스 탐지
     *
     * 하락 다이버전스 (Bearish):
     * - 주가는 전고점보다 높은데 (High > Prev_High)
     * - RSI는 전고점보다 낮은 경우 (RSI < Prev_RSI)
     * → 매도 신호 (상승 추세 약화)
     *
     * 상승 다이버전스 (Bullish):
     * - 주가는 전저점보다 낮은데 (Low < Prev_Low)
     * - RSI는 전저점보다 높은 경우 (RSI > Prev_RSI)
     * → 매수 신호 (하락 추세 약화)
     *
     * @param prices 가격 리스트 (index 0이 최신, 최소 60개 권장)
     * @param lookbackPeriod 비교할 기간 (20~60봉)
     * @return 다이버전스 결과
     */
    public DivergenceResult detectRsiDivergence(List<BigDecimal> prices, int lookbackPeriod) {
        if (prices == null || prices.size() < lookbackPeriod + RSI_PERIOD) {
            log.debug("다이버전스 탐지 불가: 데이터 부족 (필요: {}, 보유: {})",
                    lookbackPeriod + RSI_PERIOD, prices != null ? prices.size() : 0);
            return createNoDivergence("데이터 부족");
        }

        // 1. 전체 기간의 RSI 계산
        List<BigDecimal> rsiValues = new ArrayList<>();
        for (int i = 0; i <= lookbackPeriod; i++) {
            List<BigDecimal> subPrices = prices.subList(i, Math.min(i + RSI_PERIOD + 5, prices.size()));
            BigDecimal rsi = calculateRSI(subPrices, RSI_PERIOD);
            rsiValues.add(rsi);
        }

        BigDecimal currentPrice = prices.get(0);
        BigDecimal currentRsi = rsiValues.get(0);

        if (currentRsi == null) {
            return createNoDivergence("RSI 계산 불가");
        }

        // 2. 고점/저점 찾기 (최소 5봉 이상 떨어진)
        int minDistance = 5;

        // 하락 다이버전스 탐지: 가격 고점 찾기
        DivergenceResult bearishResult = detectBearishDivergence(
                prices, rsiValues, currentPrice, currentRsi, lookbackPeriod, minDistance);

        // 상승 다이버전스 탐지: 가격 저점 찾기
        DivergenceResult bullishResult = detectBullishDivergence(
                prices, rsiValues, currentPrice, currentRsi, lookbackPeriod, minDistance);

        // 더 강한 신호 반환
        if (bearishResult.isValid() && bullishResult.isValid()) {
            return bearishResult.getStrength() >= bullishResult.getStrength() ? bearishResult : bullishResult;
        } else if (bearishResult.isValid()) {
            return bearishResult;
        } else if (bullishResult.isValid()) {
            return bullishResult;
        }

        return createNoDivergence("다이버전스 미감지");
    }

    /**
     * 종목코드로 RSI 다이버전스 탐지
     * 한투 API로 일봉 데이터를 조회하여 다이버전스 분석
     *
     * @param stockCode 종목코드
     * @param lookbackPeriod 분석 기간 (기본 40일)
     * @return 다이버전스 결과
     */
    public DivergenceResult detectRsiDivergenceByStockCode(String stockCode, int lookbackPeriod) {
        log.info("종목별 RSI 다이버전스 탐지: {}, lookback={}", stockCode, lookbackPeriod);

        if (!kisService.isConfigured()) {
            log.warn("RSI 다이버전스 탐지 불가 - 한투 API 미설정");
            return createNoDivergence("API 미설정");
        }

        try {
            // 일봉 데이터 조회 (lookback + RSI 기간 + 여유분)
            int requiredDays = lookbackPeriod + RSI_PERIOD + 20;
            JsonNode response = kisService.getStockDailyChart(stockCode, requiredDays);

            if (response == null) {
                return createNoDivergence("일봉 데이터 조회 실패");
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                String msg = response.has("msg1") ? response.get("msg1").asText() : "Unknown error";
                log.warn("RSI 다이버전스 일봉 API 에러 [{}]: {}", stockCode, msg);
                return createNoDivergence(msg);
            }

            JsonNode output2 = response.get("output2");
            if (output2 == null || !output2.isArray() || output2.isEmpty()) {
                return createNoDivergence("일봉 데이터 없음");
            }

            // 가격 데이터 추출 (종가 기준)
            List<BigDecimal> prices = new ArrayList<>();
            for (JsonNode candle : output2) {
                String closeStr = candle.has("stck_clpr") ? candle.get("stck_clpr").asText() : null;
                if (closeStr != null && !closeStr.isEmpty()) {
                    try {
                        prices.add(new BigDecimal(closeStr.replace(",", "")));
                    } catch (NumberFormatException e) {
                        // 무시
                    }
                }
            }

            if (prices.size() < lookbackPeriod + RSI_PERIOD) {
                return createNoDivergence("데이터 부족 (" + prices.size() + "일)");
            }

            log.info("종목 {} 일봉 {}개 조회 완료, 다이버전스 분석 시작", stockCode, prices.size());

            // 기존 다이버전스 탐지 로직 호출
            return detectRsiDivergence(prices, lookbackPeriod);

        } catch (Exception e) {
            log.error("종목별 RSI 다이버전스 탐지 실패 [{}]: {}", stockCode, e.getMessage(), e);
            return createNoDivergence("분석 실패: " + e.getMessage());
        }
    }

    /**
     * 하락 다이버전스 탐지 (매도 신호)
     * 주가 신고가 + RSI 전고점 하회
     */
    private DivergenceResult detectBearishDivergence(List<BigDecimal> prices, List<BigDecimal> rsiValues,
                                                       BigDecimal currentPrice, BigDecimal currentRsi,
                                                       int lookbackPeriod, int minDistance) {
        // 현재가 근처가 고점인지 확인 (최근 5봉 중 최고)
        boolean isNearHigh = true;
        for (int i = 1; i < Math.min(5, prices.size()); i++) {
            if (prices.get(i) != null && prices.get(i).compareTo(currentPrice) > 0) {
                isNearHigh = false;
                break;
            }
        }

        if (!isNearHigh) {
            return createNoDivergence("현재가가 고점 아님");
        }

        // 이전 고점 찾기
        BigDecimal prevPeakPrice = null;
        BigDecimal prevPeakRsi = null;
        int peakIndex = -1;

        for (int i = minDistance; i < Math.min(lookbackPeriod, prices.size()); i++) {
            BigDecimal price = prices.get(i);
            BigDecimal rsi = i < rsiValues.size() ? rsiValues.get(i) : null;

            if (price == null || rsi == null) continue;

            // 로컬 고점 확인 (앞뒤 2봉보다 높은지)
            boolean isLocalHigh = true;
            for (int j = -2; j <= 2; j++) {
                if (j == 0) continue;
                int checkIdx = i + j;
                if (checkIdx >= 0 && checkIdx < prices.size() && prices.get(checkIdx) != null) {
                    if (prices.get(checkIdx).compareTo(price) > 0) {
                        isLocalHigh = false;
                        break;
                    }
                }
            }

            if (isLocalHigh && (prevPeakPrice == null || price.compareTo(prevPeakPrice) > 0)) {
                prevPeakPrice = price;
                prevPeakRsi = rsi;
                peakIndex = i;
            }
        }

        if (prevPeakPrice == null || prevPeakRsi == null) {
            return createNoDivergence("이전 고점 없음");
        }

        // 하락 다이버전스 조건: 현재 가격 > 이전 고점 가격, 현재 RSI < 이전 고점 RSI
        boolean priceHigher = currentPrice.compareTo(prevPeakPrice) > 0;
        boolean rsiLower = currentRsi.compareTo(prevPeakRsi) < 0;

        if (priceHigher && rsiLower) {
            // 신호 강도 계산 (RSI 차이가 클수록 강함)
            BigDecimal rsiDiff = prevPeakRsi.subtract(currentRsi);
            int strength = calculateDivergenceStrength(rsiDiff, true);

            String interpretation = String.format(
                    "🔻 하락 다이버전스 감지! 주가 신고가(%.0f > %.0f) but RSI 하락(%.1f < %.1f). " +
                    "%d봉 전 고점 대비. 추세 반전(하락) 주의!",
                    currentPrice, prevPeakPrice, currentRsi, prevPeakRsi, peakIndex);

            return DivergenceResult.builder()
                    .type(DivergenceType.BEARISH_REGULAR)
                    .signal(strength >= 4 ? DivergenceSignal.STRONG_SELL : DivergenceSignal.SELL)
                    .currentPrice(currentPrice)
                    .currentRsi(currentRsi)
                    .prevPeakPrice(prevPeakPrice)
                    .prevPeakRsi(prevPeakRsi)
                    .peakIndex(peakIndex)
                    .strength(strength)
                    .interpretation(interpretation)
                    .isValid(true)
                    .detectedAt(java.time.LocalDateTime.now())
                    .build();
        }

        return createNoDivergence("하락 다이버전스 조건 미충족");
    }

    /**
     * 상승 다이버전스 탐지 (매수 신호)
     * 주가 신저가 + RSI 전저점 상회
     */
    private DivergenceResult detectBullishDivergence(List<BigDecimal> prices, List<BigDecimal> rsiValues,
                                                       BigDecimal currentPrice, BigDecimal currentRsi,
                                                       int lookbackPeriod, int minDistance) {
        // 현재가 근처가 저점인지 확인 (최근 5봉 중 최저)
        boolean isNearLow = true;
        for (int i = 1; i < Math.min(5, prices.size()); i++) {
            if (prices.get(i) != null && prices.get(i).compareTo(currentPrice) < 0) {
                isNearLow = false;
                break;
            }
        }

        if (!isNearLow) {
            return createNoDivergence("현재가가 저점 아님");
        }

        // 이전 저점 찾기
        BigDecimal prevTroughPrice = null;
        BigDecimal prevTroughRsi = null;
        int troughIndex = -1;

        for (int i = minDistance; i < Math.min(lookbackPeriod, prices.size()); i++) {
            BigDecimal price = prices.get(i);
            BigDecimal rsi = i < rsiValues.size() ? rsiValues.get(i) : null;

            if (price == null || rsi == null) continue;

            // 로컬 저점 확인 (앞뒤 2봉보다 낮은지)
            boolean isLocalLow = true;
            for (int j = -2; j <= 2; j++) {
                if (j == 0) continue;
                int checkIdx = i + j;
                if (checkIdx >= 0 && checkIdx < prices.size() && prices.get(checkIdx) != null) {
                    if (prices.get(checkIdx).compareTo(price) < 0) {
                        isLocalLow = false;
                        break;
                    }
                }
            }

            if (isLocalLow && (prevTroughPrice == null || price.compareTo(prevTroughPrice) < 0)) {
                prevTroughPrice = price;
                prevTroughRsi = rsi;
                troughIndex = i;
            }
        }

        if (prevTroughPrice == null || prevTroughRsi == null) {
            return createNoDivergence("이전 저점 없음");
        }

        // 상승 다이버전스 조건: 현재 가격 < 이전 저점 가격, 현재 RSI > 이전 저점 RSI
        boolean priceLower = currentPrice.compareTo(prevTroughPrice) < 0;
        boolean rsiHigher = currentRsi.compareTo(prevTroughRsi) > 0;

        if (priceLower && rsiHigher) {
            // 신호 강도 계산
            BigDecimal rsiDiff = currentRsi.subtract(prevTroughRsi);
            int strength = calculateDivergenceStrength(rsiDiff, false);

            String interpretation = String.format(
                    "🔺 상승 다이버전스 감지! 주가 신저가(%.0f < %.0f) but RSI 상승(%.1f > %.1f). " +
                    "%d봉 전 저점 대비. 추세 반전(상승) 기대!",
                    currentPrice, prevTroughPrice, currentRsi, prevTroughRsi, troughIndex);

            return DivergenceResult.builder()
                    .type(DivergenceType.BULLISH_REGULAR)
                    .signal(strength >= 4 ? DivergenceSignal.STRONG_BUY : DivergenceSignal.BUY)
                    .currentPrice(currentPrice)
                    .currentRsi(currentRsi)
                    .prevPeakPrice(prevTroughPrice)
                    .prevPeakRsi(prevTroughRsi)
                    .peakIndex(troughIndex)
                    .strength(strength)
                    .interpretation(interpretation)
                    .isValid(true)
                    .detectedAt(java.time.LocalDateTime.now())
                    .build();
        }

        return createNoDivergence("상승 다이버전스 조건 미충족");
    }

    /**
     * 다이버전스 신호 강도 계산 (1~5)
     */
    private int calculateDivergenceStrength(BigDecimal rsiDiff, boolean isBearish) {
        BigDecimal absDiff = rsiDiff.abs();

        // RSI 차이에 따른 강도
        // 5점 이하: 약한 신호, 10점 이상: 강한 신호
        if (absDiff.compareTo(new BigDecimal("15")) >= 0) {
            return 5;
        } else if (absDiff.compareTo(new BigDecimal("10")) >= 0) {
            return 4;
        } else if (absDiff.compareTo(new BigDecimal("7")) >= 0) {
            return 3;
        } else if (absDiff.compareTo(new BigDecimal("5")) >= 0) {
            return 2;
        } else {
            return 1;
        }
    }

    /**
     * 다이버전스 미감지 결과 생성
     */
    private DivergenceResult createNoDivergence(String reason) {
        return DivergenceResult.builder()
                .type(DivergenceType.NONE)
                .signal(DivergenceSignal.NEUTRAL)
                .interpretation(reason)
                .isValid(false)
                .detectedAt(java.time.LocalDateTime.now())
                .build();
    }

    // ========== 다이버전스 결과 클래스 ==========

    /**
     * RSI 다이버전스 결과
     */
    @Data
    @AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Builder
    public static class DivergenceResult {
        private DivergenceType type;
        private DivergenceSignal signal;
        private BigDecimal currentPrice;
        private BigDecimal currentRsi;
        private BigDecimal prevPeakPrice;
        private BigDecimal prevPeakRsi;
        private int peakIndex;
        private int strength;
        private String interpretation;
        private boolean isValid;
        private java.time.LocalDateTime detectedAt;
    }

    /**
     * 다이버전스 타입
     */
    public enum DivergenceType {
        BEARISH_REGULAR("하락 다이버전스 (일반)", "주가 신고가, RSI 전고점 하회"),
        BEARISH_HIDDEN("하락 다이버전스 (히든)", "주가 저고점, RSI 고점 갱신"),
        BULLISH_REGULAR("상승 다이버전스 (일반)", "주가 신저가, RSI 전저점 상회"),
        BULLISH_HIDDEN("상승 다이버전스 (히든)", "주가 고저점, RSI 저점 갱신"),
        NONE("다이버전스 없음", "특이 신호 없음");

        private final String label;
        private final String description;

        DivergenceType(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    /**
     * 다이버전스 신호
     */
    public enum DivergenceSignal {
        STRONG_BUY("강한 매수 신호", 5),
        BUY("매수 신호", 3),
        NEUTRAL("중립", 0),
        SELL("매도 신호", -3),
        STRONG_SELL("강한 매도 신호", -5);

        private final String label;
        private final int weight;

        DivergenceSignal(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        public String getLabel() { return label; }
        public int getWeight() { return weight; }
    }
}
