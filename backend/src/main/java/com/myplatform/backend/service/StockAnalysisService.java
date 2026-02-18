package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.dto.StockDiagnosisDto.*;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.util.StockNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private final StockPriceHistoryRepository stockPriceHistoryRepository;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final KoreaInvestmentService koreaInvestmentService;

    // 기술적 분석에 필요한 최소 데이터 개수
    private static final int MIN_PRICE_DATA_COUNT = 60;

    // 상수
    private static final int SUPPLY_DEMAND_DAYS = 5;
    private static final int PRICE_DATA_DAYS = 120;  // 기술적 분석용 가격 데이터 일수
    private static final BigDecimal ONE_TIME_GAIN_THRESHOLD = new BigDecimal("50");  // 50% 이상 차이시 경고

    /**
     * 배치 점수 조회 — 여러 종목을 병렬 진단 후 경량 점수+수급만 반환
     *
     * @param stockCodes 종목코드 리스트
     * @return { "005930": { tradingScore, fundamentalScore, foreignBuying, instBuying } }
     */
    public Map<String, Map<String, Object>> batchScores(List<String> stockCodes) {
        Map<String, Map<String, Object>> result = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = stockCodes.stream()
                .map(code -> CompletableFuture.runAsync(() -> {
                    try {
                        StockDiagnosisDto diagnosis = diagnose(code);

                        int techScore = diagnosis.getTechnicalAnalysis() != null
                                ? diagnosis.getTechnicalAnalysis().getScore() : 50;
                        int supplyScore = diagnosis.getSupplyDemand() != null
                                ? diagnosis.getSupplyDemand().getScore() : 50;

                        // 단기 점수 = 기술(60%) + 수급(40%)
                        int tradingScore = (int) Math.round(techScore * 0.6 + supplyScore * 0.4);
                        // 중장기 점수 = overallScore (재무30 + 수급35 + 기술35)
                        int fundamentalScore = diagnosis.getOverallScore();

                        boolean foreignBuying = diagnosis.getSupplyDemand() != null
                                && diagnosis.getSupplyDemand().isForeignBuying();
                        boolean instBuying = diagnosis.getSupplyDemand() != null
                                && diagnosis.getSupplyDemand().isInstitutionBuying();

                        Map<String, Object> scoreMap = new HashMap<>();
                        scoreMap.put("tradingScore", tradingScore);
                        scoreMap.put("fundamentalScore", fundamentalScore);
                        scoreMap.put("foreignBuying", foreignBuying);
                        scoreMap.put("instBuying", instBuying);

                        result.put(code, scoreMap);
                    } catch (Exception e) {
                        log.warn("배치 점수 조회 실패 [{}]: {}", code, e.getMessage());
                    }
                }))
                .collect(Collectors.toList());

        // 모든 진단이 끝날 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("배치 점수 조회 완료: {}/{} 종목 성공", result.size(), stockCodes.size());
        return result;
    }

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

        // 경고 수집 - 매도 경고 강화
        if (financialHealth.isHasOneTimeGainWarning()) {
            warnings.add("일회성 이익 의심: " + financialHealth.getOneTimeGainReason());
        }

        // 수급 경고 - 매도는 반드시 경고!
        if (supplyDemand.isBothSelling()) {
            // 동반 매도는 가장 심각한 경고
            warnings.add("🚨 외국인+기관 동반 매도 중! 뇌동매매 금지!");
        } else {
            // 개별 매도 경고
            if (supplyDemand.getForeignNet5Days() != null &&
                supplyDemand.getForeignNet5Days().compareTo(BigDecimal.ZERO) < 0) {
                // 외국인 순매도 금액 계산 (억 단위)
                BigDecimal foreignNetBillion = supplyDemand.getForeignNet5Days()
                        .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP).abs();
                warnings.add("⚠️ 외국인 순매도 " + foreignNetBillion + "억 - 신중히 접근!");
            }
            if (supplyDemand.getInstitutionNet5Days() != null &&
                supplyDemand.getInstitutionNet5Days().compareTo(BigDecimal.ZERO) < 0) {
                // 기관 순매도 금액 계산 (억 단위)
                BigDecimal institutionNetBillion = supplyDemand.getInstitutionNet5Days()
                        .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP).abs();
                warnings.add("⚠️ 기관 순매도 " + institutionNetBillion + "억 - 신중히 접근!");
            }
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
        // ⚠️ 핵심 수정: 동반 매수는 둘 다 양수(>0)일 때만 표시!
        if (supplyDemand.isBothBuying() &&
            supplyDemand.getForeignNet5Days() != null &&
            supplyDemand.getForeignNet5Days().compareTo(BigDecimal.ZERO) > 0 &&
            supplyDemand.getInstitutionNet5Days() != null &&
            supplyDemand.getInstitutionNet5Days().compareTo(BigDecimal.ZERO) > 0) {
            positives.add("외국인+기관 동반 매수 중");
        }
        // 외국인만 매수
        else if (supplyDemand.isForeignBuying() &&
                 supplyDemand.getForeignNet5Days() != null &&
                 supplyDemand.getForeignNet5Days().compareTo(BigDecimal.ZERO) > 0) {
            positives.add("외국인 순매수 중");
        }
        // 기관만 매수
        else if (supplyDemand.isInstitutionBuying() &&
                 supplyDemand.getInstitutionNet5Days() != null &&
                 supplyDemand.getInstitutionNet5Days().compareTo(BigDecimal.ZERO) > 0) {
            positives.add("기관 순매수 중");
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

        // ⭐ 종목명 최종 검증 (코드로 표시되면 안됨!)
        String stockName = financialData.getStockName();
        if (stockName == null || stockName.isEmpty() || stockName.equals(stockCode)) {
            stockName = fetchStockNameFallback(stockCode);
        }

        return StockDiagnosisDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
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
     * - operatingProfit/netIncome이 없으면 다른 분기 데이터에서 조회
     */
    private FinancialHealthDto analyzeFinancialHealth(StockFinancialData data) {
        BigDecimal operatingProfit = data.getOperatingProfit();
        BigDecimal netIncome = data.getNetIncome();
        BigDecimal operatingMargin = data.getOperatingMargin();
        BigDecimal netMargin = data.getNetMargin();
        BigDecimal roe = data.getRoe();
        BigDecimal debtRatio = data.getDebtRatio();
        BigDecimal revenue = data.getRevenue();
        BigDecimal totalEquity = data.getTotalEquity();

        // operatingProfit/netIncome이 없으면 가장 최근 분기 데이터에서 조회
        if ((operatingProfit == null || netIncome == null) && data.getStockCode() != null) {
            List<StockFinancialData> historicalData = stockFinancialDataRepository
                    .findByStockCodeOrderByReportDateDesc(data.getStockCode());
            for (StockFinancialData hist : historicalData) {
                if (operatingProfit == null && hist.getOperatingProfit() != null) {
                    operatingProfit = hist.getOperatingProfit();
                    log.debug("[재무분석] {} 영업이익 보완: {} (from {})",
                            data.getStockCode(), operatingProfit, hist.getReportDate());
                }
                if (netIncome == null && hist.getNetIncome() != null) {
                    netIncome = hist.getNetIncome();
                    log.debug("[재무분석] {} 당기순이익 보완: {} (from {})",
                            data.getStockCode(), netIncome, hist.getReportDate());
                }
                if (revenue == null && hist.getRevenue() != null) {
                    revenue = hist.getRevenue();
                }
                if (totalEquity == null && hist.getTotalEquity() != null) {
                    totalEquity = hist.getTotalEquity();
                }
                if (operatingProfit != null && netIncome != null
                        && revenue != null && totalEquity != null) break;
            }
        }

        // ★ 영업이익률: DB값이 null/0이면 영업이익÷매출액으로 재계산
        if ((operatingMargin == null || operatingMargin.compareTo(BigDecimal.ZERO) == 0)
                && operatingProfit != null && revenue != null
                && revenue.compareTo(BigDecimal.ZERO) > 0) {
            operatingMargin = operatingProfit
                    .divide(revenue, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            log.info("[재무분석] {} 영업이익률 재계산: {}% (영업이익: {}, 매출: {})",
                    data.getStockCode(), operatingMargin, operatingProfit, revenue);
        }

        // ★ 순이익률: DB값이 null/0이면 당기순이익÷매출액으로 재계산
        if ((netMargin == null || netMargin.compareTo(BigDecimal.ZERO) == 0)
                && netIncome != null && revenue != null
                && revenue.compareTo(BigDecimal.ZERO) > 0) {
            netMargin = netIncome
                    .divide(revenue, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            log.info("[재무분석] {} 순이익률 재계산: {}% (순이익: {}, 매출: {})",
                    data.getStockCode(), netMargin, netIncome, revenue);
        }

        // ★ ROE: DB값이 null/0이면 당기순이익÷자본총계로 재계산
        if ((roe == null || roe.compareTo(BigDecimal.ZERO) == 0)
                && netIncome != null && totalEquity != null
                && totalEquity.compareTo(BigDecimal.ZERO) > 0) {
            roe = netIncome
                    .divide(totalEquity, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            log.info("[재무분석] {} ROE 재계산: {}% (순이익: {}, 자본총계: {})",
                    data.getStockCode(), roe, netIncome, totalEquity);
        }

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
     * - netBuyAmount가 양수면 순매수, 음수면 순매도
     */
    private SupplyDemandDto analyzeSupplyDemand(String stockCode) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(SUPPLY_DEMAND_DAYS + 5);  // 여유있게 조회

        List<InvestorDailyTrade> trades = investorDailyTradeRepository
                .findByStockCodeAndDateRange(stockCode, startDate, endDate);

        // 외국인 수급
        BigDecimal foreignNet5Days = BigDecimal.ZERO;
        int foreignBuyDays = 0;
        int foreignSellDays = 0;

        // 기관 수급
        BigDecimal institutionNet5Days = BigDecimal.ZERO;
        int institutionBuyDays = 0;
        int institutionSellDays = 0;

        // 일자별 집계
        List<LocalDate> recentDates = trades.stream()
                .map(InvestorDailyTrade::getTradeDate)
                .distinct()
                .sorted((a, b) -> b.compareTo(a))  // 최신순
                .limit(SUPPLY_DEMAND_DAYS)
                .collect(Collectors.toList());

        for (LocalDate date : recentDates) {
            // 외국인 - tradeType에 따라 BUY는 +, SELL은 - 처리!
            BigDecimal foreignBuy = trades.stream()
                    .filter(t -> t.getTradeDate().equals(date))
                    .filter(t -> "FOREIGN".equals(t.getInvestorType()))
                    .filter(t -> "BUY".equals(t.getTradeType()))
                    .map(t -> t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal foreignSell = trades.stream()
                    .filter(t -> t.getTradeDate().equals(date))
                    .filter(t -> "FOREIGN".equals(t.getInvestorType()))
                    .filter(t -> "SELL".equals(t.getTradeType()))
                    .map(t -> t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 순매수 = 매수금액 - 매도금액
            BigDecimal foreignDayNet = foreignBuy.subtract(foreignSell);

            foreignNet5Days = foreignNet5Days.add(foreignDayNet);
            if (foreignDayNet.compareTo(BigDecimal.ZERO) > 0) {
                foreignBuyDays++;
            } else if (foreignDayNet.compareTo(BigDecimal.ZERO) < 0) {
                foreignSellDays++;
            }

            // 기관 - tradeType에 따라 BUY는 +, SELL은 - 처리!
            BigDecimal institutionBuy = trades.stream()
                    .filter(t -> t.getTradeDate().equals(date))
                    .filter(t -> "INSTITUTION".equals(t.getInvestorType()))
                    .filter(t -> "BUY".equals(t.getTradeType()))
                    .map(t -> t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal institutionSell = trades.stream()
                    .filter(t -> t.getTradeDate().equals(date))
                    .filter(t -> "INSTITUTION".equals(t.getInvestorType()))
                    .filter(t -> "SELL".equals(t.getTradeType()))
                    .map(t -> t.getNetBuyAmount() != null ? t.getNetBuyAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 순매수 = 매수금액 - 매도금액
            BigDecimal institutionDayNet = institutionBuy.subtract(institutionSell);

            institutionNet5Days = institutionNet5Days.add(institutionDayNet);
            if (institutionDayNet.compareTo(BigDecimal.ZERO) > 0) {
                institutionBuyDays++;
            } else if (institutionDayNet.compareTo(BigDecimal.ZERO) < 0) {
                institutionSellDays++;
            }
        }

        // ⚠️ 핵심 로직: 양수(>0)일때만 매수, 음수(<0)일때만 매도
        boolean isForeignBuying = foreignNet5Days.compareTo(BigDecimal.ZERO) > 0;
        boolean isForeignSelling = foreignNet5Days.compareTo(BigDecimal.ZERO) < 0;
        boolean isInstitutionBuying = institutionNet5Days.compareTo(BigDecimal.ZERO) > 0;
        boolean isInstitutionSelling = institutionNet5Days.compareTo(BigDecimal.ZERO) < 0;

        // 동반 매수: 둘 다 양수(순매수)일 때만!
        boolean isBothBuying = isForeignBuying && isInstitutionBuying;
        // 동반 매도: 둘 다 음수(순매도)일 때!
        boolean isBothSelling = isForeignSelling && isInstitutionSelling;

        // 점수 계산 - 매도 시 감점 반영
        int score = calculateSupplyDemandScoreV2(
                foreignNet5Days, institutionNet5Days,
                foreignBuyDays, institutionBuyDays,
                foreignSellDays, institutionSellDays
        );

        // 평가 - 매도 시 경고 강화
        String assessment;
        if (isBothBuying) {
            assessment = "✅ 외국인+기관 동반 매수";
        } else if (isBothSelling) {
            assessment = "⚠️ 매도 우위 (뇌동매매 주의!)";
        } else if (isForeignSelling && isInstitutionSelling) {
            // 이미 isBothSelling에서 처리됨
            assessment = "⚠️ 매도 우위 (주의)";
        } else if (isForeignSelling) {
            assessment = "⚠️ 외국인 매도 (주의)";
        } else if (isInstitutionSelling) {
            assessment = "⚠️ 기관 매도 (주의)";
        } else if (isForeignBuying && !isInstitutionBuying) {
            assessment = "외국인 매수 / 기관 관망";
        } else if (!isForeignBuying && isInstitutionBuying) {
            assessment = "기관 매수 / 외국인 관망";
        } else {
            assessment = "혼조 (관망)";
        }

        log.debug("[수급분석] {} - 외국인: {}억({}일 매수/{}일 매도), 기관: {}억({}일 매수/{}일 매도), 평가: {}",
                stockCode,
                foreignNet5Days.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP),
                foreignBuyDays, foreignSellDays,
                institutionNet5Days.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP),
                institutionBuyDays, institutionSellDays,
                assessment);

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
     * - StockPriceHistory DB 우선 조회, 부족하면 KIS API로 수집 후 DB 저장
     * - 볼린저 밴드 & MFI 지표 포함
     */
    private TechnicalAnalysisDto analyzeTechnical(String stockCode) {
        List<BigDecimal> closePrices = new ArrayList<>();
        List<KoreaInvestmentService.OhlcvData> ohlcvData = null;

        // 1차: StockPriceHistory DB에서 조회
        List<StockPriceHistory> historyData = stockPriceHistoryRepository
                .findByStockCodeOrderByTradeDateDesc(stockCode, PageRequest.of(0, PRICE_DATA_DAYS));

        if (historyData.size() >= MIN_PRICE_DATA_COUNT) {
            // DB에 충분한 데이터가 있음
            closePrices = historyData.stream()
                    .map(StockPriceHistory::getClosePrice)
                    .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            // OHLCV 데이터도 변환 (MFI 계산용)
            ohlcvData = historyData.stream()
                    .map(h -> new KoreaInvestmentService.OhlcvData(
                            h.getOpenPrice(), h.getHighPrice(), h.getLowPrice(),
                            h.getClosePrice(), h.getVolume()))
                    .collect(Collectors.toList());

            log.debug("종목 {} StockPriceHistory에서 {} 건의 일봉 조회", stockCode, closePrices.size());
        }

        // 2차: DB 데이터 부족 → KIS API에서 수집 후 DB 저장 (재시도 포함)
        if (closePrices.size() < MIN_PRICE_DATA_COUNT) {
            log.info("종목 {} 일봉 데이터 부족 ({}/{}건), KIS API에서 수집 시작...",
                    stockCode, closePrices.size(), MIN_PRICE_DATA_COUNT);

            // ⚠️ Rate Limit 방어: API 호출 전 딜레이
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}

            // KIS API에서 OHLCV 데이터 조회 (재시도 포함)
            int maxRetries = 2;
            for (int retry = 0; retry <= maxRetries && (ohlcvData == null || ohlcvData.isEmpty()); retry++) {
                if (retry > 0) {
                    log.info("종목 {} KIS API 재시도 {}/{}", stockCode, retry, maxRetries);
                    try {
                        Thread.sleep(500 * retry);  // 재시도 시 더 긴 딜레이
                    } catch (InterruptedException ignored) {}
                }

                try {
                    ohlcvData = koreaInvestmentService.getDailyOhlcv(stockCode, PRICE_DATA_DAYS);
                } catch (Exception e) {
                    log.warn("종목 {} KIS API 조회 실패 (시도 {}): {}", stockCode, retry + 1, e.getMessage());
                }
            }

            if (ohlcvData != null && !ohlcvData.isEmpty()) {
                log.info("종목 {} KIS API에서 {} 건의 일봉 데이터 조회 성공 - DB 저장 중...",
                        stockCode, ohlcvData.size());

                // DB에 저장 (중복 방지를 위해 날짜별로 체크)
                savePriceHistoryToDb(stockCode, ohlcvData);

                // 종가 추출
                closePrices = ohlcvData.stream()
                        .map(KoreaInvestmentService.OhlcvData::getClose)
                        .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                        .collect(Collectors.toList());

                log.info("종목 {} 일봉 데이터 DB 저장 완료 - {} 건", stockCode, closePrices.size());
            } else {
                log.warn("종목 {} KIS API 일봉 조회 최종 실패", stockCode);
            }
        }

        // 최소 데이터 검증 (20개 미만이면 분석 불가)
        if (closePrices.size() < 20) {
            log.warn("종목 {} 의 가격 데이터가 부족합니다 ({} 건). 최소 20건 필요.", stockCode, closePrices.size());
            return TechnicalAnalysisDto.builder()
                    .score(50)
                    .assessment("데이터 부족 (" + closePrices.size() + "/20)")
                    .signalDescription("가격 데이터 " + closePrices.size() + "건 (최소 20건 필요)")
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

        // MFI 계산 (OHLCV 데이터가 있는 경우)
        TechnicalIndicatorService.MfiResult mfiResult = null;
        if (ohlcvData != null && !ohlcvData.isEmpty()) {
            // KIS API OhlcvData → TechnicalIndicatorService OhlcvData 변환
            List<TechnicalIndicatorService.OhlcvData> convertedOhlcv = ohlcvData.stream()
                    .map(d -> new TechnicalIndicatorService.OhlcvData(
                            d.getOpen(), d.getHigh(), d.getLow(), d.getClose(), d.getVolume()))
                    .collect(Collectors.toList());
            mfiResult = technicalIndicatorService.calculateMFI(convertedOhlcv);
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
     * 수급 점수 계산 (기존 - deprecated)
     */
    @Deprecated
    private int calculateSupplyDemandScore(boolean isForeignBuying, boolean isInstitutionBuying,
                                            int foreignBuyDays, int institutionBuyDays) {
        return calculateSupplyDemandScoreV2(
                isForeignBuying ? BigDecimal.ONE : BigDecimal.ONE.negate(),
                isInstitutionBuying ? BigDecimal.ONE : BigDecimal.ONE.negate(),
                foreignBuyDays, institutionBuyDays, 0, 0
        );
    }

    /**
     * 수급 점수 계산 V2 - 매도 시 감점 강화 (뇌동매매 방지)
     *
     * 점수 기준:
     * - 50점 기준
     * - 순매수: +15점 기본 + 매수일*3점
     * - 순매도: -20점 기본 - 매도일*4점 (강화!)
     * - 동반매도: 추가 -15점 (강화!)
     * - 대량매도(100억 이상): 추가 -10점
     */
    private int calculateSupplyDemandScoreV2(BigDecimal foreignNet, BigDecimal institutionNet,
                                              int foreignBuyDays, int institutionBuyDays,
                                              int foreignSellDays, int institutionSellDays) {
        int score = 50;  // 기준점
        BigDecimal BILLION_100 = new BigDecimal("10000000000");  // 100억

        // 외국인 수급
        if (foreignNet.compareTo(BigDecimal.ZERO) > 0) {
            // 순매수: 가점
            score += 15;
            score += foreignBuyDays * 3;
        } else if (foreignNet.compareTo(BigDecimal.ZERO) < 0) {
            // 순매도: 감점 강화!
            score -= 20;  // 기본 감점 (15→20)
            score -= foreignSellDays * 4;  // 매도일 페널티 강화 (3→4)

            // 대량 매도 추가 페널티 (100억 이상)
            if (foreignNet.abs().compareTo(BILLION_100) > 0) {
                score -= 10;
            }
        }

        // 기관 수급
        if (institutionNet.compareTo(BigDecimal.ZERO) > 0) {
            // 순매수: 가점
            score += 15;
            score += institutionBuyDays * 3;
        } else if (institutionNet.compareTo(BigDecimal.ZERO) < 0) {
            // 순매도: 감점 강화!
            score -= 20;  // 기본 감점 (15→20)
            score -= institutionSellDays * 4;  // 매도일 페널티 강화 (3→4)

            // 대량 매도 추가 페널티 (100억 이상)
            if (institutionNet.abs().compareTo(BILLION_100) > 0) {
                score -= 10;
            }
        }

        // 동반 매도 추가 페널티 강화!
        if (foreignNet.compareTo(BigDecimal.ZERO) < 0 && institutionNet.compareTo(BigDecimal.ZERO) < 0) {
            score -= 15;  // 동반 매도 시 추가 감점 (10→15)
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
     * KIS API에서 조회한 일봉 데이터를 DB에 저장
     * - 중복 방지: 이미 존재하는 날짜는 스킵
     * - OhlcvData.tradeDate 사용 (KIS API stck_bsop_date 필드에서 추출)
     */
    private void savePriceHistoryToDb(String stockCode, List<KoreaInvestmentService.OhlcvData> ohlcvData) {
        try {
            LocalDate today = LocalDate.now();
            int savedCount = 0;
            int skippedCount = 0;
            int fallbackIndex = 0;

            for (KoreaInvestmentService.OhlcvData data : ohlcvData) {
                // API에서 제공한 거래일자 사용, 없으면 인덱스로 추정
                LocalDate tradeDate = data.getTradeDate();
                if (tradeDate == null) {
                    // 폴백: 인덱스로 날짜 추정 (영업일만)
                    tradeDate = today.minusDays(fallbackIndex);
                    while (tradeDate.getDayOfWeek().getValue() >= 6) {
                        tradeDate = tradeDate.minusDays(1);
                    }
                    fallbackIndex++;
                }

                // 이미 존재하면 스킵
                if (stockPriceHistoryRepository.existsByStockCodeAndTradeDate(stockCode, tradeDate)) {
                    skippedCount++;
                    continue;
                }

                // 유효한 데이터만 저장
                if (data.getClose() == null || data.getClose().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                StockPriceHistory history = StockPriceHistory.builder()
                        .stockCode(stockCode)
                        .tradeDate(tradeDate)
                        .openPrice(data.getOpen())
                        .highPrice(data.getHigh())
                        .lowPrice(data.getLow())
                        .closePrice(data.getClose())
                        .volume(data.getVolume())
                        .dataSource("KIS")
                        .build();

                stockPriceHistoryRepository.save(history);
                savedCount++;
            }

            log.info("종목 {} 일봉 데이터 저장 완료 - 신규: {}, 스킵(중복): {}",
                    stockCode, savedCount, skippedCount);

        } catch (Exception e) {
            log.error("종목 {} 일봉 데이터 저장 실패: {}", stockCode, e.getMessage());
        }
    }

    /**
     * 빈 진단 결과 생성
     */
    private StockDiagnosisDto createEmptyDiagnosis(String stockCode) {
        return StockDiagnosisDto.builder()
                .stockCode(stockCode)
                .stockName(fetchStockNameFallback(stockCode))
                .diagnosisDate(LocalDate.now())
                .verdict("분석 불가")
                .verdictLevel(VerdictLevel.NEUTRAL)
                .overallScore(0)
                .warnings(List.of("재무 데이터가 없습니다. 먼저 데이터를 수집해주세요."))
                .positives(new ArrayList<>())
                .build();
    }

    /**
     * 종목명 폴백 조회 (코드가 아닌 한글 이름 반환)
     * - KIS API 조회 → DB 조회 → 주요 종목 맵 순서로 시도
     */
    private String fetchStockNameFallback(String stockCode) {
        // 1차: KIS API에서 조회
        try {
            com.fasterxml.jackson.databind.JsonNode response = koreaInvestmentService.getStockPrice(stockCode);
            if (response != null && response.has("output")) {
                com.fasterxml.jackson.databind.JsonNode output = response.get("output");
                String name = output.has("hts_kor_isnm") ? output.get("hts_kor_isnm").asText() : null;
                if (name != null && !name.isEmpty() && !name.equals(stockCode)) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.debug("KIS API 종목명 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }

        // 2차: 주요 종목 맵에서 조회
        return StockNameResolver.getNameOrDefault(stockCode, stockCode);
    }
}
