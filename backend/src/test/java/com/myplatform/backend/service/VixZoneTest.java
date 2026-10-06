package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VIX 구간 이름 — 화면 공용 기준(frontend composables/useMarketStatus.js VIX_TIERS)과 같은 5단계(2026-10-06 화면 점검).
 *
 * <p>재현: 같은 VIX 15.52 가 글로벌 화면 배지는 '보통', 눈금 범례는 '경계(15~25)', 종합 방향 문장·간밤 미국장 카드는 '안정'이었다
 * — 백엔드 문장은 20 미만을 전부 '안정'이라 했다.
 */
class VixZoneTest {

    @Test
    @DisplayName("재현: 15~20 은 '보통' — '안정'이 아니다")
    void fifteenToTwentyIsNormal() {
        assertThat(VixZone.label(15.52)).isEqualTo("보통");
        assertThat(VixZone.label(19.99)).isEqualTo("보통");
    }

    @Test
    @DisplayName("나머지 구간은 화면 공용 기준 그대로")
    void otherTiersMatchSharedScale() {
        assertThat(VixZone.label(14.9)).isEqualTo("안정");
        assertThat(VixZone.label(20)).isEqualTo("경계");
        assertThat(VixZone.label(25)).isEqualTo("공포");
        assertThat(VixZone.label(30)).isEqualTo("극심한 공포");
    }
}
