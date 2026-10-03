package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실전 계좌 요약 — 수익률을 0 으로 박지 않는다(2026-10-03 화면 점검).
 *
 * <p>예전엔 {@code totalProfitRate(BigDecimal.ZERO)}("초기자본 없으므로 계산 불가")라 화면이 늘 '수익률 +0.00%',
 * 잔고 조회가 한 번도 성공하지 못하면 금액 전부 0·updatedAt=지금이라 '🟢 Live 0원'이었다.
 */
class RealAccountSummaryHonestyTest {

    @Test
    @DisplayName("보유 평가 수익률 = 평가손익 ÷ 투자금액 — 예전엔 늘 0")
    void holdingReturn() {
        assertThat(RealTradeService.holdingReturnPct(new BigDecimal("150000"), new BigDecimal("1000000")))
                .isEqualByComparingTo("15.00");
        assertThat(RealTradeService.holdingReturnPct(new BigDecimal("-25000"), new BigDecimal("500000")))
                .isEqualByComparingTo("-5.00");
    }

    @Test
    @DisplayName("보유가 없으면 모름(null) — 0% 아님")
    void noHoldingsUnknown() {
        assertThat(RealTradeService.holdingReturnPct(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
        assertThat(RealTradeService.holdingReturnPct(null, new BigDecimal("1"))).isNull();
    }
}
