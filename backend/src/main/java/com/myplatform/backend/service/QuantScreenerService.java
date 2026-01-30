package com.myplatform.backend.service;

import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 퀀트 스크리너 서비스
 * - 마법의 공식 (Magic Formula) 스크리닝
 * - PEG 기반 저평가 성장주 스크리닝
 * - 분기 실적 턴어라운드 스크리닝
 *
 * [성능 최적화]
 * - N+1 문제 해결: Bulk 조회 후 메모리에서 groupingBy 처리
 * - 적자 기업 필터링: 마법의 공식에서 PER <= 0 기업 사전 제외
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuantScreenerService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final TelegramNotificationService telegramNotificationService;

    private static final BigDecimal MAX_DEBT_RATIO = new BigDecimal("200"); // 부채비율 상한 200%
    private static final BigDecimal MIN_NET_INCOME = new BigDecimal("30");  // 최소 순이익 30억원

    /**
     * 마법의 공식 스크리너
     * - (영업이익률 순위 + ROE 순위 + PER 순위) 합산으로 종합 순위 계산
     * - 영업이익률이 없으면 ROE + PER만으로 계산 (2지표 모드)
     *
     * [필터 조건]
     * - PER > 0 (적자 기업 제외)
     * - ROE > 0 (수익성 있는 기업)
     * - 부채비율 <= 200% (ROE 왜곡 방지, 재무 건전성 확보)
     */
    public List<ScreenerResultDto> getMagicFormulaStocks(Integer limit, BigDecimal minMarketCap) {
        log.info("마법의 공식 스크리닝 시작 - limit: {}, minMarketCap: {}", limit, minMarketCap);

        // 1. 조건에 맞는 종목 조회 (Repository에서 이미 PER > 0 필터 적용됨)
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findForMagicFormula(minMarketCap);

        // 2. 기본 필터: PER > 0, ROE > 0, 부채비율 <= 200%
        List<StockFinancialData> stocks = allStocks.stream()
                .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                .filter(s -> s.getRoe() != null && s.getRoe().compareTo(BigDecimal.ZERO) > 0)
                // 부채비율 필터: null이면 통과, 있으면 200% 이하만 포함
                .filter(s -> s.getDebtRatio() == null || s.getDebtRatio().compareTo(MAX_DEBT_RATIO) <= 0)
                .collect(Collectors.toList());

        if (stocks.isEmpty()) {
            log.info("마법의 공식 조건에 맞는 종목이 없습니다.");
            return Collections.emptyList();
        }

        // 영업이익률이 있는 종목 수 확인
        long withOperatingMargin = stocks.stream()
                .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                .count();
        boolean useOperatingMargin = withOperatingMargin > stocks.size() / 2; // 절반 이상 있으면 사용

        log.info("마법의 공식 후보 종목 수: {} (영업이익률 있는 종목: {}, 사용여부: {})",
                stocks.size(), withOperatingMargin, useOperatingMargin);

        // 3. 각 지표별 순위 계산
        int totalStocks = stocks.size();

        // 영업이익률 순위 (있는 경우만, 없으면 최하위 순위 부여)
        Map<String, Integer> operatingMarginRanks = new HashMap<>();
        if (useOperatingMargin) {
            List<StockFinancialData> withOpMargin = stocks.stream()
                    .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(StockFinancialData::getOperatingMargin).reversed())
                    .collect(Collectors.toList());
            for (int i = 0; i < withOpMargin.size(); i++) {
                operatingMarginRanks.put(withOpMargin.get(i).getStockCode(), i + 1);
            }
        }

        // ROE 순위 (높을수록 좋음 = 낮은 순위)
        Map<String, Integer> roeRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getRoe).reversed());

        // PER 순위 (낮을수록 좋음 = 낮은 순위)
        Map<String, Integer> perRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getPer));

        // 4. 종합 순위 계산
        final boolean finalUseOperatingMargin = useOperatingMargin;
        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    Integer roeRank = roeRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer perRank = perRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer opMarginRank = totalStocks; // 기본값: 최하위

                    int totalScore;
                    if (finalUseOperatingMargin) {
                        opMarginRank = operatingMarginRanks.getOrDefault(stock.getStockCode(), totalStocks);
                        totalScore = opMarginRank + roeRank + perRank;
                    } else {
                        // 영업이익률 없으면 ROE + PER만으로 계산 (가중치 1.5배)
                        totalScore = (int) ((roeRank + perRank) * 1.5);
                    }

                    return ScreenerResultDto.builder()
                            .stockCode(stock.getStockCode())
                            .stockName(stock.getStockName())
                            .market(stock.getMarket())
                            .sector(stock.getSector())
                            .currentPrice(stock.getCurrentPrice())
                            .marketCap(stock.getMarketCap())
                            .per(stock.getPer())
                            .pbr(stock.getPbr())
                            .roe(stock.getRoe())
                            .operatingMargin(stock.getOperatingMargin())
                            .netMargin(stock.getNetMargin())
                            .eps(stock.getEps())
                            .epsGrowth(stock.getEpsGrowth())
                            .peg(stock.getPeg())
                            .magicFormulaScore(BigDecimal.valueOf(totalScore))
                            .operatingMarginRank(opMarginRank)
                            .roeRank(roeRank)
                            .perRank(perRank)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                .sorted(Comparator.comparing(ScreenerResultDto::getMagicFormulaScore))
                .collect(Collectors.toList());

        // 5. 최종 순위 부여
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setMagicFormulaRank(i + 1);
        }

        // 6. limit 적용
        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("마법의 공식 스크리닝 완료 - 결과 {}건", results.size());
        return results;
    }

    /**
     * 순위 계산 헬퍼 메서드
     */
    private Map<String, Integer> calculateRanks(List<StockFinancialData> stocks,
                                                 Comparator<StockFinancialData> comparator) {
        List<StockFinancialData> sorted = stocks.stream()
                .sorted(comparator)
                .collect(Collectors.toList());

        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).getStockCode(), i + 1);
        }
        return ranks;
    }

    /**
     * PEG 스크리너
     * - PEG = PER / EPS성장률 (또는 profitGrowth로 대체)
     * - PEG < maxPeg (기본 1.0)인 저평가 성장주 발굴
     *
     * [로직]
     * 1. DB에 PEG가 이미 계산된 종목 조회
     * 2. 없으면 epsGrowth 또는 profitGrowth로 PEG 계산
     * 3. 그래도 없으면 분기별 데이터에서 직접 EPS 성장률 계산
     */
    public List<ScreenerResultDto> getLowPegStocks(BigDecimal maxPeg, BigDecimal minEpsGrowth, Integer limit) {
        log.info("PEG 스크리닝 시작 - maxPeg: {}, minEpsGrowth: {}, limit: {}", maxPeg, minEpsGrowth, limit);

        // 기본값 설정
        if (maxPeg == null) {
            maxPeg = new BigDecimal("1.0");
        }
        if (minEpsGrowth == null) {
            minEpsGrowth = new BigDecimal("10.0"); // 최소 10% 성장
        }

        final BigDecimal finalMaxPeg = maxPeg;
        final BigDecimal finalMinGrowth = minEpsGrowth;

        // 1차: PEG가 이미 계산된 종목 조회
        List<StockFinancialData> stocks = stockFinancialDataRepository.findLowPegStocks(maxPeg, minEpsGrowth);
        log.info("DB에서 PEG 있는 종목: {}건", stocks.size());

        // 2차: 성장률 데이터가 있는 종목으로 PEG 계산
        if (stocks.isEmpty()) {
            log.info("PEG 데이터가 없어 성장률 기반으로 계산합니다.");
            stocks = stockFinancialDataRepository.findStocksWithGrowthData();
            log.info("성장률 데이터 있는 종목: {}건", stocks.size());
        }

        // 3차: 분기별 데이터에서 직접 EPS 성장률 계산 (fallback)
        if (stocks.isEmpty()) {
            log.info("성장률 데이터 없음 - 분기별 데이터에서 직접 계산합니다.");
            return calculatePegFromQuarterlyData(finalMaxPeg, finalMinGrowth, limit);
        }

        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    // PEG 계산: 기존 PEG 사용 또는 epsGrowth/profitGrowth로 계산
                    BigDecimal peg = stock.getPeg();
                    BigDecimal growthRate = stock.getEpsGrowth();

                    // PEG가 없으면 계산
                    if (peg == null && stock.getPer() != null && stock.getPer().compareTo(BigDecimal.ZERO) > 0) {
                        // epsGrowth 우선, 없으면 profitGrowth 사용
                        if (growthRate != null && growthRate.compareTo(BigDecimal.ZERO) > 0) {
                            peg = stock.getPer().divide(growthRate, 2, RoundingMode.HALF_UP);
                        } else if (stock.getProfitGrowth() != null && stock.getProfitGrowth().compareTo(BigDecimal.ZERO) > 0) {
                            peg = stock.getPer().divide(stock.getProfitGrowth(), 2, RoundingMode.HALF_UP);
                            growthRate = stock.getProfitGrowth();
                        }
                    }

                    return ScreenerResultDto.builder()
                            .stockCode(stock.getStockCode())
                            .stockName(stock.getStockName())
                            .market(stock.getMarket())
                            .sector(stock.getSector())
                            .currentPrice(stock.getCurrentPrice())
                            .marketCap(stock.getMarketCap())
                            .per(stock.getPer())
                            .pbr(stock.getPbr())
                            .roe(stock.getRoe())
                            .operatingMargin(stock.getOperatingMargin())
                            .netMargin(stock.getNetMargin())
                            .eps(stock.getEps())
                            .epsGrowth(growthRate != null ? growthRate : stock.getEpsGrowth())
                            .peg(peg)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                // PEG 필터: 0 < PEG <= maxPeg, 성장률 >= minGrowth
                .filter(dto -> dto.getPeg() != null && dto.getPeg().compareTo(BigDecimal.ZERO) > 0)
                .filter(dto -> dto.getPeg().compareTo(finalMaxPeg) <= 0)
                .filter(dto -> dto.getEpsGrowth() != null && dto.getEpsGrowth().compareTo(finalMinGrowth) >= 0)
                .sorted(Comparator.comparing(ScreenerResultDto::getPeg))
                .collect(Collectors.toList());

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("PEG 스크리닝 완료 - 결과 {}건", results.size());
        return results;
    }

    /**
     * profitGrowth 기반으로 PEG 계산 (fallback)
     * - DB에 PEG가 없고 epsGrowth도 없을 때 profitGrowth로 대체
     */
    private List<ScreenerResultDto> calculatePegFromQuarterlyData(BigDecimal maxPeg, BigDecimal minGrowth, Integer limit) {
        // profitGrowth가 있는 종목들 조회
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findStocksWithGrowthData();
        log.info("성장률 데이터 있는 종목 (fallback): {}건", allStocks.size());

        List<ScreenerResultDto> results = new ArrayList<>();

        for (StockFinancialData stock : allStocks) {
            if (stock.getPer() == null || stock.getPer().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // profitGrowth로 PEG 계산
            BigDecimal growthRate = stock.getProfitGrowth();
            if (growthRate == null || growthRate.compareTo(minGrowth) < 0) {
                continue;
            }

            BigDecimal peg = stock.getPer().divide(growthRate, 2, RoundingMode.HALF_UP);
            if (peg.compareTo(BigDecimal.ZERO) <= 0 || peg.compareTo(maxPeg) > 0) {
                continue;
            }

            results.add(ScreenerResultDto.builder()
                    .stockCode(stock.getStockCode())
                    .stockName(stock.getStockName())
                    .market(stock.getMarket())
                    .sector(stock.getSector())
                    .currentPrice(stock.getCurrentPrice())
                    .marketCap(stock.getMarketCap())
                    .per(stock.getPer())
                    .pbr(stock.getPbr())
                    .roe(stock.getRoe())
                    .operatingMargin(stock.getOperatingMargin())
                    .netMargin(stock.getNetMargin())
                    .eps(stock.getEps())
                    .epsGrowth(growthRate) // profitGrowth를 epsGrowth로 사용
                    .peg(peg)
                    .revenueGrowth(stock.getRevenueGrowth())
                    .profitGrowth(stock.getProfitGrowth())
                    .build());
        }

        // PEG 기준 정렬
        results.sort(Comparator.comparing(ScreenerResultDto::getPeg));

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("profitGrowth 기반 PEG 계산 완료 - 결과 {}건", results.size());
        return results;
    }

    /**
     * 턴어라운드 스크리너
     * - 직전 분기 적자 → 당 분기 흑자 전환 종목
     * - 순이익 개선률 계산
     *
     * [개선 1] N+1 문제 해결
     * - 기존: findAllStockCodes() 후 2,000번 개별 쿼리 (서버 뻗음)
     * - 개선: findAllRecentData()로 한 번에 조회 → 메모리에서 groupingBy 처리
     *
     * [개선 2] 여러 분기 데이터가 없는 경우 profitGrowth 기반으로 대체
     */
    public List<ScreenerResultDto> getTurnaroundStocks(Integer limit) {
        log.info("턴어라운드 스크리닝 시작 - limit: {}", limit);

        LocalDate minDate = LocalDate.now().minusMonths(12);
        List<StockFinancialData> allRecentData = stockFinancialDataRepository.findAllRecentData(minDate);
        log.info("최근 12개월 데이터: {}건 (minDate: {})", allRecentData.size(), minDate);

        List<ScreenerResultDto> results = new ArrayList<>();

        if (!allRecentData.isEmpty()) {
            log.info("턴어라운드 분석 대상 데이터: {}건", allRecentData.size());

            // [성능 최적화] 메모리에서 종목별로 그룹화 (날짜 내림차순 정렬 유지)
            Map<String, List<StockFinancialData>> stockDataMap = allRecentData.stream()
                    .collect(Collectors.groupingBy(
                            StockFinancialData::getStockCode,
                            LinkedHashMap::new,  // 순서 유지
                            Collectors.toList()
                    ));

            // 각 종목별로 턴어라운드 여부 판단
            for (Map.Entry<String, List<StockFinancialData>> entry : stockDataMap.entrySet()) {
                List<StockFinancialData> historicalData = entry.getValue();

                // 최소 2개 분기 데이터 필요
                if (historicalData.size() < 2) {
                    continue;
                }

                // 이미 날짜 내림차순 정렬되어 있음 (쿼리에서 ORDER BY)
                StockFinancialData current = historicalData.get(0);
                StockFinancialData previous = historicalData.get(1);

                // 순이익 데이터 확인
                if (current.getNetIncome() == null || previous.getNetIncome() == null) {
                    continue;
                }

                BigDecimal currentNetIncome = current.getNetIncome();
                BigDecimal previousNetIncome = previous.getNetIncome();

                // 잡주 필터링: 당 분기 순이익이 최소 30억원 이상이어야 함
                if (currentNetIncome.compareTo(MIN_NET_INCOME) < 0) {
                    continue;
                }

                String turnaroundType = null;
                BigDecimal changeRate = null;

                // 적자 → 흑자 전환 (단, 흑자 전환 후 30억원 이상)
                if (previousNetIncome.compareTo(BigDecimal.ZERO) < 0 &&
                    currentNetIncome.compareTo(BigDecimal.ZERO) > 0) {
                    turnaroundType = "LOSS_TO_PROFIT";
                    changeRate = new BigDecimal("999.99"); // 흑자전환 특별 표기
                }
                // 흑자 → 흑자 (이익 증가 50% 이상)
                else if (previousNetIncome.compareTo(BigDecimal.ZERO) > 0 &&
                         currentNetIncome.compareTo(BigDecimal.ZERO) > 0 &&
                         currentNetIncome.compareTo(previousNetIncome) > 0) {

                    changeRate = currentNetIncome.subtract(previousNetIncome)
                            .divide(previousNetIncome.abs(), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));

                    if (changeRate.compareTo(new BigDecimal("50")) >= 0) {
                        turnaroundType = "PROFIT_GROWTH";
                    }
                }

                if (turnaroundType != null) {
                    results.add(ScreenerResultDto.builder()
                            .stockCode(current.getStockCode())
                            .stockName(current.getStockName())
                            .market(current.getMarket())
                            .sector(current.getSector())
                            .currentPrice(current.getCurrentPrice())
                            .marketCap(current.getMarketCap())
                            .per(current.getPer())
                            .pbr(current.getPbr())
                            .roe(current.getRoe())
                            .operatingMargin(current.getOperatingMargin())
                            .netMargin(current.getNetMargin())
                            .eps(current.getEps())
                            .epsGrowth(current.getEpsGrowth())
                            .peg(current.getPeg())
                            .turnaroundType(turnaroundType)
                            .previousNetIncome(previousNetIncome)
                            .currentNetIncome(currentNetIncome)
                            .netIncomeChangeRate(changeRate)
                            .revenueGrowth(current.getRevenueGrowth())
                            .profitGrowth(current.getProfitGrowth())
                            .build());
                }
            }

            log.info("분기 비교 기반 턴어라운드 결과: {}건", results.size());
        }

        // 분기 비교 결과가 없으면 profitGrowth 기반으로 대체
        if (results.isEmpty()) {
            log.info("분기 비교 데이터가 없어 profitGrowth 기반으로 턴어라운드 종목을 조회합니다.");
            results = findTurnaroundByProfitGrowth(limit);
        } else {
            // 적자→흑자 전환 우선, 그 다음 변화율 높은 순으로 정렬
            results.sort((a, b) -> {
                if ("LOSS_TO_PROFIT".equals(a.getTurnaroundType()) && !"LOSS_TO_PROFIT".equals(b.getTurnaroundType())) {
                    return -1;
                }
                if (!"LOSS_TO_PROFIT".equals(a.getTurnaroundType()) && "LOSS_TO_PROFIT".equals(b.getTurnaroundType())) {
                    return 1;
                }
                return b.getNetIncomeChangeRate().compareTo(a.getNetIncomeChangeRate());
            });

            if (limit != null && limit > 0) {
                results = results.stream().limit(limit).collect(Collectors.toList());
            }
        }

        log.info("턴어라운드 스크리닝 완료 - 결과 {}건", results.size());
        return results;
    }

    /**
     * profitGrowth 기반 턴어라운드 종목 조회
     * - 분기 비교 데이터가 없을 때 사용
     * - profitGrowth가 높은 종목 = 실적 개선 종목으로 간주
     * - 순이익 30억원 이상 필터 적용 (잡주 제외)
     */
    private List<ScreenerResultDto> findTurnaroundByProfitGrowth(Integer limit) {
        // 성장률 데이터가 있는 최신 데이터 조회
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findStocksWithGrowthData();
        log.info("성장률 데이터 있는 종목: {}건", allStocks.size());

        // profitGrowth가 50% 이상이고 순이익 30억원 이상인 종목 조회
        List<ScreenerResultDto> results = allStocks.stream()
                .filter(s -> s.getProfitGrowth() != null && s.getProfitGrowth().compareTo(new BigDecimal("50")) >= 0)
                .filter(s -> s.getNetIncome() != null && s.getNetIncome().compareTo(MIN_NET_INCOME) >= 0)
                .sorted((a, b) -> b.getProfitGrowth().compareTo(a.getProfitGrowth()))
                .map(stock -> ScreenerResultDto.builder()
                        .stockCode(stock.getStockCode())
                        .stockName(stock.getStockName())
                        .market(stock.getMarket())
                        .sector(stock.getSector())
                        .currentPrice(stock.getCurrentPrice())
                        .marketCap(stock.getMarketCap())
                        .per(stock.getPer())
                        .pbr(stock.getPbr())
                        .roe(stock.getRoe())
                        .operatingMargin(stock.getOperatingMargin())
                        .netMargin(stock.getNetMargin())
                        .eps(stock.getEps())
                        .epsGrowth(stock.getEpsGrowth())
                        .peg(stock.getPeg())
                        .turnaroundType("PROFIT_GROWTH")
                        .netIncomeChangeRate(stock.getProfitGrowth())
                        .revenueGrowth(stock.getRevenueGrowth())
                        .profitGrowth(stock.getProfitGrowth())
                        .build())
                .collect(Collectors.toList());

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        return results;
    }

    /**
     * 스크리너 요약 정보
     * - 각 스크리너의 상위 종목 요약
     */
    public Map<String, Object> getScreenerSummary() {
        Map<String, Object> summary = new HashMap<>();

        // 마법의 공식 Top 5
        List<ScreenerResultDto> magicFormula = getMagicFormulaStocks(5, null);
        summary.put("magicFormula", magicFormula);
        summary.put("magicFormulaCount", magicFormula.size());

        // PEG 스크리너 Top 5
        List<ScreenerResultDto> lowPeg = getLowPegStocks(new BigDecimal("1.0"), new BigDecimal("10"), 5);
        summary.put("lowPeg", lowPeg);
        summary.put("lowPegCount", lowPeg.size());

        // 턴어라운드 Top 5
        List<ScreenerResultDto> turnaround = getTurnaroundStocks(5);
        summary.put("turnaround", turnaround);
        summary.put("turnaroundCount", turnaround.size());

        return summary;
    }

    // ========== 텔레그램 알림 연동 메서드 ==========

    /**
     * 마법의 공식 상위 종목 알림 발송
     * - 상위 N개 종목을 텔레그램으로 알림
     * - 스케줄러나 외부에서 호출하여 사용
     *
     * @param topN 알림 발송할 종목 수 (기본 3)
     * @return 알림 발송된 종목 수
     */
    public int sendMagicFormulaAlerts(Integer topN) {
        if (topN == null) topN = 3;

        log.info("마법의 공식 상위 종목 알림 발송 시작 - top: {}", topN);

        List<ScreenerResultDto> topStocks = getMagicFormulaStocks(topN, null);

        int sentCount = 0;
        for (ScreenerResultDto stock : topStocks) {
            telegramNotificationService.sendMagicFormulaAlert(
                    stock.getStockName(),
                    stock.getStockCode(),
                    stock.getMagicFormulaRank(),
                    stock.getPer(),
                    stock.getRoe(),
                    stock.getOperatingMargin(),
                    stock.getCurrentPrice()
            );
            sentCount++;

            log.info("마법의 공식 알림 발송 - {} ({}), 순위: #{}",
                    stock.getStockName(), stock.getStockCode(), stock.getMagicFormulaRank());
        }

        log.info("마법의 공식 알림 발송 완료 - {}건", sentCount);
        return sentCount;
    }

    /**
     * 턴어라운드 종목 알림 발송
     * - 적자→흑자 전환 또는 이익 급증 종목 알림
     *
     * @param topN 알림 발송할 종목 수 (기본 3)
     * @return 알림 발송된 종목 수
     */
    public int sendTurnaroundAlerts(Integer topN) {
        if (topN == null) topN = 3;

        log.info("턴어라운드 종목 알림 발송 시작 - top: {}", topN);

        List<ScreenerResultDto> turnaroundStocks = getTurnaroundStocks(topN);

        int sentCount = 0;
        for (ScreenerResultDto stock : turnaroundStocks) {
            telegramNotificationService.sendTurnaroundAlert(
                    stock.getStockName(),
                    stock.getStockCode(),
                    stock.getTurnaroundType(),
                    stock.getNetIncomeChangeRate(),
                    stock.getCurrentPrice()
            );
            sentCount++;

            log.info("턴어라운드 알림 발송 - {} ({}), 유형: {}",
                    stock.getStockName(), stock.getStockCode(), stock.getTurnaroundType());
        }

        log.info("턴어라운드 알림 발송 완료 - {}건", sentCount);
        return sentCount;
    }
}
