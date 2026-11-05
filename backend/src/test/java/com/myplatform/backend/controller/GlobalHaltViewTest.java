package com.myplatform.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트레이딩 지표 '글로벌 악재 필터' 화면(2026-11-06 운영 화면).
 *
 * <p>재현: 간밤 나스닥 −6.36%·SOX −12.25% 인 아침에 "✅ 글로벌 시장 정상. 매수 가능"이었다. 필터가 나스닥 선물만 보는데 선물 등락률은 새 CME
 * 세션 기준이라 −0.74% 였다. 봇의 필터({@code shouldHaltBuying})는 매매 규칙이라 그대로 두고 화면만 간밤을 본다.
 */
class GlobalHaltViewTest {

    @Test
    @DisplayName("재현: 코스피 개장 전 간밤 나스닥이 −1% 아래면 선물이 멀쩡해도 보류라고 말한다")
    void overnightDropHolds() {
        TradingIndicatorController.GlobalHaltView v = TradingIndicatorController.globalHaltView(false, -6.36);

        assertThat(v.shouldHalt()).isTrue();
        assertThat(v.message()).contains("간밤 나스닥 -6.36%").doesNotContain("정상").doesNotContain("매수 가능");
    }

    @Test
    @DisplayName("간밤이 조용하면 막지 않되 '매수 가능'이라 하지 않는다 — 선물 하나만 본 결과다")
    void quietNightDoesNotSayBuy() {
        TradingIndicatorController.GlobalHaltView v = TradingIndicatorController.globalHaltView(false, -0.40);

        assertThat(v.shouldHalt()).isFalse();
        assertThat(v.message()).contains("나스닥 선물 기준").doesNotContain("매수 가능");
    }

    @Test
    @DisplayName("장중엔 간밤 값이 없다 — 선물 필터 그대로")
    void sessionUsesFutures() {
        assertThat(TradingIndicatorController.globalHaltView(true, null).shouldHalt()).isTrue();
        assertThat(TradingIndicatorController.globalHaltView(true, null).message()).contains("매수 보류");
        assertThat(TradingIndicatorController.globalHaltView(false, null).shouldHalt()).isFalse();
    }
}
