package com.myplatform.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RecommendationService.normalizeScore 단위 테스트.
 *
 * <p>2026-11-10(사용자 결정 '만점 58로 환산'): 분모가 4 × 20 = 80 이 아니라 지금 규칙의 실제 만점
 * (실적 20 + 수급 기여 최대 10 + 기술 20 + 섹터 최대 8 = 58)이다. 예전엔 네 항목 만점을 다 받아도 72점이라 강력 매수(75)가
 * 나올 수 없었고 매수 컷(55)은 만점의 76% 를 요구했다. 커버리지 페널티는 그대로 — 결측이 있으면 최대 vc × 25 이고 강력 매수에는
 * 닿지 않는다(최대 74).
 */
class RecommendationServiceNormalizeTest {

    private int savedCap;

    @BeforeEach
    void pinCap() {
        savedCap = RecommendationService.SUPPLY_DEMAND_CAP;
        RecommendationService.SUPPLY_DEMAND_CAP = 10;   // 운영 기본값
    }

    @AfterEach
    void restoreCap() {
        RecommendationService.SUPPLY_DEMAND_CAP = savedCap;
    }

    private int normalize(int raw, int validCount) throws Exception {
        Method m = RecommendationService.class.getDeclaredMethod("normalizeScore", int.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, raw, validCount);
    }

    @Test
    @DisplayName("재현(11/10): 지금 규칙의 만점(20·10·20·8 = 58)이 100 이다 — 예전엔 72 라 강력 매수(75)가 나올 수 없었다")
    void currentRulesMaxIs100() throws Exception {
        assertThat(normalize(58, 4)).isEqualTo(100);
    }

    @Test
    @DisplayName("등급 경계는 만점의 75%·55% — 원점수 44 면 강력 매수, 43 이면 아니다 / 32 면 매수, 31 이면 아니다")
    void gradeBoundariesAreFractionsOfTheRealMax() throws Exception {
        assertThat(normalize(44, 4)).isEqualTo(75);
        assertThat(normalize(43, 4)).isLessThan(75);
        assertThat(normalize(32, 4)).isEqualTo(55);
        assertThat(normalize(31, 4)).isLessThan(55);
    }

    @Test
    @DisplayName("재현(11/10 08:03 파세코): 결측 1개(수급)·실적 19·기술 20·섹터 8 = 47 → 81 이지만 강력 매수가 아니라 74")
    void missingCategoryCannotBeStrongBuy() throws Exception {
        assertThat(normalize(47, 3)).isEqualTo(74);
        assertThat(normalize(48, 3)).isEqualTo(74);
        // 커버리지 페널티 아래면 그대로 — 매수 판정은 같은 척도
        assertThat(normalize(35, 3)).isEqualTo(60);
    }

    @Test
    @DisplayName("결측 2·3개는 예전과 같은 상한 — vc=2 → 50, vc=1 → 25")
    void lowerCoverageCaps() throws Exception {
        assertThat(normalize(40, 2)).isEqualTo(50);
        assertThat(normalize(20, 1)).isEqualTo(25);
    }

    @Test
    @DisplayName("0 valid → 0")
    void zeroValid() throws Exception {
        assertThat(normalize(0, 0)).isZero();
        assertThat(normalize(50, 0)).isZero();
    }

    @Test
    @DisplayName("수급 캡 flag 를 꺼도 분모는 58 그대로 — 캡은 raw 기여만 바꾼다(A안 불변식), 넘치면 100 에서 멈춘다")
    void denominatorIgnoresSupplyCapFlag() throws Exception {
        RecommendationService.SUPPLY_DEMAND_CAP = -1;
        assertThat(normalize(58, 4)).isEqualTo(100);
        assertThat(normalize(68, 4)).isEqualTo(100);
        assertThat(normalize(32, 4)).isEqualTo(55);
    }
}
