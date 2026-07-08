package com.myplatform.backend.util;

/**
 * KIS 주문 라우팅 세션 — 2026-09-14 거래시간 연장(NXT ~20:00) 대비.
 *
 * <ul>
 *   <li>{@link #REGULAR} — KRX 정규 연속세션(현행, 09:00~15:30). <b>모든 기존 호출부의 기본값</b>.
 *       이 세션은 KIS order-cash 바디를 현행 그대로 구성(거래소구분 필드 미추가 = 바이트 동일).</li>
 *   <li>{@link #NXT_EXTENDED} — NXT 연장 세션(15:30~20:00, 9/14 이후). <b>방어적 청산 한정</b>
 *       (진입은 §16-2 대로 KRX 정규장 유지). flag {@code bot.nxt-routing.enabled} OFF 면 도달 불가.</li>
 * </ul>
 *
 * @see OrderSessionRouter
 * @see docs/NXT_ROUTING_DESIGN.md
 */
public enum OrderSession {
    REGULAR,
    NXT_EXTENDED
}
