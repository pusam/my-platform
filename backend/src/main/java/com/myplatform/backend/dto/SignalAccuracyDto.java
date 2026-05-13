package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 시그널 적중률 통계 응답.
 *
 * 사용자가 화면에서 "지난 N일간 SURGE_HOT 신호의 적중률은 62%" 같이 확인.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalAccuracyDto {
    private int daysWindow;
    private List<SignalStat> stats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignalStat {
        private String signalType;
        private long totalSignals;
        private long hitCount;
        /** 0.0 ~ 100.0 % */
        private BigDecimal hitRate;
        /** 평균 3일 변동률 % */
        private BigDecimal avgPctChange;
    }
}
