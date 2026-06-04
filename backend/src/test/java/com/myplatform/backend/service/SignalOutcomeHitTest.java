package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4: 시그널 hit 산식 테스트 ({@link SignalOutcomeService#isHit}).
 *
 * <p>합격 기준: alpha≥0 & pct&gt;0 → hit / 둘 중 하나라도 미달 → miss /
 * alpha null → 폴백 pct≥3% / pct 정확히 0·경계값 처리 확인. (불변식 5번)
 */
class SignalOutcomeHitTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Nested
    @DisplayName("alpha 있음: alpha≥0 AND pct>0 둘 다여야 hit")
    class WithAlpha {
        @Test
        @DisplayName("alpha>0 & pct>0 → hit")
        void bothPositive() {
            assertThat(SignalOutcomeService.isHit(bd("1.5"), bd("2.0"))).isTrue();
        }

        @Test
        @DisplayName("alpha=0 & pct>0 → hit (alpha 경계 0 포함)")
        void alphaZeroBoundary() {
            assertThat(SignalOutcomeService.isHit(bd("0"), bd("0.01"))).isTrue();
        }

        @Test
        @DisplayName("alpha<0 → miss (시장 못 이김)")
        void negativeAlpha() {
            assertThat(SignalOutcomeService.isHit(bd("-0.01"), bd("5.0"))).isFalse();
        }

        @Test
        @DisplayName("alpha≥0 이지만 pct=0 → miss (절대 수익 양수 아님)")
        void pctZero() {
            assertThat(SignalOutcomeService.isHit(bd("1.0"), bd("0"))).isFalse();
        }

        @Test
        @DisplayName("alpha≥0 이지만 pct<0 → miss")
        void pctNegative() {
            assertThat(SignalOutcomeService.isHit(bd("2.0"), bd("-0.5"))).isFalse();
        }
    }

    @Nested
    @DisplayName("alpha 없음(BM 부재): pct≥3% 폴백")
    class FallbackNoAlpha {
        @Test
        @DisplayName("pct=3.00 → hit (경계 포함)")
        void exactlyThree() {
            assertThat(SignalOutcomeService.isHit(null, bd("3.00"))).isTrue();
        }

        @Test
        @DisplayName("pct=2.99 → miss (경계 직전)")
        void justBelowThree() {
            assertThat(SignalOutcomeService.isHit(null, bd("2.99"))).isFalse();
        }

        @Test
        @DisplayName("pct=10 → hit")
        void wellAbove() {
            assertThat(SignalOutcomeService.isHit(null, bd("10"))).isTrue();
        }
    }

    @Test
    @DisplayName("방어: pct null → miss")
    void pctNull() {
        assertThat(SignalOutcomeService.isHit(bd("1.0"), null)).isFalse();
        assertThat(SignalOutcomeService.isHit(null, null)).isFalse();
    }
}
