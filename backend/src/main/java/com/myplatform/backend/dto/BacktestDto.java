package com.myplatform.backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class BacktestDto {

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceResponse {
        private int days;
        private List<StrategyPerformance> strategies;
        private OverallStats overall;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrategyPerformance {
        private String strategyType;
        private String label;
        private int totalPicks;
        private int winCount;
        private int loseCount;
        private BigDecimal hitRate;       // 적중률 (%)
        private BigDecimal avgReturn;     // 평균 수익률 (%)
        private BigDecimal bestReturn;    // 최고 수익률
        private String bestStock;         // 최고 수익 종목
        private BigDecimal worstReturn;   // 최저 수익률
        private String worstStock;        // 최저 수익 종목
        private List<PickDetail> picks;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PickDetail {
        private String stockCode;
        private String stockName;
        private BigDecimal recommendPrice;  // 추천 시 가격
        private BigDecimal currentPrice;    // 현재가
        private BigDecimal returnRate;      // 수익률 (%)
        private LocalDateTime recommendedAt;
        private int rankNum;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverallStats {
        private int totalPicks;
        private int winCount;
        private BigDecimal hitRate;
        private BigDecimal avgReturn;
    }
}
