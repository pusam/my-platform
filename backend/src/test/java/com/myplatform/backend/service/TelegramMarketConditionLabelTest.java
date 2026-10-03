package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 상태 텔레그램 문구 — MarketTimingDto.MarketCondition 의 문구 하나로(2026-10-03).
 *
 * <p>재현: 알림만 따로 적은 문구라 극심한 공포가 화면('적극 매수 검토')과 달리 "적극 매수!"였고, 모르는 상태
 * (급락일 CRASH 등)는 "☁️ 보통"으로 떨어졌다.
 */
class TelegramMarketConditionLabelTest {

    @Test
    @DisplayName("재현: 극심한 공포는 화면과 같은 문구 — '적극 매수!'가 아니다")
    void extremeFearMatchesScreen() {
        String label = TelegramNotificationService.marketConditionLabel("EXTREME_FEAR");
        assertThat(label).doesNotContain("적극 매수!");
        assertThat(label).isEqualTo(com.myplatform.backend.dto.MarketTimingDto.MarketCondition.EXTREME_FEAR.getEmoji());
    }

    @Test
    @DisplayName("재현: 급락일(CRASH)은 '보통'이 아니다, 모르는 값은 그대로")
    void crashAndUnknown() {
        assertThat(TelegramNotificationService.marketConditionLabel("CRASH")).contains("폭락");
        assertThat(TelegramNotificationService.marketConditionLabel("WHATEVER")).isEqualTo("WHATEVER");
    }
}
