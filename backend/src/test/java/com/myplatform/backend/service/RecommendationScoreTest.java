package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.MarketRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-5: 종합추천 점수 경계 · coverage 테스트.
 *
 * <p>합격 기준: validCount&lt;3 → 미채택 / 임계 40·55·75 경계 분류 정확 /
 * total≥75 & value≥12 → +2 보너스 / MarketRegime 승수(BULL·BEAR) 반영.
 * 산식 불변식: 4카테고리 ×20 = raw80 → 0~100 정규화, validCount≥3(coverage 75%).
 */
class RecommendationScoreTest {

    @Nested
    @DisplayName("normalizeScore 경계 (validCount=4 풀, raw×100/58)")
    class NormalizeBoundaries {
        // 2026-11-10 '만점 58로 환산' — 분모는 실제 만점(20 + 수급 기여 10 + 20 + 섹터 8 = 58). 등급 경계는 그 75%·55%·40%.
        @Test @DisplayName("raw 24 → 41 (HOLD 40 이상), raw 23 → 39")
        void at40() {
            assertThat(RecommendationService.normalizeScore(24, 4)).isEqualTo(41);
            assertThat(RecommendationService.normalizeScore(23, 4)).isEqualTo(39);
        }

        @Test @DisplayName("raw 32 → 55 (매수 컷)")
        void at55() { assertThat(RecommendationService.normalizeScore(32, 4)).isEqualTo(55); }

        @Test @DisplayName("raw 44 → 75 (STRONG_BUY 컷)")
        void at75() { assertThat(RecommendationService.normalizeScore(44, 4)).isEqualTo(75); }

        @Test @DisplayName("raw 58(만점) → 100, 그 위는 100 상한")
        void capped100() {
            assertThat(RecommendationService.normalizeScore(58, 4)).isEqualTo(100);
            assertThat(RecommendationService.normalizeScore(80, 4)).isEqualTo(100);
        }

        @Test @DisplayName("validCount=0 → 0")
        void zeroValid() { assertThat(RecommendationService.normalizeScore(40, 0)).isZero(); }
    }

    @Nested
    @DisplayName("validCount cap (커버리지 부족 시 상한)")
    class ValidCountCap {
        @Test @DisplayName("validCount=3 → 최대 74 — 결측이 있으면 강력 매수(75)에 닿지 않는다(raw 48 = 82 도 74)")
        void vc3() {
            assertThat(RecommendationService.normalizeScore(48, 3)).isEqualTo(74);
            assertThat(RecommendationService.normalizeScore(35, 3)).isEqualTo(60);   // 상한 아래는 같은 척도
        }

        @Test @DisplayName("validCount=1 → 최대 25")
        void vc1() {
            assertThat(RecommendationService.normalizeScore(20, 1)).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("validCount: ≥3 채택 컷의 분모")
    class ValidCountCount {
        @Test @DisplayName("4개 중 2개만 >0 → 2 (<3, 미채택)")
        void two() { assertThat(RecommendationService.validCount(20, 20, 0, 0)).isEqualTo(2); }

        @Test @DisplayName("3개 >0 → 3 (채택 가능)")
        void three() { assertThat(RecommendationService.validCount(20, 20, 20, 0)).isEqualTo(3); }

        @Test @DisplayName("음수/0 은 유효 아님")
        void noneNegative() { assertThat(RecommendationService.validCount(0, -1, 0, 0)).isZero(); }
    }

    @Nested
    @DisplayName("STRONG+VALUE +2 보너스 (total≥75 AND value≥12)")
    class StrongValueBonus {
        @Test @DisplayName("75 & value12 → 77")
        void applied() { assertThat(RecommendationService.strongValueBonus(75, 12)).isEqualTo(77); }

        @Test @DisplayName("total 74(<75) → 보너스 없음")
        void totalBelow() { assertThat(RecommendationService.strongValueBonus(74, 12)).isEqualTo(74); }

        @Test @DisplayName("value 11(<12) → 보너스 없음")
        void valueBelow() { assertThat(RecommendationService.strongValueBonus(75, 11)).isEqualTo(75); }

        @Test @DisplayName("99 & value20 → 100 상한 (101 아님)")
        void capped() { assertThat(RecommendationService.strongValueBonus(99, 20)).isEqualTo(100); }

        @Test @DisplayName("100 & value20 → 100 (min)")
        void alreadyMax() { assertThat(RecommendationService.strongValueBonus(100, 20)).isEqualTo(100); }
    }

    @Nested
    @DisplayName("MarketRegime 가중 승수 (BULL/BEAR) + clamp[0,20]")
    class RegimeWeights {
        @Test
        @DisplayName("BULL: 섹터 ×1.0 (phase 38 — +4 floor 와 이중가산 제거), 실적 ×0.95 약화")
        void bull() {
            // e=20,sd=10,tc=10,sc=15
            int[] w = RecommendationService.applyRegimeWeights(20, 10, 10, 15, MarketRegime.BULL);
            assertThat(w[0]).isEqualTo(19);  // 20*0.95=19
            assertThat(w[1]).isEqualTo(11);  // 10*1.10=11
            assertThat(w[2]).isEqualTo(11);  // 10*1.05=10.5 → 11 (half-up)
            assertThat(w[3]).isEqualTo(15);  // 15*1.0=15 (기존 ×1.20 증폭 제거 — scoreSectorMomentum 의 +4 floor 만 유지)
        }

        @Test
        @DisplayName("BEAR: 실적 ×1.20 강화(상한 20 clamp), 섹터 ×0.80 약화")
        void bear() {
            int[] w = RecommendationService.applyRegimeWeights(20, 10, 10, 15, MarketRegime.BEAR);
            assertThat(w[0]).isEqualTo(20);  // 20*1.20=24 → clamp 20
            assertThat(w[1]).isEqualTo(9);   // 10*0.85=8.5 → 9
            assertThat(w[2]).isEqualTo(9);   // 10*0.90=9
            assertThat(w[3]).isEqualTo(12);  // 15*0.80=12
        }

        @Test
        @DisplayName("SIDEWAYS: 섹터만 ×0.9, 나머지 그대로")
        void sideways() {
            int[] w = RecommendationService.applyRegimeWeights(20, 10, 10, 15, MarketRegime.SIDEWAYS);
            assertThat(w).containsExactly(20, 10, 10, 14); // 15*0.9=13.5 → 14
        }

        @Test
        @DisplayName("UNKNOWN(측정 실패): 가중 미적용 — 결측을 SIDEWAYS 판정으로 위장하지 않음(§4c, 2026-07-28)")
        void unknown() {
            int[] w = RecommendationService.applyRegimeWeights(20, 10, 10, 15, MarketRegime.UNKNOWN);
            assertThat(w).containsExactly(20, 10, 10, 15); // 전부 ×1.0
        }
    }

    @Nested
    @DisplayName("빈 결과 발행 판정 — 컷 통과 0건(관망) vs 데이터 몰락 구분 (2026-07-28)")
    class PublishEmptyResult {
        @Test @DisplayName("scoreMap ≥10 종목이면 발행 (정상 계산·관망 결론)")
        void healthyScoreMapPublishes() {
            org.assertj.core.api.Assertions.assertThat(RecommendationService.shouldPublishEmptyResult(10)).isTrue();
            org.assertj.core.api.Assertions.assertThat(RecommendationService.shouldPublishEmptyResult(200)).isTrue();
        }

        @Test @DisplayName("scoreMap 빈약(<10)이면 미발행 (데이터 소스 몰락 의심 — 기존 스냅샷 유지)")
        void starvedScoreMapDoesNot() {
            org.assertj.core.api.Assertions.assertThat(RecommendationService.shouldPublishEmptyResult(0)).isFalse();
            org.assertj.core.api.Assertions.assertThat(RecommendationService.shouldPublishEmptyResult(9)).isFalse();
        }
    }

    @Nested
    @DisplayName("신규 진입 감점 임계 (phase 38 — BULL 은 극단만)")
    class NewEntryThreshold {
        @Test @DisplayName("BULL → 25% (극단 급등만 감점, 정상 추세 풀 보존)")
        void bull() { assertThat(RecommendationService.newEntryPenaltyThreshold(MarketRegime.BULL)).isEqualTo(25.0); }

        @Test @DisplayName("BEAR → 15%")
        void bear() { assertThat(RecommendationService.newEntryPenaltyThreshold(MarketRegime.BEAR)).isEqualTo(15.0); }

        @Test @DisplayName("SIDEWAYS → 15%")
        void sideways() { assertThat(RecommendationService.newEntryPenaltyThreshold(MarketRegime.SIDEWAYS)).isEqualTo(15.0); }
    }
}
