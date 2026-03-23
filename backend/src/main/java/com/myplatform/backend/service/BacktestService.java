package com.myplatform.backend.service;

import com.myplatform.backend.dto.BacktestDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final AiStrategySnapshotRepository snapshotRepository;
    private final StockPriceService stockPriceService;

    // 거래 비용 상수
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.00015");  // 매수·매도 각 0.015%
    private static final BigDecimal TAX_RATE = new BigDecimal("0.0018");          // 매도 세금 0.18%

    // 전략별 슬리피지 (스냅샷 가격 vs 실제 체결가 괴리)
    private static final Map<StrategyType, BigDecimal> SLIPPAGE_RATE = Map.of(
            StrategyType.SCALPING, new BigDecimal("0.002"),     // 0.2% — 단타, 변동성 크고 체결 급함
            StrategyType.SWING, new BigDecimal("0.001"),        // 0.1%
            StrategyType.TURNAROUND, new BigDecimal("0.001"),   // 0.1%
            StrategyType.VALUE, new BigDecimal("0.0005")        // 0.05% — 장기, 지정가 여유
    );

    /**
     * 왕복 거래 비용 계산 (수수료 + 세금 + 슬리피지), 수익률(%) 단위로 반환
     */
    private BigDecimal calculateTradingCost(StrategyType strategyType) {
        BigDecimal slippage = SLIPPAGE_RATE.getOrDefault(strategyType, new BigDecimal("0.001"));
        // 왕복 수수료(매수+매도) + 매도 세금 + 슬리피지
        BigDecimal totalCostRate = COMMISSION_RATE.multiply(BigDecimal.valueOf(2))
                .add(TAX_RATE)
                .add(slippage);
        // 비율 → 퍼센트
        return totalCostRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    public BacktestDto.PerformanceResponse getPerformance(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        // 전체 전략에서 추천 종목 수집
        Map<StrategyType, Map<String, AiStrategySnapshot>> allFirstRecommendations = new LinkedHashMap<>();
        Set<String> allStockCodes = new HashSet<>();

        for (StrategyType type : StrategyType.values()) {
            List<AiStrategySnapshot> snapshots = snapshotRepository
                    .findByStrategyTypeAndCreatedAtAfterOrderByCreatedAtAsc(type, since);

            Map<String, AiStrategySnapshot> firstRecs = new LinkedHashMap<>();
            for (AiStrategySnapshot snap : snapshots) {
                if (snap.getRankNum() != null && snap.getRankNum() <= 3
                        && !firstRecs.containsKey(snap.getStockCode())) {
                    firstRecs.put(snap.getStockCode(), snap);
                    allStockCodes.add(snap.getStockCode());
                }
            }
            allFirstRecommendations.put(type, firstRecs);
        }

        // 배치로 현재가 조회 (N+1 → 1회)
        Map<String, StockPriceDto> priceMap = new HashMap<>();
        if (!allStockCodes.isEmpty()) {
            try {
                priceMap = stockPriceService.getStockPrices(new ArrayList<>(allStockCodes));
                log.info("백테스트 배치 시세 조회 완료: {}건", priceMap.size());
            } catch (Exception e) {
                log.warn("백테스트 배치 시세 조회 실패: {}", e.getMessage());
            }
        }

        // 전략별 성과 분석
        List<BacktestDto.StrategyPerformance> strategyResults = new ArrayList<>();
        List<BacktestDto.PickDetail> allPicks = new ArrayList<>();
        int totalPicks = 0, totalWins = 0;
        BigDecimal totalReturn = BigDecimal.ZERO;

        for (StrategyType type : StrategyType.values()) {
            BacktestDto.StrategyPerformance perf = analyzeStrategy(
                    type, allFirstRecommendations.get(type), priceMap);
            strategyResults.add(perf);
            totalPicks += perf.getTotalPicks();
            totalWins += perf.getWinCount();
            if (perf.getAvgReturn() != null) {
                totalReturn = totalReturn.add(perf.getAvgReturn().multiply(BigDecimal.valueOf(perf.getTotalPicks())));
            }
            if (perf.getPicks() != null) {
                allPicks.addAll(perf.getPicks());
            }
        }

        BigDecimal overallAvgReturn = totalPicks > 0
                ? totalReturn.divide(BigDecimal.valueOf(totalPicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 전체 종목 시간순 정렬 후 MDD 계산
        allPicks.sort(Comparator.comparing(BacktestDto.PickDetail::getRecommendedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<BigDecimal> allReturns = allPicks.stream()
                .map(BacktestDto.PickDetail::getReturnRate)
                .collect(Collectors.toList());

        BacktestDto.OverallStats overall = BacktestDto.OverallStats.builder()
                .totalPicks(totalPicks)
                .winCount(totalWins)
                .hitRate(totalPicks > 0
                        ? BigDecimal.valueOf(totalWins).divide(BigDecimal.valueOf(totalPicks), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .avgReturn(overallAvgReturn)
                .mdd(calculateMdd(allReturns))
                .sharpeRatio(calculateSharpe(allReturns, overallAvgReturn))
                .build();

        return BacktestDto.PerformanceResponse.builder()
                .days(days)
                .strategies(strategyResults)
                .overall(overall)
                .build();
    }

    private BacktestDto.StrategyPerformance analyzeStrategy(
            StrategyType type,
            Map<String, AiStrategySnapshot> firstRecommendations,
            Map<String, StockPriceDto> priceMap) {

        List<BacktestDto.PickDetail> picks = new ArrayList<>();
        int winCount = 0;
        BigDecimal totalReturn = BigDecimal.ZERO;
        BigDecimal bestReturn = null;
        String bestStock = null;
        BigDecimal worstReturn = null;
        String worstStock = null;

        BigDecimal tradingCost = calculateTradingCost(type);

        for (AiStrategySnapshot snap : firstRecommendations.values()) {
            BigDecimal recommendPrice = snap.getCurrentPrice();
            if (recommendPrice == null || recommendPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

            StockPriceDto priceDto = priceMap.get(snap.getStockCode());
            BigDecimal currentPrice = priceDto != null ? priceDto.getCurrentPrice() : null;
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal grossReturn = currentPrice.subtract(recommendPrice)
                    .divide(recommendPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

            // 순수익률 = 총수익률 - 거래비용(수수료+세금+슬리피지)
            BigDecimal returnRate = grossReturn.subtract(tradingCost);

            if (returnRate.compareTo(BigDecimal.ZERO) > 0) winCount++;
            totalReturn = totalReturn.add(returnRate);

            if (bestReturn == null || returnRate.compareTo(bestReturn) > 0) {
                bestReturn = returnRate;
                bestStock = snap.getStockName();
            }
            if (worstReturn == null || returnRate.compareTo(worstReturn) < 0) {
                worstReturn = returnRate;
                worstStock = snap.getStockName();
            }

            picks.add(BacktestDto.PickDetail.builder()
                    .stockCode(snap.getStockCode())
                    .stockName(snap.getStockName())
                    .recommendPrice(recommendPrice)
                    .currentPrice(currentPrice)
                    .grossReturn(grossReturn)
                    .returnRate(returnRate)
                    .tradingCost(tradingCost)
                    .recommendedAt(snap.getCreatedAt())
                    .rankNum(snap.getRankNum())
                    .build());
        }

        // 시간순 정렬하여 MDD 계산
        picks.sort(Comparator.comparing(BacktestDto.PickDetail::getRecommendedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<BigDecimal> returnRates = picks.stream()
                .map(BacktestDto.PickDetail::getReturnRate)
                .collect(Collectors.toList());
        BigDecimal strategyMdd = calculateMdd(returnRates);

        // 최근 20거래일 (약 30일) 롤링 지표
        LocalDateTime recentCutoff = LocalDateTime.now().minusDays(30);
        List<BacktestDto.PickDetail> recentPicks = picks.stream()
                .filter(p -> p.getRecommendedAt() != null && p.getRecommendedAt().isAfter(recentCutoff))
                .collect(Collectors.toList());

        BigDecimal recentHitRate = BigDecimal.ZERO;
        BigDecimal recentAvgReturn = BigDecimal.ZERO;
        if (!recentPicks.isEmpty()) {
            long recentWins = recentPicks.stream()
                    .filter(p -> p.getReturnRate().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            recentHitRate = BigDecimal.valueOf(recentWins)
                    .divide(BigDecimal.valueOf(recentPicks.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
            BigDecimal recentTotalReturn = recentPicks.stream()
                    .map(BacktestDto.PickDetail::getReturnRate)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            recentAvgReturn = recentTotalReturn
                    .divide(BigDecimal.valueOf(recentPicks.size()), 2, RoundingMode.HALF_UP);
        }

        // 수익률 내림차순 정렬 (표시용)
        picks.sort((a, b) -> b.getReturnRate().compareTo(a.getReturnRate()));

        int totalPicks = picks.size();
        BigDecimal hitRate = totalPicks > 0
                ? BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(totalPicks), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgReturn = totalPicks > 0
                ? totalReturn.divide(BigDecimal.valueOf(totalPicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return BacktestDto.StrategyPerformance.builder()
                .strategyType(type.name())
                .label(getLabel(type))
                .totalPicks(totalPicks)
                .winCount(winCount)
                .loseCount(totalPicks - winCount)
                .hitRate(hitRate)
                .avgReturn(avgReturn)
                .bestReturn(bestReturn)
                .bestStock(bestStock)
                .worstReturn(worstReturn)
                .worstStock(worstStock)
                .mdd(strategyMdd)
                .recentHitRate(recentHitRate)
                .recentAvgReturn(recentAvgReturn)
                .picks(picks.size() > 10 ? picks.subList(0, 10) : picks)
                .build();
    }

    /**
     * MDD (최대낙폭) 계산: 누적 수익률 기반
     * 시간순 정렬된 returnRates를 받아 peak 대비 최대 하락폭을 구한다.
     */
    private BigDecimal calculateMdd(List<BigDecimal> returnRates) {
        if (returnRates == null || returnRates.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BigDecimal ret : returnRates) {
            cumulative = cumulative.add(ret);
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
            }
            BigDecimal drawdown = peak.subtract(cumulative);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 간이 샤프비율 계산: avgReturn / stdDev(returnRates)
     * 무위험 수익률은 0으로 가정한다.
     */
    private BigDecimal calculateSharpe(List<BigDecimal> returnRates, BigDecimal avgReturn) {
        if (returnRates == null || returnRates.size() < 3 || avgReturn == null) {
            return BigDecimal.ZERO;
        }

        // 표준편차 계산
        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (BigDecimal ret : returnRates) {
            BigDecimal diff = ret.subtract(avgReturn);
            sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSquaredDiff.divide(
                BigDecimal.valueOf(returnRates.size() - 1), 10, RoundingMode.HALF_UP);

        double stdDev = Math.sqrt(variance.doubleValue());
        if (stdDev < 0.0001) {
            return BigDecimal.ZERO;
        }

        return avgReturn.divide(BigDecimal.valueOf(stdDev), 2, RoundingMode.HALF_UP);
    }

    private String getLabel(StrategyType type) {
        return switch (type) {
            case SCALPING -> "스캘핑";
            case SWING -> "스윙";
            case TURNAROUND -> "턴어라운드";
            case VALUE -> "가치투자";
        };
    }
}
