package com.myplatform.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-11: 꼬리 동결 길이 계산기 테스트.
 */
class StaleFeedDetectorTest {

    private static List<BigDecimal> of(String... v) {
        List<BigDecimal> l = new ArrayList<>();
        for (String s : v) l.add(new BigDecimal(s));
        return l;
    }

    @Test
    @DisplayName("전부 동일 → 전체 길이")
    void allEqual() {
        assertThat(StaleFeedDetector.trailingFrozenRun(of("70000", "70000", "70000"))).isEqualTo(3);
    }

    @Test
    @DisplayName("꼬리만 동일 → 꼬리 길이")
    void trailingOnly() {
        assertThat(StaleFeedDetector.trailingFrozenRun(of("70000", "71000", "71000"))).isEqualTo(2);
    }

    @Test
    @DisplayName("마지막만 다름 → 1")
    void lastDiffers() {
        assertThat(StaleFeedDetector.trailingFrozenRun(of("70000", "71000", "70000"))).isEqualTo(1);
    }

    @Test
    @DisplayName("단일 → 1, 빈 리스트/null → 0")
    void singleAndEmpty() {
        assertThat(StaleFeedDetector.trailingFrozenRun(of("70000"))).isEqualTo(1);
        assertThat(StaleFeedDetector.trailingFrozenRun(Collections.emptyList())).isZero();
        assertThat(StaleFeedDetector.trailingFrozenRun(null)).isZero();
    }

    @Test
    @DisplayName("값 비교는 스케일 무시 (70000 vs 70000.00 동일 취급)")
    void scaleInsensitive() {
        assertThat(StaleFeedDetector.trailingFrozenRun(of("70000", "70000.00"))).isEqualTo(2);
    }
}
