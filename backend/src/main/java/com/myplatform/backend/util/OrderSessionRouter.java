package com.myplatform.backend.util;

import java.time.LocalTime;

/**
 * 주문 세션 라우팅 결정 — <b>순수 함수</b> (2026-09-14 NXT 연장장 대비).
 *
 * <p>호출부가 요청한 세션(기본 REGULAR)을 flag·시각과 함께 받아 <b>실제 라우팅 세션</b>을 돌려준다.
 * 부작용 없음(로그·flag 읽기는 호출부 책임) — 경계값을 결정성 있게 테스트하기 위한 분리.
 *
 * <p><b>다운그레이드 정책</b>: NXT 를 요청했으나 flag OFF 이거나 NXT 창(15:30~20:00) 밖이면 REGULAR 로
 * 강등한다. 강등 자체는 조용히 하지 않는다(§4c) — 호출부가 {@code requested != effective} 를 보고 WARN.
 */
public final class OrderSessionRouter {

    private OrderSessionRouter() {}

    /** KRX 정규 연속세션 종료(= NXT 연장 세션 시작 경계). */
    public static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 30);
    /** NXT 연장 세션 종료(9/14 이후 거래 상한). */
    public static final LocalTime NXT_EXTENDED_CLOSE = LocalTime.of(20, 0);

    /**
     * 실제 주문 라우팅 세션 판정.
     *
     * @param now                현재 시각(KST)
     * @param nxtRoutingEnabled  {@code bot.nxt-routing.enabled} flag
     * @param requested          호출부 요청 세션(null=REGULAR 취급)
     * @return REGULAR 또는 NXT_EXTENDED. NXT 요청이라도 flag OFF·창 밖이면 REGULAR(강등).
     */
    public static OrderSession resolveOrderSession(LocalTime now, boolean nxtRoutingEnabled, OrderSession requested) {
        if (requested == null || requested == OrderSession.REGULAR) {
            return OrderSession.REGULAR;
        }
        // requested == NXT_EXTENDED
        if (!nxtRoutingEnabled) {
            return OrderSession.REGULAR;   // flag OFF → 강제 다운그레이드(호출부 WARN)
        }
        // NXT 연장 세션 창: (15:30, 20:00]. 15:30 정각은 아직 정규장(REGULAR).
        if (now != null && now.isAfter(REGULAR_CLOSE) && !now.isAfter(NXT_EXTENDED_CLOSE)) {
            return OrderSession.NXT_EXTENDED;
        }
        return OrderSession.REGULAR;
    }
}
