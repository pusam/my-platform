package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해외 시세 장 상태 — {@code GlobalFuturesService.marketStateOrNull}(2026-10-02).
 *
 * <p>재현: Yahoo 응답에 marketState 가 없으면 "CLOSED" 로 채워, 10/2 11:28(장중) 글로벌 선물 머리말이 "장마감"이었다.
 * 모르는 것은 null — 화면은 null 이면 배지를 그리지 않는다.
 */
class GlobalFuturesMarketStateTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    @DisplayName("marketState 가 없거나 비면 null — 닫혔다고 단정하지 않는다")
    void missingIsNullNotClosed() throws Exception {
        assertThat(GlobalFuturesService.marketStateOrNull(M.readTree("{\"regularMarketPrice\":1}"))).isNull();
        assertThat(GlobalFuturesService.marketStateOrNull(M.readTree("{\"marketState\":null}"))).isNull();
        assertThat(GlobalFuturesService.marketStateOrNull(M.readTree("{\"marketState\":\"  \"}"))).isNull();
        assertThat(GlobalFuturesService.marketStateOrNull(null)).isNull();
    }

    @Test
    @DisplayName("있으면 그대로")
    void presentIsKept() throws Exception {
        assertThat(GlobalFuturesService.marketStateOrNull(M.readTree("{\"marketState\":\"REGULAR\"}"))).isEqualTo("REGULAR");
        assertThat(GlobalFuturesService.marketStateOrNull(M.readTree("{\"marketState\":\"CLOSED\"}"))).isEqualTo("CLOSED");
    }
}
