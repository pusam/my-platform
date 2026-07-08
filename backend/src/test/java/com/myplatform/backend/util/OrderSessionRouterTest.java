package com.myplatform.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 세션 라우팅 순수 판정 경계값 — NXT 연장장(2026-09-14) 대비.
 * flag OFF 강제 다운그레이드 + NXT 창(15:30~20:00) 경계(15:30/20:00) 확정.
 */
class OrderSessionRouterTest {

    private OrderSession resolve(LocalTime now, boolean flag, OrderSession req) {
        return OrderSessionRouter.resolveOrderSession(now, flag, req);
    }

    @Test
    @DisplayName("REGULAR 요청은 시각·flag 무관 항상 REGULAR")
    void regularAlwaysRegular() {
        assertThat(resolve(LocalTime.of(16, 0), true, OrderSession.REGULAR)).isEqualTo(OrderSession.REGULAR);
        assertThat(resolve(LocalTime.of(10, 0), false, OrderSession.REGULAR)).isEqualTo(OrderSession.REGULAR);
        assertThat(resolve(LocalTime.of(16, 0), true, null)).isEqualTo(OrderSession.REGULAR);   // null=REGULAR 취급
    }

    @Test
    @DisplayName("flag OFF → NXT 요청도 REGULAR 강제 다운그레이드")
    void flagOffDowngrades() {
        assertThat(resolve(LocalTime.of(16, 0), false, OrderSession.NXT_EXTENDED))
                .isEqualTo(OrderSession.REGULAR);
    }

    @Test
    @DisplayName("flag ON + NXT 요청: 15:30 정각은 아직 REGULAR, 15:31 부터 NXT")
    void boundary_1530() {
        assertThat(resolve(LocalTime.of(15, 30), true, OrderSession.NXT_EXTENDED))
                .isEqualTo(OrderSession.REGULAR);   // 정각은 정규장
        assertThat(resolve(LocalTime.of(15, 31), true, OrderSession.NXT_EXTENDED))
                .isEqualTo(OrderSession.NXT_EXTENDED);
    }

    @Test
    @DisplayName("flag ON + NXT 요청: 20:00 정각은 NXT(포함), 20:01 부터 REGULAR")
    void boundary_2000() {
        assertThat(resolve(LocalTime.of(20, 0), true, OrderSession.NXT_EXTENDED))
                .isEqualTo(OrderSession.NXT_EXTENDED);   // 20:00 포함
        assertThat(resolve(LocalTime.of(20, 1), true, OrderSession.NXT_EXTENDED))
                .isEqualTo(OrderSession.REGULAR);
    }

    @Test
    @DisplayName("flag ON + NXT 요청이지만 정규장 시간(예: 10:00)이면 REGULAR")
    void nxtRequestedDuringRegularHours() {
        assertThat(resolve(LocalTime.of(10, 0), true, OrderSession.NXT_EXTENDED))
                .isEqualTo(OrderSession.REGULAR);
    }
}
