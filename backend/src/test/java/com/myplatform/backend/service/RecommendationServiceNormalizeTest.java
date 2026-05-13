package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RecommendationService.normalizeScore 단위 테스트.
 *
 * phase 14 변경 — 4 valid full 시 raw 그대로(80) 반환 → scaled (100) 으로 수정.
 *
 * 검증:
 *  - 1/2/3/4 valid 각각의 점수 변환 + cap 적용
 *  - 0 valid → 0
 *  - 4 valid full = 100 (이전 80 버그 회귀 방지)
 */
class RecommendationServiceNormalizeTest {

    private int normalize(int raw, int validCount) throws Exception {
        Method m = RecommendationService.class.getDeclaredMethod("normalizeScore", int.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, raw, validCount);
    }

    @Test
    @DisplayName("4 valid full (raw=80) → 100 (phase14 버그 회귀 방지)")
    void fourValidFull_returns100() throws Exception {
        assertThat(normalize(80, 4)).isEqualTo(100);
    }

    @Test
    @DisplayName("4 valid 중간 (raw=60) → 75")
    void fourValidMid() throws Exception {
        assertThat(normalize(60, 4)).isEqualTo(75);
    }

    @Test
    @DisplayName("3 valid full (raw=60) → 75, cap 81 미적용")
    void threeValidFull() throws Exception {
        // scaled = 60 × 100 / 80 = 75, cap = 81 → min(81, 75) = 75
        assertThat(normalize(60, 3)).isEqualTo(75);
    }

    @Test
    @DisplayName("2 valid full (raw=40) → cap 62 적용")
    void twoValidFull_capApplied() throws Exception {
        // scaled = 40 × 100 / 80 = 50, cap = 62 → min(62, 50) = 50
        assertThat(normalize(40, 2)).isEqualTo(50);
    }

    @Test
    @DisplayName("1 valid full (raw=20) → cap 43 적용")
    void oneValidFull() throws Exception {
        // scaled = 20 × 100 / 80 = 25, cap = 43 → min(43, 25) = 25
        assertThat(normalize(20, 1)).isEqualTo(25);
    }

    @Test
    @DisplayName("0 valid → 0")
    void zeroValid() throws Exception {
        assertThat(normalize(0, 0)).isZero();
        assertThat(normalize(50, 0)).isZero();
    }

    @Test
    @DisplayName("STRONG_BUY 임계 75 도달 — 4 valid 평균 15점 이상")
    void strongBuyThreshold() throws Exception {
        // 4 valid, 카테고리당 15점 = raw 60 → scaled 75 ✓
        assertThat(normalize(60, 4)).isGreaterThanOrEqualTo(75);
        // 카테고리당 14점 = raw 56 → 70 (미달)
        assertThat(normalize(56, 4)).isLessThan(75);
    }
}
