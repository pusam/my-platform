package com.myplatform.backend.service;

import com.myplatform.backend.service.RealTradeService.FillResult;
import com.myplatform.backend.service.RealTradeService.FillStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 체결 판정(resolveFill) — B2-A Phase 1.
 * 지정가 부분/미체결을 KIS 총체결수량으로 판정. 조회 실패(null)는 UNKNOWN → 현행 동작 보존.
 */
class RealTradeFillTest {

    @Test
    @DisplayName("전량 체결 — totCcld == 요청")
    void full() {
        FillResult r = RealTradeService.resolveFill(100, 100);
        assertThat(r.status()).isEqualTo(FillStatus.FULL);
        assertThat(r.filledQty()).isEqualTo(100);
        assertThat(r.isFull()).isTrue();
        assertThat(r.isConfirmedShort()).isFalse();
    }

    @Test
    @DisplayName("과대 보고 방어 — totCcld > 요청도 FULL(요청수량으로 클램프)")
    void over() {
        FillResult r = RealTradeService.resolveFill(100, 120);
        assertThat(r.status()).isEqualTo(FillStatus.FULL);
        assertThat(r.filledQty()).isEqualTo(100);
    }

    @Test
    @DisplayName("부분 체결 — 0 < totCcld < 요청")
    void partial() {
        FillResult r = RealTradeService.resolveFill(100, 60);
        assertThat(r.status()).isEqualTo(FillStatus.PARTIAL);
        assertThat(r.filledQty()).isEqualTo(60);
        assertThat(r.isConfirmedShort()).isTrue();
        assertThat(r.isFull()).isFalse();
    }

    @Test
    @DisplayName("미체결 — totCcld == 0")
    void none() {
        FillResult r = RealTradeService.resolveFill(100, 0);
        assertThat(r.status()).isEqualTo(FillStatus.NONE);
        assertThat(r.filledQty()).isZero();
        assertThat(r.isConfirmedShort()).isTrue();
    }

    @Test
    @DisplayName("조회 실패(null) — UNKNOWN, 보수적으로 요청수량 체결로 간주(현행 보존)")
    void unknown() {
        FillResult r = RealTradeService.resolveFill(100, null);
        assertThat(r.status()).isEqualTo(FillStatus.UNKNOWN);
        assertThat(r.filledQty()).isEqualTo(100);
        assertThat(r.isFull()).isFalse();
        assertThat(r.isConfirmedShort()).isFalse(); // 확정된 미달 아님 → 제거 차단도 안 함
    }

    @Test
    @DisplayName("maxObserved — 후속 null 조회가 앞선 부분체결 관측을 지우지 않음(PARTIAL→UNKNOWN 승격 방지)")
    void maxObserved_keepsPartialAcrossNullPolls() {
        // 폴링 시퀀스: 5주 관측 → null → null. 덮어쓰기였다면 최종 null → UNKNOWN → 포지션 제거(잔량 orphan).
        Integer ccld = null;
        ccld = RealTradeService.maxObserved(ccld, 5);
        ccld = RealTradeService.maxObserved(ccld, null);
        ccld = RealTradeService.maxObserved(ccld, null);
        assertThat(ccld).isEqualTo(5);
        assertThat(RealTradeService.resolveFill(10, ccld).status()).isEqualTo(FillStatus.PARTIAL);
    }

    @Test
    @DisplayName("maxObserved — 관측값은 단조 누적(증가만 반영), 첫 관측 전 null 유지")
    void maxObserved_monotonic() {
        assertThat(RealTradeService.maxObserved(null, null)).isNull();      // 전 회차 실패 → UNKNOWN 경로 보존
        assertThat(RealTradeService.maxObserved(null, 0)).isEqualTo(0);     // 미체결 관측은 유효 관측
        assertThat(RealTradeService.maxObserved(5, 8)).isEqualTo(8);
        assertThat(RealTradeService.maxObserved(8, 5)).isEqualTo(8);        // 역행 값 무시(방어)
    }
}
