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

    public BacktestDto.PerformanceResponse getPerformance(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        List<BacktestDto.StrategyPerformance> strategyResults = new ArrayList<>();
        int totalPicks = 0, totalWins = 0;
        BigDecimal totalReturn = BigDecimal.ZERO;

        for (StrategyType type : StrategyType.values()) {
            BacktestDto.StrategyPerformance perf = analyzeStrategy(type, since);
            strategyResults.add(perf);
            totalPicks += perf.getTotalPicks();
            totalWins += perf.getWinCount();
            if (perf.getAvgReturn() != null) {
                totalReturn = totalReturn.add(perf.getAvgReturn().multiply(BigDecimal.valueOf(perf.getTotalPicks())));
            }
        }

        BacktestDto.OverallStats overall = BacktestDto.OverallStats.builder()
                .totalPicks(totalPicks)
                .winCount(totalWins)
                .hitRate(totalPicks > 0
                        ? BigDecimal.valueOf(totalWins).divide(BigDecimal.valueOf(totalPicks), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .avgReturn(totalPicks > 0
                        ? totalReturn.divide(BigDecimal.valueOf(totalPicks), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .build();

        return BacktestDto.PerformanceResponse.builder()
                .days(days)
                .strategies(strategyResults)
                .overall(overall)
                .build();
    }

    private BacktestDto.StrategyPerformance analyzeStrategy(StrategyType type, LocalDateTime since) {
        // 기간 내 고유 추천 종목 추출 (종목별 첫 추천 기준)
        List<AiStrategySnapshot> allSnapshots = snapshotRepository
                .findByStrategyTypeAndCreatedAtAfterOrderByCreatedAtAsc(type, since);

        // 종목별 첫 추천만 추출 (중복 제거)
        Map<String, AiStrategySnapshot> firstRecommendations = new LinkedHashMap<>();
        for (AiStrategySnapshot snap : allSnapshots) {
            if (snap.getRankNum() != null && snap.getRankNum() <= 3
                    && !firstRecommendations.containsKey(snap.getStockCode())) {
                firstRecommendations.put(snap.getStockCode(), snap);
            }
        }

        List<BacktestDto.PickDetail> picks = new ArrayList<>();
        int winCount = 0;
        BigDecimal totalReturn = BigDecimal.ZERO;
        BigDecimal bestReturn = null;
        String bestStock = null;
        BigDecimal worstReturn = null;
        String worstStock = null;

        for (AiStrategySnapshot snap : firstRecommendations.values()) {
            BigDecimal recommendPrice = snap.getCurrentPrice();
            if (recommendPrice == null || recommendPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

            // 현재가 조회
            BigDecimal currentPrice = null;
            try {
                StockPriceDto priceDto = stockPriceService.getStockPrice(snap.getStockCode());
                if (priceDto != null) currentPrice = priceDto.getCurrentPrice();
            } catch (Exception e) {
                log.debug("백테스트 현재가 조회 실패: {}", snap.getStockCode());
            }

            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal returnRate = currentPrice.subtract(recommendPrice)
                    .divide(recommendPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

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
                    .returnRate(returnRate)
                    .recommendedAt(snap.getCreatedAt())
                    .rankNum(snap.getRankNum())
                    .build());
        }

        // 수익률 기준 정렬
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
                .picks(picks.size() > 10 ? picks.subList(0, 10) : picks) // 상위 10개만
                .build();
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
