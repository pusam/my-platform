package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.dto.StockDiagnosisDto.*;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockShortData;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockShortDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 종목 상세 진단 서비스
 *
 * 마법의 공식 등에서 선택한 종목의 '더블 체크' 분석 수행:
 * 1. 재무 건전성: 영업이익 vs 당기순이익 비교 (일회성 이익 경고)
 * 2. 수급 현황: 최근 5일 외국인/기관 순매수 체크
 * 3. 기술적 분석: 이평선 정배열 여부, RSI 상태 확인
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockAnalysisService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final InvestorDailyTradeRepository investorDailyTradeRepository;
    private final StockShortDataRepository stockShortDataRepository;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final KoreaInvestmentService koreaInvestmentService;

    // 상수
    private static final int SUPPLY_DEMAND_DAYS = 5;
    private static final int PRICE_DATA_DAYS = 120;  // 기술적 분석용 가격 데이터 일수
    private static final BigDecimal ONE_TIME_GAIN_THRESHOLD = new BigDecimal("50");  // 50% 이상 차이시 경고

    /**
     * 종목 상세 진단 수행
     *
     * @param stockCode 종목코드
     * @return 진단 결과 DTO
     */
    public StockDiagnosisDto diagnose(String stockCode) {
        log.info("종목 상세 진단 시작: {}", stockCode);

        // 기본 정보 조회
        Optional<StockFinancialData> financialDataOpt = stockFinancialDataRepository
                .findTopByStockCodeOrderByReportDateDesc(stockCode);

        if (financialDataOpt.isEmpty()) {
            log.warn("종목 {} 의 재무 데이터가 없습니다.", stockCode);
            return createEmptyDiagnosis(stockCode);
        }

        StockFinancialData financialData = financialDataOpt.get();

        // 1. 재무 건전성 분석
        FinancialHealthDto financialHealth = analyzeFinancialHealth(financialData);

        // 2. 수급 현황 분석
        SupplyDemandDto supplyDemand = analyzeSupplyDemand(stockCode);

        // 3. 기술적 분석
        TechnicalAnalysisDto technicalAnalysis = analyzeTechnical(stockCode);

        // 4. 종합 의견 도출
        List<String> warnings = new ArrayList<>();
        List<String> positives = new ArrayList<>();

        // 경고 수집
        if (financialHealth.isHasOneTimeGainWarning()) {
            warnings.add("일회성 이익 의심: " + financialHealth.getOneTimeGainReason());
        }
        if (supplyDemand.isBothSelling()) {
            warnings.add("외국인+기관 동반 매도 중");
        }
        if (technicalAnalysis.isRsiOverbought()) {
            warnings.add("RSI 과열 구간 (단기 조정 가능성)");
        }
        if (Boolean.TRUE.equals(technicalAnalysis.getIsDeadCross())) {
            warnings.add("데드크로스 발생");
        }
        if (Boolean.TRUE.equals(technicalAnalysis.getIsArrangedUp()) == false &&
            technicalAnalysis.getIsArrangedUp() != null) {
            // 역배열 체크는 별도로
        }

        // 긍정 요소 수집
        if (financialHealth.getOperatingMargin() != null &&
            financialHealth.getOperatingMargin().compareTo(new BigDecimal("10")) > 0) {
            positives.add("영업이익률 " + financialHealth.getOperatingMargin() + "% (양호)");
        }
        if (supplyDemand.isBothBuying()) {
            positives.add("외국인+기관 동반 매수 중");
        }
        if (Boolean.TRUE.equals(technicalAnalysis.getIsArrangedUp())) {
            positives.add("이평선 정배열 (상승 추세)");
        }
        if (Boolean.TRUE.equals(technicalAnalysis.getIsGoldenCross())) {
            positives.add("골든크로스 발생");
        }
        if (technicalAnalysis.isRsiOversold()) {
            positives.add("RSI 침체 구간 (반등 기회)");
        }

        // 종합 점수 계산
        int overallScore = calculateOverallScore(financialHealth, supplyDemand, technicalAnalysis);
        VerdictLevel verdictLevel = determineVerdictLevel(overallScore, warnings.size());
        String verdict = verdictLevel.getLabel();

        return StockDiagnosisDto.builder()
                .stockCode(stockCode)
                .stockName(financialData.getStockName())
                .market(financialData.getMarket())
                .currentPrice(financialData.getCurrentPrice())
                .diagnosisDate(LocalDate.now())
                .financialHealth(financialHealth)
                .supplyDemand(supplyDemand)
                .technicalAnalysis(technicalAnalysis)
                .verdict(verdict)
                .verdictLevel(verdictLevel)
                .overallScore(overallScore)
                .warnings(warnings)
                .positives(positives)
                .build();
    }

    /**
     * 1. 재무 건전성 분석
     * - 영업이익 vs 당기순이익 비교
     * - 차이가 클 경우 일회성 이익 경고
     */
    private FinancialHealthDto analyzeFinancialHealth(StockFinancialData data) {
        BigDecimal operatingProfit = data.getOperatingProfit();
        BigDecimal netIncome = data.getNetIncome();
        BigDecimal operatingMargin = data.getOperatingMargin();
        BigDecimal netMargin = data.getNetMargin();
        BigDecimal roe = data.getRoe();
        BigDecimal debtRatio = data.getDebtRatio();

        // 일회성 이익 분석
        boolean hasOneTimeGainWarning = false;
        String oneTimeGainReason = null;
        BigDecimal profitGap = null;
        BigDecimal profitGapRatio = null;

        if (operatingProfit != null && netIncome != null &&
            operatingProfit.compareTo(BigDecimal.ZERO) != 0) {

            profitGap = netIncome.subtract(operatingProfit);
            profitGapRatio = profitGap
                    .divide(operatingProfit.abs(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            // 순이익이 영업이익보다 50% 이상 많으면 일회성 이익 의심
            if (profitGapRatio.compareTo(ONE_TIME_GAIN_THRESHOLD) > 0) {
                hasOneTimeGainWarning = true;
                oneTimeGainReason = String.format("순이익이 영업이익 대비 %.1f%% 높음 (자산매각, 환차익 등 확인 필요)",
                        profitGapRatio.doubleValue());
            }
            // 영업이익은 흑자인데 순이익이 적자면 영업외비용 경고
            else if (operatingProfit.compareTo(BigDecimal.ZERO) > 0 &&
                     netIncome.compareTo(BigDecimal.ZERO) < 0) {
                hasOneTimeGainWarning = true;
                oneTimeGainReason = "영업이익 흑자, 순이익 적자 (영업외비용 확인 필요)";
            }
        }

        // 점수 계산
        int score = calculateFinancialScore(operatingMargin, roe, debtRatio, hasOneTimeGainWarning);
        String assessment = score >= 70 ? "양호" : score >= 40 ? "보통" : "주의";

        return FinancialHealthDto.builder()
                .operatingProfit(operatingProfit)
                .netIncome(netIncome)
                .profitGap(profitGap)
                .profitGapRatio(profitGapRatio)
                .hasOneTimeGainWarning(hasOneTimeGainWarning)
                .oneTimeGainReason(oneTimeGainReason)
                .operatingMargin(operatingMargin)
                .netMargin(netMargin)
                .roe(roe)
                .debtRatio(debtRatio)
                .score(score)
                .assessment(assessment)
                .build();
    }

    /**
     * 2. 수급 현황 분석
     * - 최근 5일 외국인/기관 순매수 합계
     */
    private SupplyDemandDto analyzeSupplyDemand(String stockCode) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(SUPPLY_DEMAND_DAYS + 5);  // 여유있게 조회

        List<InvestorDailyTrade> trades = investorDailyTradeRepository
                .findByStockCodeAndDateRange(stockCode, startDate, endDate);

        // 외국인 수급
        BigDecimal foreignNet5Days = BigDecimal.ZERO;
        int foreignBuyDays = 0;

        // 기관 수급
        BigDecimal institutionNet5Days = BigDecimal.ZERO;
        int institutionBuyDays = 0;

        // 일자별 집계
        List<LocalDate> recentDates = trades.stream()
                .map(InvestorDailyTrade::getTradeDate)
                .distinct()
                .sorted((a, b) -> b.compareTo(a))  // 최신순
                .limit(SUPPLY_DEMAND_DAYS)
                .collect(Collectors.toList());

        for (LocalDate date : recentDates) {
            // 외국인
            BigDecimal foreignDayNet = trades.stream()
                    .filter(t -> t.getTradeDate().equals(date))
                    .filter(t -> "FOREIGN".equals(t.getInvestorType()))
                    .map(t -> {
                        if ("BUY".equals(t.getTradeType())) {
                            return t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO;
                        } else {
                            return t.getNetBuyAmount() != null ? t.getNetBuyAmount().negate() : BigDecimal.ZERO;
                        }
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            foreignNet5Days = foreignNet5Days.add(foreignDayNet);
            if (foreignDayNet.compareTo(BigDecimal.ZERO) > 0) {
                foreignBuyDays++;
            }

            // 기관
            BigDecimal institutionDayNet = trades.stream()
                    .filter(t -> t.getTradeDate().equals(date))
                    .filter(t -> "INSTITUTION".equals(t.getInvestorType()))
                    .map(t -> {
                        if ("BUY".equals(t.getTradeType())) {
                            return t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO;
                        } else {
                            return t.getNetBuyAmount() != null ? t.getNetBuyAmount().negate() : BigDecimal.ZERO;
                        }
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            institutionNet5Days = institutionNet5Days.add(institutionDayNet);
            if (institutionDayNet.compareTo(BigDecimal.ZERO) > 0) {
                institutionBuyDays++;
            }
        }

        boolean isForeignBuying = foreignNet5Days.compareTo(BigDecimal.ZERO) > 0;
        boolean isInstitutionBuying = institutionNet5Days.compareTo(BigDecimal.ZERO) > 0;
        boolean isBothBuying = isForeignBuying && isInstitutionBuying;
        boolean isBothSelling = !isForeignBuying && !isInstitutionBuying &&
                (foreignNet5Days.compareTo(BigDecimal.ZERO) < 0 || institutionNet5Days.compareTo(BigDecimal.ZERO) < 0);

        // 점수 계산
        int score = calculateSupplyDemandScore(isForeignBuying, isInstitutionBuying, foreignBuyDays, institutionBuyDays);
        String assessment = isBothBuying ? "매수 우위" : isBothSelling ? "매도 우위" : "혼조";

        return SupplyDemandDto.builder()
                .foreignNet5Days(foreignNet5Days)
                .foreignBuyDays(foreignBuyDays)
                .isForeignBuying(isForeignBuying)
                .institutionNet5Days(institutionNet5Days)
                .institutionBuyDays(institutionBuyDays)
                .isInstitutionBuying(isInstitutionBuying)
                .isBothBuying(isBothBuying)
                .isBothSelling(isBothSelling)
                .score(score)
                .assessment(assessment)
                .build();
    }

    /**
     * 3. 기술적 분석
     * - TechnicalIndicatorService 활용
     * - 공매도 데이터(StockShortData)가 없으면 KIS API로 fallback
     * - 볼린저 밴드 & MFI 지표 포함
     */
    private TechnicalAnalysisDto analyzeTechnical(String stockCode) {
        List<BigDecimal> closePrices;
        List<KoreaInvestmentService.OhlcvData> ohlcvData = null;
        boolean useKisApi = false;

        // 1차: 공매도 데이터에서 가격 조회
        List<StockShortData> priceData = stockShortDataRepository
                .findByStockCodeOrderByTradeDateDesc(stockCode, PageRequest.of(0, PRICE_DATA_DAYS));

        if (!priceData.isEmpty()) {
            // 공매도 데이터에서 종가 추출
            closePrices = priceData.stream()
                    .map(StockShortData::getClosePrice)
                    .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());
            log.debug("종목 {} 공매도 데이터에서 {} 건의 종가 조회", stockCode, closePrices.size());
        } else {
            // 2차: KIS API로 일봉 데이터 조회 (fallback)
            log.debug("종목 {} 공매도 데이터 없음, KIS API로 fallback", stockCode);
            useKisApi = true;

            // OHLCV 데이터 가져오기 (MFI 계산용)
            ohlcvData = koreaInvestmentService.getDailyOhlcv(stockCode, PRICE_DATA_DAYS);

            if (ohlcvData.isEmpty()) {
                log.warn("종목 {} 의 가격 데이터를 조회할 수 없습니다.", stockCode);
                return TechnicalAnalysisDto.builder()
                        .score(50)
                        .assessment("데이터 부족")
                        .build();
            }

            // OHLCV에서 종가만 추출
            closePrices = ohlcvData.stream()
                    .map(KoreaInvestmentService.OhlcvData::getClose)
                    .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            log.debug("종목 {} KIS API에서 {} 건의 OHLCV 조회", stockCode, ohlcvData.size());
        }

        // 최소 데이터 검증
        if (closePrices.size() < 20) {
            log.warn("종목 {} 의 가격 데이터가 부족합니다 ({} 건).", stockCode, closePrices.size());
            return TechnicalAnalysisDto.builder()
                    .score(50)
                    .assessment("데이터 부족")
                    .build();
        }

        // 기술적 지표 계산
        TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(closePrices);

        // RSI 상태 변환
        String rsiStatus = indicators.getRsiStatus() != null ? indicators.getRsiStatus().getLabel() : "알 수 없음";
        boolean isRsiOversold = indicators.getRsi14() != null &&
                indicators.getRsi14().compareTo(new BigDecimal("30")) <= 0;
        boolean isRsiOverbought = indicators.getRsi14() != null &&
                indicators.getRsi14().compareTo(new BigDecimal("70")) >= 0;

        // 볼린저 밴드 계산
        TechnicalIndicatorService.BollingerBandsResult bbResult =
                technicalIndicatorService.calculateBollingerBands(closePrices);

        // MFI 계산 (OHLCV 데이터가 있는 경우만)
        TechnicalIndicatorService.MfiResult mfiResult = null;
        if (ohlcvData != null && !ohlcvData.isEmpty()) {
            // KIS API OhlcvData → TechnicalIndicatorService OhlcvData 변환
            List<TechnicalIndicatorService.OhlcvData> convertedOhlcv = ohlcvData.stream()
                    .map(d -> new TechnicalIndicatorService.OhlcvData(
                            d.getOpen(), d.getHigh(), d.getLow(), d.getClose(), d.getVolume()))
                    .collect(Collectors.toList());
            mfiResult = technicalIndicatorService.calculateMFI(convertedOhlcv);
        } else if (!useKisApi) {
            // 공매도 데이터를 쓴 경우, MFI 계산을 위해 KIS API에서 OHLCV 추가 조회
            ohlcvData = koreaInvestmentService.getDailyOhlcv(stockCode, PRICE_DATA_DAYS);
            if (ohlcvData != null && !ohlcvData.isEmpty()) {
                List<TechnicalIndicatorService.OhlcvData> convertedOhlcv = ohlcvData.stream()
                        .map(d -> new TechnicalIndicatorService.OhlcvData(
                                d.getOpen(), d.getHigh(), d.getLow(), d.getClose(), d.getVolume()))
                        .collect(Collectors.toList());
                mfiResult = technicalIndicatorService.calculateMFI(convertedOhlcv);
            }
        }

        // 종합 신호 변환
        String overallSignal = indicators.getOverallSignal() != null ?
                indicators.getOverallSignal().getLabel() : "중립";

        // 점수 계산 (볼린저 밴드 & MFI 반영)
        int score = calculateTechnicalScore(indicators, bbResult, mfiResult);
        String assessment = score >= 60 ? "매수 신호" : score <= 40 ? "매도 신호" : "중립";

        // 신호 설명 업데이트
        String signalDesc = generateEnhancedSignalDescription(indicators, bbResult, mfiResult);

        return TechnicalAnalysisDto.builder()
                .isArrangedUp(indicators.getIsArrangedUp())
                .isAboveMa20(indicators.getIsAboveMa20())
                .isAboveMa60(indicators.getIsAboveMa60())
                .isGoldenCross(indicators.getIsGoldenCross())
                .isDeadCross(indicators.getIsDeadCross())
                .rsi14(indicators.getRsi14())
                .rsiStatus(rsiStatus)
                .isRsiOversold(isRsiOversold)
                .isRsiOverbought(isRsiOverbought)
                // 볼린저 밴드
                .upperBand(bbResult != null ? bbResult.getUpperBand() : null)
                .middleBand(bbResult != null ? bbResult.getMiddleBand() : null)
                .lowerBand(bbResult != null ? bbResult.getLowerBand() : null)
                .bandWidth(bbResult != null ? bbResult.getBandWidth() : null)
                .isSqueeze(bbResult != null ? bbResult.getIsSqueeze() : null)
                .isBreakout(bbResult != null ? bbResult.getIsBreakout() : null)
                // MFI
                .mfiScore(mfiResult != null ? mfiResult.getMfiScore() : null)
                .mfiStatus(mfiResult != null && mfiResult.getMfiStatus() != null ?
                        mfiResult.getMfiStatus().getLabel() : null)
                // 종합
                .overallSignal(overallSignal)
                .buySignalStrength(score)
                .signalDescription(signalDesc)
                .score(score)
                .assessment(assessment)
                .build();
    }

    /**
     * 기술적 점수 계산 (볼린저 밴드 & MFI 포함)
     */
    private int calculateTechnicalScore(TechnicalIndicatorsDto indicators,
                                         TechnicalIndicatorService.BollingerBandsResult bbResult,
                                         TechnicalIndicatorService.MfiResult mfiResult) {
        int score = indicators.getBuySignalStrength() != null ? indicators.getBuySignalStrength() : 50;

        // 볼린저 밴드 가산점
        if (bbResult != null) {
            // 스퀴즈 상태 (에너지 응축) +5
            if (Boolean.TRUE.equals(bbResult.getIsSqueeze())) {
                score += 5;
            }
            // 상단 돌파 +10 (강한 상승 신호)
            if (Boolean.TRUE.equals(bbResult.getIsBreakout())) {
                score += 10;
            }
        }

        // MFI 가산점
        if (mfiResult != null && mfiResult.getMfiScore() != null) {
            BigDecimal mfi = mfiResult.getMfiScore();
            // MFI 침체 (20 이하): 매수 기회 +10
            if (mfi.compareTo(new BigDecimal("20")) <= 0) {
                score += 10;
            }
            // MFI 과열 (80 이상): 매도 신호 -5
            else if (mfi.compareTo(new BigDecimal("80")) >= 0) {
                score -= 5;
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 향상된 신호 설명 생성 (볼린저 밴드 & MFI 포함)
     */
    private String generateEnhancedSignalDescription(TechnicalIndicatorsDto indicators,
                                                      TechnicalIndicatorService.BollingerBandsResult bbResult,
                                                      TechnicalIndicatorService.MfiResult mfiResult) {
        List<String> signals = new ArrayList<>();

        // 기존 신호
        if (Boolean.TRUE.equals(indicators.getIsGoldenCross())) {
            signals.add("골든크로스");
        }
        if (Boolean.TRUE.equals(indicators.getIsDeadCross())) {
            signals.add("데드크로스");
        }
        if (Boolean.TRUE.equals(indicators.getIsArrangedUp())) {
            signals.add("정배열");
        }
        if (Boolean.TRUE.equals(indicators.getIsArrangedDown())) {
            signals.add("역배열");
        }

        // RSI
        if (indicators.getRsi14() != null) {
            if (indicators.getRsi14().compareTo(new BigDecimal("70")) >= 0) {
                signals.add("RSI 과열");
            } else if (indicators.getRsi14().compareTo(new BigDecimal("30")) <= 0) {
                signals.add("RSI 침체");
            }
        }

        // 볼린저 밴드
        if (bbResult != null) {
            if (Boolean.TRUE.equals(bbResult.getIsSqueeze())) {
                signals.add("BB 스퀴즈(폭발 대기)");
            }
            if (Boolean.TRUE.equals(bbResult.getIsBreakout())) {
                signals.add("BB 상단 돌파");
            }
        }

        // MFI
        if (mfiResult != null && mfiResult.getMfiStatus() != null) {
            switch (mfiResult.getMfiStatus()) {
                case OVERBOUGHT:
                    signals.add("MFI 과열");
                    break;
                case OVERSOLD:
                    signals.add("MFI 침체(거래량↑매수)");
                    break;
                default:
                    break;
            }
        }

        if (signals.isEmpty()) {
            return "특이 신호 없음";
        }

        return String.join(" / ", signals);
    }

    /**
     * 재무 건전성 점수 계산
     */
    private int calculateFinancialScore(BigDecimal operatingMargin, BigDecimal roe,
                                         BigDecimal debtRatio, boolean hasOneTimeGainWarning) {
        int score = 50;

        // 영업이익률
        if (operatingMargin != null) {
            if (operatingMargin.compareTo(new BigDecimal("15")) > 0) score += 20;
            else if (operatingMargin.compareTo(new BigDecimal("10")) > 0) score += 15;
            else if (operatingMargin.compareTo(new BigDecimal("5")) > 0) score += 10;
            else if (operatingMargin.compareTo(BigDecimal.ZERO) > 0) score += 5;
            else score -= 10;
        }

        // ROE
        if (roe != null) {
            if (roe.compareTo(new BigDecimal("15")) > 0) score += 15;
            else if (roe.compareTo(new BigDecimal("10")) > 0) score += 10;
            else if (roe.compareTo(new BigDecimal("5")) > 0) score += 5;
            else if (roe.compareTo(BigDecimal.ZERO) < 0) score -= 15;
        }

        // 부채비율
        if (debtRatio != null) {
            if (debtRatio.compareTo(new BigDecimal("50")) < 0) score += 10;
            else if (debtRatio.compareTo(new BigDecimal("100")) < 0) score += 5;
            else if (debtRatio.compareTo(new BigDecimal("200")) > 0) score -= 15;
        }

        // 일회성 이익 경고
        if (hasOneTimeGainWarning) {
            score -= 20;
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 수급 점수 계산
     */
    private int calculateSupplyDemandScore(boolean isForeignBuying, boolean isInstitutionBuying,
                                            int foreignBuyDays, int institutionBuyDays) {
        int score = 50;

        // 외국인
        if (isForeignBuying) {
            score += 15;
            score += foreignBuyDays * 3;  // 연속 매수일수 보너스
        } else {
            score -= 10;
        }

        // 기관
        if (isInstitutionBuying) {
            score += 15;
            score += institutionBuyDays * 3;
        } else {
            score -= 10;
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 종합 점수 계산
     */
    private int calculateOverallScore(FinancialHealthDto financial, SupplyDemandDto supply, TechnicalAnalysisDto technical) {
        // 가중 평균: 재무 30%, 수급 35%, 기술적 35%
        double weightedScore = financial.getScore() * 0.3 +
                              supply.getScore() * 0.35 +
                              technical.getScore() * 0.35;

        return (int) Math.round(weightedScore);
    }

    /**
     * 종합 의견 레벨 결정
     */
    private VerdictLevel determineVerdictLevel(int overallScore, int warningCount) {
        // 경고가 많으면 레벨 다운
        int adjustedScore = overallScore - (warningCount * 10);

        if (adjustedScore >= 75) {
            return VerdictLevel.STRONG_BUY;
        } else if (adjustedScore >= 60) {
            return VerdictLevel.BUY;
        } else if (adjustedScore >= 45) {
            return VerdictLevel.NEUTRAL;
        } else if (adjustedScore >= 30) {
            return VerdictLevel.CAUTION;
        } else {
            return VerdictLevel.AVOID;
        }
    }

    /**
     * 빈 진단 결과 생성
     */
    private StockDiagnosisDto createEmptyDiagnosis(String stockCode) {
        return StockDiagnosisDto.builder()
                .stockCode(stockCode)
                .diagnosisDate(LocalDate.now())
                .verdict("분석 불가")
                .verdictLevel(VerdictLevel.NEUTRAL)
                .overallScore(0)
                .warnings(List.of("재무 데이터가 없습니다. 먼저 데이터를 수집해주세요."))
                .positives(new ArrayList<>())
                .build();
    }
}
