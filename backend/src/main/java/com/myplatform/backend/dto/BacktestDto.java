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
        /** 실제 표본이 시작하는 시각(집계된 첫 추천) — 없으면 null. 요청 기간보다 짧을 수 있다(2026-10-03). */
        private java.time.LocalDateTime sampleFrom;
        /** 추천 스냅샷 보존 기간(일) — 이보다 긴 기간을 요청해도 그 앞은 이미 지워져 없다. */
        private int retentionDays;
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
        private BigDecimal mdd;             // 전략별 MDD (%)
        private BigDecimal recentHitRate;    // 최근 20거래일 적중률
        private BigDecimal recentAvgReturn;  // 최근 20거래일 평균 수익률
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
        private BigDecimal grossReturn;     // 총수익률 (%, 비용 차감 전)
        private BigDecimal returnRate;      // 순수익률 (%, 비용 차감 후)
        private BigDecimal tradingCost;     // 거래비용 (%, 수수료+세금+슬리피지)
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
        private BigDecimal mdd;          // 최대낙폭 (%)
        private BigDecimal sharpeRatio;  // 간이 샤프비율
    }
}
