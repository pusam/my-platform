package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낙폭과대 반등 점수(computeOversoldScoreParts).
 * 게이트: RSI ≤ 40 AND MA20 대비 −5% 이하. RSI과매도(0~8)+낙폭(0~7)+반등조짐(0~5).
 */
class RecommendationOversoldTest {

    private static int total(int[] p) { return p[0] + p[1] + p[2]; }

    @Nested
    @DisplayName("게이트 — 미달이면 0")
    class Gate {
        @Test @DisplayName("RSI 45(>40) → 0")
        void rsiHigh() { assertThat(total(RecommendationService.computeOversoldScoreParts(45.0, -20.0, 2.0, 1.0))).isZero(); }

        @Test @DisplayName("이격도 -3%(>-5) → 0 (낙폭 부족)")
        void notDropped() { assertThat(total(RecommendationService.computeOversoldScoreParts(28.0, -3.0, 2.0, 1.0))).isZero(); }

        @Test @DisplayName("RSI null → 0")
        void rsiNull() { assertThat(total(RecommendationService.computeOversoldScoreParts(null, -20.0, null, null))).isZero(); }

        @Test @DisplayName("MA20 위(이격도 양수) → 0")
        void aboveMa() { assertThat(total(RecommendationService.computeOversoldScoreParts(30.0, 5.0, null, null))).isZero(); }
    }

    @Nested
    @DisplayName("RSI 과매도 단계 (0~8)")
    class Rsi {
        @Test void t25() { assertThat(RecommendationService.computeOversoldScoreParts(25.0, -6.0, null, null)[0]).isEqualTo(8); }
        @Test void t30() { assertThat(RecommendationService.computeOversoldScoreParts(30.0, -6.0, null, null)[0]).isEqualTo(6); }
        @Test void t35() { assertThat(RecommendationService.computeOversoldScoreParts(35.0, -6.0, null, null)[0]).isEqualTo(4); }
        @Test void t40() { assertThat(RecommendationService.computeOversoldScoreParts(40.0, -6.0, null, null)[0]).isEqualTo(2); }
    }

    @Nested
    @DisplayName("낙폭/이격도 단계 (0~7)")
    class Drop {
        @Test void d25() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -25.0, null, null)[1]).isEqualTo(7); }
        @Test void d18() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -18.0, null, null)[1]).isEqualTo(5); }
        @Test void d12() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -12.0, null, null)[1]).isEqualTo(3); }
        @Test void d5() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -5.0, null, null)[1]).isEqualTo(1); }
    }

    @Nested
    @DisplayName("반등 조짐 (0~5) — 양봉+3, 거래량x1.5+2")
    class Rebound {
        @Test @DisplayName("양봉만 → 3")
        void up() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -10.0, 1.0, 2.0)[2]).isEqualTo(3); }
        @Test @DisplayName("거래량만 → 2")
        void vol() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -10.0, 1.6, -1.0)[2]).isEqualTo(2); }
        @Test @DisplayName("양봉+거래량 → 5")
        void both() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -10.0, 2.0, 1.5)[2]).isEqualTo(5); }
        @Test @DisplayName("둘 다 없음 → 0 (아직 안 돌아섬)")
        void none() { assertThat(RecommendationService.computeOversoldScoreParts(28.0, -10.0, 1.0, -2.0)[2]).isZero(); }
    }

    @Test
    @DisplayName("만점 — RSI25 + 낙폭-25% + 반등 = 8+7+5 = 20")
    void max() {
        assertThat(total(RecommendationService.computeOversoldScoreParts(25.0, -25.0, 2.0, 1.0))).isEqualTo(20);
    }

    @Test
    @DisplayName("태그 — 과매도/이격도/반등시작/거래량급증")
    void tags() {
        assertThat(RecommendationService.oversoldTags(28.0, -15.0, 1.8, 2.0))
                .containsExactly("RSI28과매도", "이격도-15%", "반등시작", "거래량급증");
    }
}
