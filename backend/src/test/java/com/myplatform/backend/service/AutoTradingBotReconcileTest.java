package com.myplatform.backend.service;

import com.myplatform.backend.service.AutoTradingBotService.ReconciliationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시작 시 KIS 실잔고 ↔ 봇 추적 포지션 정합성 diff (B3-A).
 * orphaned = 봇은 추적하나 KIS 잔고에 없음 / untracked = KIS 잔고엔 있으나 봇이 추적 안 함.
 */
class AutoTradingBotReconcileTest {

    @Test
    @DisplayName("일치 — orphaned·untracked 모두 없음")
    void clean() {
        ReconciliationResult r = AutoTradingBotService.computeReconciliation(
                Set.of("000660", "005930"), Set.of("005930", "000660"));
        assertThat(r.isClean()).isTrue();
        assertThat(r.orphaned()).isEmpty();
        assertThat(r.untracked()).isEmpty();
    }

    @Test
    @DisplayName("orphaned — 봇 추적O/KIS X (이미 매도됐거나 부분체결 잔재)")
    void orphaned() {
        ReconciliationResult r = AutoTradingBotService.computeReconciliation(
                Set.of("005930"), Set.of("005930", "035720"));
        assertThat(r.orphaned()).containsExactly("035720");
        assertThat(r.untracked()).isEmpty();
        assertThat(r.isClean()).isFalse();
    }

    @Test
    @DisplayName("untracked — KIS O/봇 추적X (DB 유실분 또는 수동 보유)")
    void untracked() {
        ReconciliationResult r = AutoTradingBotService.computeReconciliation(
                Set.of("005930", "000660"), Set.of("005930"));
        assertThat(r.untracked()).containsExactly("000660");
        assertThat(r.orphaned()).isEmpty();
        assertThat(r.isClean()).isFalse();
    }

    @Test
    @DisplayName("양쪽 불일치 동시 — orphaned·untracked 각각 산출")
    void both() {
        ReconciliationResult r = AutoTradingBotService.computeReconciliation(
                Set.of("005930", "000660"), Set.of("005930", "035720"));
        assertThat(r.orphaned()).containsExactly("035720"); // 봇O/KIS X
        assertThat(r.untracked()).containsExactly("000660"); // KIS O/봇X
    }

    @Test
    @DisplayName("빈 입력 — KIS 비었고 봇 추적만 있으면 전부 orphaned")
    void emptyKis() {
        ReconciliationResult r = AutoTradingBotService.computeReconciliation(
                Set.of(), Set.of("005930", "000660"));
        assertThat(r.orphaned()).containsExactlyInAnyOrder("005930", "000660");
        assertThat(r.untracked()).isEmpty();
    }
}
