package com.myplatform.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-05 봇 감사 P0 — <b>청산 경로가 막히면 손실이 무한대로 열린다</b>.
 *
 * <p>§4d 의 핵심 비대칭: <b>매수는 fail-closed / 매도는 fail-open</b>.
 * 일일 손실 브레이커는 이 원칙대로 진입만 막는데, 총 킬스위치(-3%)만 예외적으로
 * 손절·강제청산·미완료경고까지 전부 껐다 — 손실을 멈추라고 만든 장치가 손실 상한을 없앤 것.
 */
class BotExitSafetyTest {

    private static final LocalTime WINDOW_START = LocalTime.of(15, 20);
    private static final LocalTime WINDOW_END = LocalTime.of(15, 28);
    private static final LocalTime IN_WINDOW = LocalTime.of(15, 22);

    // ==================== 킬스위치는 청산을 막지 않는다 ====================

    @Test
    void 킬스위치가_발동해도_강제청산은_돌아야_한다() {
        // 킬스위치는 '손실 중'에 발동한다 — 이때야말로 청산이 가장 필요하다
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                false, true, true, false, IN_WINDOW, WINDOW_START, WINDOW_END))
                .as("botActive=false·killed=true 여도 보유 포지션 청산은 진행").isTrue();
    }

    @Test
    void 킬스위치가_발동해도_미완료_경고는_떠야_한다() {
        assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(false, true, true, false))
                .as("경고까지 꺼지면 운영자가 방치를 인지할 단서가 없다").isTrue();
    }

    @Test
    void 이미_청산_완료한_날은_다시_돌지_않는다() {
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                true, false, true, true, IN_WINDOW, WINDOW_START, WINDOW_END)).isFalse();
        assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(true, false, true, true)).isFalse();
    }

    @Test
    void 설정으로_끈_강제청산은_돌지_않는다() {
        // configOn=false 는 운영자의 명시적 의사 — 킬스위치와 달리 존중한다
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                true, false, false, false, IN_WINDOW, WINDOW_START, WINDOW_END)).isFalse();
        assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(true, false, false, false)).isFalse();
    }

    @Test
    void 창_밖_시각에는_돌지_않는다() {
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                true, false, true, false, LocalTime.of(15, 19), WINDOW_START, WINDOW_END)).isFalse();
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                true, false, true, false, LocalTime.of(15, 29), WINDOW_START, WINDOW_END)).isFalse();
    }

    @Test
    void 창_경계는_포함이다() {
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                true, false, true, false, WINDOW_START, WINDOW_START, WINDOW_END)).isTrue();
        assertThat(AutoTradingBotService.shouldRunLiquidationWindow(
                true, false, true, false, WINDOW_END, WINDOW_START, WINDOW_END)).isTrue();
    }

    // ==================== 매도 사이클 진입 판정 ====================

    @Test
    void 킬스위치_상태에서도_보유가_있으면_매도_사이클은_돈다() {
        assertThat(AutoTradingBotService.shouldRunSellCycle(false, true, true))
                .as("킬스위치는 진입만 막고 손절은 계속").isTrue();
    }

    @Test
    void 보유가_없으면_매도_사이클을_돌_필요가_없다() {
        assertThat(AutoTradingBotService.shouldRunSellCycle(true, false, false)).isFalse();
        assertThat(AutoTradingBotService.shouldRunSellCycle(false, true, false)).isFalse();
    }

    @Test
    void 정상_가동중에는_당연히_돈다() {
        assertThat(AutoTradingBotService.shouldRunSellCycle(true, false, true)).isTrue();
    }

    // ==================== 스윙 시그널 신선도 (2026-08-05 감사 4번) ====================

    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 8, 5);

    @Test
    void 수급_시그널이_너무_오래되면_진입하지_않는다() {
        // 수집이 며칠 실패하면 이미 소멸한 시그널로 실주문이 나간다
        assertThat(AutoTradingBotService.isSwingSignalFresh(TODAY.minusDays(10), TODAY)).isFalse();
        assertThat(AutoTradingBotService.isSwingSignalFresh(
                TODAY.minusDays(AutoTradingBotService.SWING_SIGNAL_MAX_AGE_DAYS + 1), TODAY)).isFalse();
    }

    @Test
    void 최근_시그널은_진입_가능() {
        assertThat(AutoTradingBotService.isSwingSignalFresh(TODAY, TODAY)).isTrue();
        assertThat(AutoTradingBotService.isSwingSignalFresh(TODAY.minusDays(1), TODAY)).isTrue();
        // 주말+연휴를 덮는 마진 — 경계 포함
        assertThat(AutoTradingBotService.isSwingSignalFresh(
                TODAY.minusDays(AutoTradingBotService.SWING_SIGNAL_MAX_AGE_DAYS), TODAY)).isTrue();
    }

    @Test
    void 시그널_날짜나_오늘을_모르면_기존대로_통과시킨다() {
        // 결측으로 전 종목을 차단하면 진입이 통째로 멈춘다 — fail-open
        assertThat(AutoTradingBotService.isSwingSignalFresh(null, TODAY)).isTrue();
        assertThat(AutoTradingBotService.isSwingSignalFresh(TODAY, null)).isTrue();
    }

    @Test
    void 운영자가_봇을_끄고_킬스위치도_아니면_매도도_멈춘다() {
        // 명시적 stopBot() 은 운영자가 수동 통제를 가져간 것 — 존중한다.
        // 킬스위치(자동·손실중)와 구분되는 유일한 케이스.
        assertThat(AutoTradingBotService.shouldRunSellCycle(false, false, true)).isFalse();
    }
}
