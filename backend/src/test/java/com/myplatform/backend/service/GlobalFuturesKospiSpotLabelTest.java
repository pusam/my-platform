package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코스피200 카드 — Yahoo 폴백(^KS200)은 현물 지수다(2026-10-03).
 *
 * <p>재현: KIS 선물 조회가 실패하면 Yahoo ^KS200(현물 지수)을 '코스피200 선물'이라는 이름으로 보였다.
 */
class GlobalFuturesKospiSpotLabelTest {

    @Test
    @DisplayName("재현: KM 의 Yahoo 폴백은 '선물'이라 부르지 않는다")
    void kospiYahooFallbackIsSpot() {
        String name = GlobalFuturesService.yahooDisplayName("KM", "코스피200 선물");
        assertThat(name).contains("현물").contains("지수");
        assertThat(name).doesNotStartWith("코스피200 선물");
    }

    @Test
    @DisplayName("다른 심볼은 맵 이름 그대로")
    void othersUnchanged() {
        assertThat(GlobalFuturesService.yahooDisplayName("NQ", "나스닥100 선물")).isEqualTo("나스닥100 선물");
    }
}
