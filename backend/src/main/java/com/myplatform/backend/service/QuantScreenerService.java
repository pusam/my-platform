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

    /**
     * 마법의 공식 스크리너
     * - (영업이익률 순위 + ROE 순위 + PER 순위) 합산으로 종합 순위 계산
     *
     * [개선 2] 적자 기업 필터링
     * - PER <= 0 (적자 또는 비정상) 기업은 랭킹 산정 전에 제외
     * - 마법의 공식은 우량주를 찾는 것이므로 적자 기업은 대상이 아님
     */
    public List<ScreenerResultDto> getMagicFormulaStocks(Integer limit, BigDecimal minMarketCap) {
        log.info("마법의 공식 스크리닝 시작 - limit: {}, minMarketCap: {}", limit, minMarketCap);

        // 1. 조건에 맞는 종목 조회 (Repository에서 이미 PER > 0 필터 적용됨)
        List<StockFinancialData> allStocks = stockFinancialDataRepository.findForMagicFormula(minMarketCap);

        // 2. [개선] 적자 기업 확실히 제외 (PER > 0 재확인)
        List<StockFinancialData> stocks = allStocks.stream()
                .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                .filter(s -> s.getRoe() != null && s.getRoe().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        if (stocks.isEmpty()) {
            log.info("마법의 공식 조건에 맞는 종목이 없습니다.");
            return Collections.emptyList();
        }

        log.debug("마법의 공식 후보 종목 수: {} (필터링 전: {})", stocks.size(), allStocks.size());

        // 3. 각 지표별 순위 계산 (모두 양수인 종목만 대상)
        int totalStocks = stocks.size();

        // 영업이익률 순위 (높을수록 좋음 = 낮은 순위)
        Map<String, Integer> operatingMarginRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getOperatingMargin).reversed());

        // ROE 순위 (높을수록 좋음 = 낮은 순위)
        Map<String, Integer> roeRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getRoe).reversed());

        // PER 순위 (낮을수록 좋음 = 낮은 순위)
        Map<String, Integer> perRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getPer));

        // 4. 종합 순위 계산 (영업이익률 순위 + ROE 순위 + PER 순위, 낮을수록 좋음)
        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    Integer opMarginRank = operatingMarginRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer roeRank = roeRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer perRank = perRanks.getOrDefault(stock.getStockCode(), totalStocks);

                    int totalScore = opMarginRank + roeRank + perRank;

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
     * - PEG = PER / EPS성장률
     * - PEG < maxPeg (기본 1.0)인 저평가 성장주 발굴
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

        List<StockFinancialData> stocks = stockFinancialDataRepository.findLowPegStocks(maxPeg, minEpsGrowth);

        List<ScreenerResultDto> results = stocks.stream()
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
                        .revenueGrowth(stock.getRevenueGrowth())
                        .profitGrowth(stock.getProfitGrowth())
                        .build())
                .collect(Collectors.toList());

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("PEG 스크리닝 완료 - 결과 {}건", results.size());
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
     */
    public List<ScreenerResultDto> getTurnaroundStocks(Integer limit) {
        log.info("턴어라운드 스크리닝 시작 - limit: {}", limit);

        LocalDate minDate = LocalDate.now().minusMonths(12);
        List<StockFinancialData> allRecentData = stockFinancialDataRepository.findAllRecentData(minDate);

        if (allRecentData.isEmpty()) {
            log.info("최근 데이터가 없습니다.");
            return Collections.emptyList();
        }

        log.debug("턴어라운드 분석 대상 데이터: {}건", allRecentData.size());

        // [성능 최적화] 메모리에서 종목별로 그룹화 (날짜 내림차순 정렬 유지)
        Map<String, List<StockFinancialData>> stockDataMap = allRecentData.stream()
                .collect(Collectors.groupingBy(
                        StockFinancialData::getStockCode,
                        LinkedHashMap::new,  // 순서 유지
                        Collectors.toList()
                ));

        List<ScreenerResultDto> results = new ArrayList<>();

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

            String turnaroundType = null;
            BigDecimal changeRate = null;

            // 적자 → 흑자 전환
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

        log.info("턴어라운드 스크리닝 완료 - 결과 {}건 (분석 종목: {}개)", results.size(), stockDataMap.size());
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
}
