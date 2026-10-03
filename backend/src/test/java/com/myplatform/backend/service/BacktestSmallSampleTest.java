package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 트랙레코드 — 표본이 적으면 MDD·Sharpe 는 0 이 아니라 모름(2026-10-03).
 *
 * <p>재현: 표본 1~2건이면 MDD 0·Sharpe 0 이 내려가 '낙폭 없음·위험 대비 수익 0'처럼 읽혔다.
 */
class BacktestSmallSampleTest {

    @Test
    @DisplayName("재현: 표본 1건 MDD·2건 Sharpe 는 null")
    void smallSamplesAreUnknown() {
        assertThat(BacktestService.calculateMdd(List.of(new BigDecimal("2.0")))).isNull();
        assertThat(BacktestService.calculateSharpe(List.of(new BigDecimal("1"), new BigDecimal("2")), new BigDecimal("1.5"))).isNull();
    }

    @Test
    @DisplayName("편차가 0 이면 Sharpe 는 정의되지 않는다 — null")
    void zeroDeviationIsUnknown() {
        List<BigDecimal> same = List.of(new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"));
        assertThat(BacktestService.calculateSharpe(same, new BigDecimal("1"))).isNull();
    }

    @Test
    @DisplayName("충분한 표본은 종전대로 — 누적합 곡선의 고점 대비 하락")
    void normalSample() {
        assertThat(BacktestService.calculateMdd(List.of(new BigDecimal("3"), new BigDecimal("-5"), new BigDecimal("1"))))
                .isEqualByComparingTo("5.00");
    }
}
