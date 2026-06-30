package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 간밤 미국장 tilt 분류(작업3) 순수 함수 테스트 — 임계 경계 + VIX null(marketState 비의존) 검증.
 * 임계값은 임시값(추후 캘리브레이션) — 이 테스트는 현재 규칙의 회귀 가드.
 */
class OvernightUsMarketServiceTest {

    @Test
    @DisplayName("3지수 강세 + VIX 안정 → BULL")
    void bull() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.8, 1.2, 2.0, 14.0)).isEqualTo("BULL");
    }

    @Test
    @DisplayName("VIX >= 30 → 공포 강제 BEAR (지수 강세여도)")
    void vixPanicForcesBear() {
        assertThat(OvernightUsMarketService.classifyOvernight(1.0, 1.5, 2.0, 31.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("3지수 평균 <= -0.6% → BEAR")
    void avgDownBear() {
        assertThat(OvernightUsMarketService.classifyOvernight(-0.7, -0.8, -0.5, 18.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("SOX <= -2% (평균은 -0.6 미만) → 반도체 급락 BEAR (단독 트리거)")
    void soxCrashBear() {
        // 평균 = (0.5+0.5-2.5)/3 = -0.5 (BEAR 평균조건 미충족) 이지만 SOX -2.5 단독으로 BEAR
        assertThat(OvernightUsMarketService.classifyOvernight(0.5, 0.5, -2.5, 16.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("VIX 25~30 → 공포경계 BEAR")
    void vixElevatedBear() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.2, 0.1, 0.0, 26.0)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("강세지만 VIX 20~25 → BULL 아님(NEUTRAL)")
    void strongButElevatedVixNeutral() {
        assertThat(OvernightUsMarketService.classifyOvernight(1.0, 1.0, 1.0, 22.0)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("경계값: 평균 +0.6 + VIX 19 → BULL / +0.59 → NEUTRAL")
    void boundary() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.6, 0.6, 0.6, 19.0)).isEqualTo("BULL");
        assertThat(OvernightUsMarketService.classifyOvernight(0.59, 0.59, 0.59, 19.0)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("VIX null(미수집)이어도 등락률만으로 분류 — marketState/VIX 비의존")
    void vixNullStillClassifies() {
        assertThat(OvernightUsMarketService.classifyOvernight(0.9, 1.1, 1.5, null)).isEqualTo("BULL");
        assertThat(OvernightUsMarketService.classifyOvernight(-1.0, -1.0, -1.0, null)).isEqualTo("BEAR");
    }

    @Test
    @DisplayName("전부 null → NEUTRAL (데이터 없음 안전 기본값)")
    void allNullNeutral() {
        assertThat(OvernightUsMarketService.classifyOvernight(null, null, null, null)).isEqualTo("NEUTRAL");
    }
}
