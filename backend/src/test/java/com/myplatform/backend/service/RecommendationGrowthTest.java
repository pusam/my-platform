package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 성장 점수 산식(computeGrowthScoreParts) — scoreGrowth(종합추천 factor)와 성장주 TOP10 공용 출처.
 * 매출(0~7)+이익(0~8)+PEG(0~5). 음수 성장=0, PEG 낮을수록 가점.
 */
class RecommendationGrowthTest {

    private static int[] parts(Double rev, Double profit, Double peg) {
        return RecommendationService.computeGrowthScoreParts(
                rev == null ? null : BigDecimal.valueOf(rev),
                profit == null ? null : BigDecimal.valueOf(profit),
                peg == null ? null : BigDecimal.valueOf(peg));
    }

    @Nested
    @DisplayName("매출 성장률 (0~7)")
    class Revenue {
        @Test void t30() { assertThat(parts(30.0, null, null)[0]).isEqualTo(7); }
        @Test void t20() { assertThat(parts(20.0, null, null)[0]).isEqualTo(5); }
        @Test void t10() { assertThat(parts(10.0, null, null)[0]).isEqualTo(3); }
        @Test void t0() { assertThat(parts(5.0, null, null)[0]).isEqualTo(1); }
        @Test @DisplayName("역성장 → 0") void negative() { assertThat(parts(-5.0, null, null)[0]).isZero(); }
        @Test void nullRev() { assertThat(parts(null, null, null)[0]).isZero(); }
    }

    @Nested
    @DisplayName("이익 성장률 (0~8)")
    class Profit {
        @Test void t50() { assertThat(parts(null, 50.0, null)[1]).isEqualTo(8); }
        @Test void t30() { assertThat(parts(null, 30.0, null)[1]).isEqualTo(6); }
        @Test void t15() { assertThat(parts(null, 15.0, null)[1]).isEqualTo(4); }
        @Test void t0() { assertThat(parts(null, 0.0, null)[1]).isEqualTo(2); }
        @Test @DisplayName("적자전환 → 0") void negative() { assertThat(parts(null, -20.0, null)[1]).isZero(); }
    }

    @Nested
    @DisplayName("PEG (0~5) — 낮을수록 가점")
    class Peg {
        @Test void t07() { assertThat(parts(null, null, 0.7)[2]).isEqualTo(5); }
        @Test void t10() { assertThat(parts(null, null, 1.0)[2]).isEqualTo(4); }
        @Test void t15() { assertThat(parts(null, null, 1.5)[2]).isEqualTo(2); }
        @Test void t20() { assertThat(parts(null, null, 2.0)[2]).isEqualTo(1); }
        @Test @DisplayName("PEG 3.0 → 0(고평가)") void high() { assertThat(parts(null, null, 3.0)[2]).isZero(); }
    }

    @Test
    @DisplayName("합산 만점 — 매출30+이익50+PEG0.7 = 7+8+5 = 20")
    void max() {
        int[] p = parts(30.0, 50.0, 0.7);
        assertThat(p[0] + p[1] + p[2]).isEqualTo(20);
    }

    @Test
    @DisplayName("태그 — 고성장/이익급증/저평가성장")
    void tags() {
        assertThat(RecommendationService.growthTags(
                BigDecimal.valueOf(30), BigDecimal.valueOf(50), BigDecimal.valueOf(0.7)))
                .containsExactly("매출고성장", "이익급증", "저평가성장(PEG<1)");
    }
}
