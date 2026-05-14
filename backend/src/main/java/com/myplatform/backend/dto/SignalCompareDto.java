package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시그널 적중률 cutoff 전후 비교 응답 — phase 32.
 *
 * <p>용도: phase 31 추격매수 방지 산식이 실제로 alpha/hit-rate 를 개선했는지 운영 데이터로 검증.
 * 운영 관찰 기반 튜닝이 누적되면 over-fit 위험이 있으므로 cutoff 시점 기준 [windowDays 전 ~
 * cutoff) vs [cutoff ~ windowDays 후) 통계를 자동 비교한다.
 *
 * <p>API: {@code GET /api/signal-outcomes/compare?signalType=STRONG_BUY&cutoff=2026-05-14&windowDays=30}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalCompareDto {
    /** 비교 기준일 (이 날짜부터 "after" 윈도우 시작). */
    private LocalDate cutoff;
    /** 각 윈도우 일수. */
    private int windowDays;
    /** 특정 시그널만 보고 싶을 때 사용. null 이면 전체. */
    private String signalTypeFilter;

    /** cutoff 이전 [cutoff - windowDays, cutoff) 구간 통계. */
    private Window before;
    /** cutoff 이후 [cutoff, cutoff + windowDays) 구간 통계. */
    private Window after;
    /** signal_type 별 before vs after delta. */
    private List<Delta> deltas;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Window {
        /** 윈도우 시작일 (inclusive). */
        private LocalDate from;
        /** 윈도우 종료일 (exclusive). */
        private LocalDate to;
        /** signal_type 별 stat. */
        private List<Stat> stats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stat {
        private String signalType;
        private long totalSignals;
        private long hitCount;
        /** 0.00 ~ 100.00 (%). */
        private BigDecimal hitRate;
        /** 3거래일 절대 변동률 평균 (%). */
        private BigDecimal avgPctChange;
        /** 3거래일 KOSPI 대비 alpha 평균 (%). null = BM 데이터 없음. */
        private BigDecimal avgAlpha;
        /** 보유 기간 MFE 평균 (%, 양수). */
        private BigDecimal avgMfe;
        /** 보유 기간 MAE 평균 (%, 음수). */
        private BigDecimal avgMae;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delta {
        private String signalType;
        /** after.hitRate - before.hitRate (%포인트). */
        private BigDecimal hitRateChange;
        /** after.avgAlpha - before.avgAlpha (%포인트). */
        private BigDecimal avgAlphaChange;
        /** after.avgPctChange - before.avgPctChange (%포인트). */
        private BigDecimal avgPctChange;
        /** after.avgMfe - before.avgMfe. */
        private BigDecimal avgMfeChange;
        /** after.avgMae - before.avgMae. (값이 커지면 = 덜 손실, 양수가 좋음) */
        private BigDecimal avgMaeChange;
        /** before/after 둘 다 표본이 ≥ 3 인지. UI 에서 "표본 부족" 경고용. */
        private boolean sufficientSample;
    }
}
