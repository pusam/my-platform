package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시그널 적중률 일별 시계열 — phase 33.
 *
 * <p>용도: 프론트 그래프로 phase 변경 시점 전후 hit-rate/alpha 추이 시각화.
 * <p>API: {@code GET /api/signal-outcomes/timeseries?signalType=STRONG_BUY&days=60}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalTimeseriesDto {
    private int daysWindow;
    /** null/blank 이면 전체 시그널. */
    private String signalTypeFilter;
    private List<Point> points;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Point {
        private LocalDate date;
        private String signalType;
        private long totalSignals;
        private long hitCount;
        /** 0.00 ~ 100.00 (%). */
        private BigDecimal hitRate;
        /** 3거래일 절대 변동률 평균 (%). */
        private BigDecimal avgPctChange;
        /** 3거래일 KOSPI 대비 alpha 평균 (%). null = BM 데이터 없음. */
        private BigDecimal avgAlpha;
    }
}
