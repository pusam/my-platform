package com.myplatform.backend.service;

import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 퀀트 스크리너 서비스
 * - 마법의 공식 (Magic Formula) 스크리닝
 * - PEG 기반 저평가 성장주 스크리닝
 * - 분기 실적 턴어라운드 스크리닝
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuantScreenerService {

    private final StockFinancialDataRepository stockFinancialDataRepository;

    /**
     * 마법의 공식 스크리너
     * - (영업이익률 순위 + ROE 순위) 합산으로 종합 순위 계산
     * - 저PER 필터 적용
     */
    public List<ScreenerResultDto> getMagicFormulaStocks(Integer limit, BigDecimal minMarketCap) {
        log.info("마법의 공식 스크리닝 시작 - limit: {}, minMarketCap: {}", limit, minMarketCap);

        // 1. 조건에 맞는 종목 조회
        List<StockFinancialData> stocks = stockFinancialDataRepository.findForMagicFormula(minMarketCap);

        if (stocks.isEmpty()) {
            log.info("마법의 공식 조건에 맞는 종목이 없습니다.");
            return Collections.emptyList();
        }

        // 2. 영업이익률 순위 계산 (높을수록 좋음 = 낮은 순위)
        List<StockFinancialData> byOperatingMargin = stocks.stream()
                .sorted(Comparator.comparing(StockFinancialData::getOperatingMargin).reversed())
                .collect(Collectors.toList());
        Map<String, Integer> operatingMarginRanks = new HashMap<>();
        for (int i = 0; i < byOperatingMargin.size(); i++) {
            operatingMarginRanks.put(byOperatingMargin.get(i).getStockCode(), i + 1);
        }

        // 3. ROE 순위 계산 (높을수록 좋음 = 낮은 순위)
        List<StockFinancialData> byRoe = stocks.stream()
                .sorted(Comparator.comparing(StockFinancialData::getRoe).reversed())
                .collect(Collectors.toList());
        Map<String, Integer> roeRanks = new HashMap<>();
        for (int i = 0; i < byRoe.size(); i++) {
            roeRanks.put(byRoe.get(i).getStockCode(), i + 1);
        }

        // 4. PER 순위 계산 (낮을수록 좋음 = 낮은 순위)
        List<StockFinancialData> byPer = stocks.stream()
                .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(StockFinancialData::getPer))
                .collect(Collectors.toList());
        Map<String, Integer> perRanks = new HashMap<>();
        for (int i = 0; i < byPer.size(); i++) {
            perRanks.put(byPer.get(i).getStockCode(), i + 1);
        }

        // 5. 종합 순위 계산 (영업이익률 순위 + ROE 순위 + PER 순위, 낮을수록 좋음)
        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    Integer opMarginRank = operatingMarginRanks.getOrDefault(stock.getStockCode(), stocks.size());
                    Integer roeRank = roeRanks.getOrDefault(stock.getStockCode(), stocks.size());
                    Integer perRank = perRanks.getOrDefault(stock.getStockCode(), stocks.size());

                    // 종합 점수 (낮을수록 좋음)
                    int totalRank = opMarginRank + roeRank + perRank;

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
                            .magicFormulaScore(BigDecimal.valueOf(totalRank))
                            .operatingMarginRank(opMarginRank)
                            .roeRank(roeRank)
                            .perRank(perRank)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                .sorted(Comparator.comparing(ScreenerResultDto::getMagicFormulaScore))
                .collect(Collectors.toList());

        // 6. 종합 순위 부여
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setMagicFormulaRank(i + 1);
        }

        // 7. limit 적용
        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("마법의 공식 스크리닝 완료 - 결과 {}건", results.size());
        return results;
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
     */
    public List<ScreenerResultDto> getTurnaroundStocks(Integer limit) {
        log.info("턴어라운드 스크리닝 시작 - limit: {}", limit);

        List<ScreenerResultDto> results = new ArrayList<>();

        // 모든 종목 코드 조회
        List<String> stockCodes = stockFinancialDataRepository.findAllStockCodes();

        for (String stockCode : stockCodes) {
            List<StockFinancialData> historicalData = stockFinancialDataRepository
                    .findByStockCodeOrderByReportDateDesc(stockCode);

            if (historicalData.size() < 2) {
                continue; // 비교할 분기 데이터가 없음
            }

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
                // 변화율: 흑자전환은 특별 표기
                changeRate = new BigDecimal("999.99");
            }
            // 흑자 → 흑자 (이익 증가)
            else if (previousNetIncome.compareTo(BigDecimal.ZERO) > 0 &&
                     currentNetIncome.compareTo(BigDecimal.ZERO) > 0 &&
                     currentNetIncome.compareTo(previousNetIncome) > 0) {
                // 순이익 50% 이상 증가한 경우만
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
            // LOSS_TO_PROFIT가 우선
            if ("LOSS_TO_PROFIT".equals(a.getTurnaroundType()) && !"LOSS_TO_PROFIT".equals(b.getTurnaroundType())) {
                return -1;
            }
            if (!"LOSS_TO_PROFIT".equals(a.getTurnaroundType()) && "LOSS_TO_PROFIT".equals(b.getTurnaroundType())) {
                return 1;
            }
            // 같은 타입 내에서는 변화율로 정렬
            return b.getNetIncomeChangeRate().compareTo(a.getNetIncomeChangeRate());
        });

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("턴어라운드 스크리닝 완료 - 결과 {}건", results.size());
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
