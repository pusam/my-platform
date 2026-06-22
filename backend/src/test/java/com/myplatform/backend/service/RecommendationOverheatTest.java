package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과열(추격매수) 페널티 강화 — 임계 바로 아래 과열주가 무페널티로 통과하던 문제 보완.
 *
 * <p>강화 전: RSI≥75 -5 / 볼린저 -3 / 5일≥20% -5 (임계 바로 아래는 0). RSI 72·5일 18% 같은
 * 과열주가 페널티 0으로 55점 컷 통과 → 발굴 상위 노출.
 * <p>강화 후: 단계별 차감 — RSI 70/75/80 → 3/5/8, 5일 15/20/30% → 3/5/8 (기존 임계의 차감폭은 유지).
 */
class RecommendationOverheatTest {

    @Nested
    @DisplayName("RSI 단계별 과열 차감 (70/75/80 → 3/5/8)")
    class RsiTiers {
        @Test @DisplayName("RSI 69 → 0 (임계 미만)")
        void below() { assertThat(RecommendationService.overheatPenalty(69.0, false, null)).isZero(); }

        @Test @DisplayName("RSI 72 → 3 (강화 전엔 0이던 구간)")
        void tier70() { assertThat(RecommendationService.overheatPenalty(72.0, false, null)).isEqualTo(3); }

        @Test @DisplayName("RSI 76 → 5 (기존 차감폭 유지)")
        void tier75() { assertThat(RecommendationService.overheatPenalty(76.0, false, null)).isEqualTo(5); }

        @Test @DisplayName("RSI 82 → 8 (극과열)")
        void tier80() { assertThat(RecommendationService.overheatPenalty(82.0, false, null)).isEqualTo(8); }

        @Test @DisplayName("RSI null → 0")
        void nullRsi() { assertThat(RecommendationService.overheatPenalty(null, false, null)).isZero(); }
    }

    @Nested
    @DisplayName("5일 누적 등락률 단계별 과열 차감 (15/20/30% → 3/5/8)")
    class FiveDayTiers {
        @Test @DisplayName("5일 14% → 0 (임계 미만)")
        void below() { assertThat(RecommendationService.overheatPenalty(null, false, 14.0)).isZero(); }

        @Test @DisplayName("5일 17% → 3 (강화 전엔 0이던 구간)")
        void tier15() { assertThat(RecommendationService.overheatPenalty(null, false, 17.0)).isEqualTo(3); }

        @Test @DisplayName("5일 22% → 5 (기존 차감폭 유지)")
        void tier20() { assertThat(RecommendationService.overheatPenalty(null, false, 22.0)).isEqualTo(5); }

        @Test @DisplayName("5일 33% → 8 (극과열)")
        void tier30() { assertThat(RecommendationService.overheatPenalty(null, false, 33.0)).isEqualTo(8); }
    }

    @Nested
    @DisplayName("볼린저 상단 돌파 + 합산")
    class BreakoutAndSum {
        @Test @DisplayName("볼린저 돌파만 → 3")
        void breakoutOnly() { assertThat(RecommendationService.overheatPenalty(null, true, null)).isEqualTo(3); }

        @Test @DisplayName("RSI 82 + 볼린저 + 5일 33% → 19 (8+3+8)")
        void extremeCombo() { assertThat(RecommendationService.overheatPenalty(82.0, true, 33.0)).isEqualTo(19); }

        @Test @DisplayName("임계 바로 아래 콤보(RSI 72 + 5일 17%) → 6 (강화 전엔 0)")
        void nearThresholdCombo() {
            assertThat(RecommendationService.overheatPenalty(72.0, false, 17.0)).isEqualTo(6);
        }

        @Test @DisplayName("과열 없음 → 0")
        void none() { assertThat(RecommendationService.overheatPenalty(50.0, false, 3.0)).isZero(); }
    }

    @Nested
    @DisplayName("태그 표시 임계 = 가장 낮은 차감 구간과 동기")
    class TagThresholds {
        @Test @DisplayName("RSI 태그 임계 70")
        void rsiMin() { assertThat(RecommendationService.OVERHEAT_RSI_MIN).isEqualTo(70.0); }

        @Test @DisplayName("5일 태그 임계 15")
        void fiveDayMin() { assertThat(RecommendationService.OVERHEAT_5D_MIN).isEqualTo(15.0); }
    }
}
