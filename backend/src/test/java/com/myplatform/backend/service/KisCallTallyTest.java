package com.myplatform.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** KIS 호출 시도·실패 일별 집계 — 관제실 규칙 ⑫⑬ 의 입력. */
class KisCallTallyTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 3);

    @BeforeEach
    void reset() {
        KisCallTally.reset();
    }

    @Test
    @DisplayName("시도·실패를 종류·날짜별로 센다 — 기록 없는 날은 0/0")
    void countsPerKindAndDay() {
        KisCallTally.attempt(KisCallTally.MINUTE_CHART, D1);
        KisCallTally.attempt(KisCallTally.MINUTE_CHART, D1);
        KisCallTally.failure(KisCallTally.MINUTE_CHART, D1);
        KisCallTally.attempt(KisCallTally.BALANCE, D1);

        assertThat(KisCallTally.of(KisCallTally.MINUTE_CHART, D1)).isEqualTo(new KisCallTally.Counts(2, 1));
        assertThat(KisCallTally.of(KisCallTally.BALANCE, D1)).isEqualTo(new KisCallTally.Counts(1, 0));
        assertThat(KisCallTally.of(KisCallTally.MINUTE_CHART, D2)).isEqualTo(KisCallTally.Counts.ZERO);
    }

    @Test
    @DisplayName("오늘·어제만 남긴다 — 이틀 전 키는 새 기록 시점에 정리된다")
    void keepsOnlyTwoDays() {
        KisCallTally.attempt(KisCallTally.BALANCE, D1.minusDays(2));
        KisCallTally.attempt(KisCallTally.BALANCE, D1.minusDays(1));
        KisCallTally.attempt(KisCallTally.BALANCE, D1);

        assertThat(KisCallTally.of(KisCallTally.BALANCE, D1.minusDays(2))).isEqualTo(KisCallTally.Counts.ZERO);
        assertThat(KisCallTally.of(KisCallTally.BALANCE, D1.minusDays(1)).attempts()).isEqualTo(1);
        assertThat(KisCallTally.of(KisCallTally.BALANCE, D1).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("null 종류/날짜는 무시 — 집계기가 호출 경로를 죽이면 안 된다")
    void nullsAreIgnored() {
        KisCallTally.attempt(null, D1);
        KisCallTally.failure(KisCallTally.BALANCE, null);
        assertThat(KisCallTally.of(null, D1)).isEqualTo(KisCallTally.Counts.ZERO);
        assertThat(KisCallTally.of(KisCallTally.BALANCE, D1)).isEqualTo(KisCallTally.Counts.ZERO);
    }
}
