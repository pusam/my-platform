package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇 VIX 매수 일시정지가 묻는 심볼은 글로벌 시세 맵의 키여야 한다(2026-10-03).
 *
 * <p>예전엔 "^VIX"(Yahoo 티커)로 물어 늘 '알 수 없는 심볼'이 돌아왔고, 가드가 fail-open 이라 VIX 30 이상에서도
 * 매수가 멈춘 적이 없다. 같은 서비스를 쓰는 간밤 미국장(OvernightUsMarketService)은 "VIX" 로 묻는다.
 */
class AutoTradingBotVixSymbolTest {

    @Test
    @DisplayName("재현: \"^VIX\" 는 맵에 없는 심볼 — 봇은 \"VIX\" 로 묻는다")
    void botAsksAKnownSymbol() {
        assertThat(GlobalFuturesService.isKnownSymbol("^VIX")).isFalse();
        assertThat(GlobalFuturesService.isKnownSymbol(AutoTradingBotService.VIX_SYMBOL)).isTrue();
    }
}
