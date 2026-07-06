package com.myplatform.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * 리스크 균등 사이징 경계값 테스트.
 * 안전 계약: ① 결과 항상 현행 수량 이하 ② 결측 → 현행 폴백(§4c 주문 확대 금지).
 */
class PositionSizerTest {

    private static final BigDecimal ASSET = new BigDecimal("500000");     // 총자산 50만
    private static final BigDecimal PRICE = new BigDecimal("10000");
    private static final BigDecimal RISK_50K = new BigDecimal("50000");   // 브레이커 30만 ÷ 6

    @Test
    void riskEqualSizing_basic() {
        // 손절폭 7.5% → 주당 손실 750원 → 50,000÷750 = 66주. 상한: min(현행 25만, 자산×50%=25만)÷1만 = 25주 → 25주
        int qty = PositionSizer.judge(ASSET, PRICE, new BigDecimal("7.5"), RISK_50K,
                new BigDecimal("0.50"), new BigDecimal("250000"));
        assertThat(qty).isEqualTo(25);   // 리스크 수량(66) > 상한(25) → 상한 캡 = 현행과 동일
    }

    @Test
    void narrowStop_reducesBelowCurrentCap() {
        // 대자산 계좌: 현행상한 5,000만(=자산 1억×50%) → 현행 5,000주.
        // 손절폭 7.5% → 리스크 수량 66주 — 현행보다 대폭 축소(리스크 균등의 목적).
        int qty = PositionSizer.judge(new BigDecimal("100000000"), PRICE, new BigDecimal("7.5"),
                RISK_50K, new BigDecimal("0.50"), new BigDecimal("50000000"));
        assertThat(qty).isEqualTo(66);
    }

    @Test
    void neverExceedsCurrentQuantity() {
        // 아주 좁은 손절(0.5%) → 리스크 수량 50,000÷50 = 1,000주. 현행상한 25만원 = 25주 → 25주 캡.
        int qty = PositionSizer.judge(ASSET, PRICE, new BigDecimal("0.5"), RISK_50K,
                new BigDecimal("0.50"), new BigDecimal("250000"));
        int currentQty = new BigDecimal("250000").divide(PRICE).intValue();
        assertThat(qty).isEqualTo(currentQty).isEqualTo(25);
    }

    @Test
    void missingStopPct_fallsBackToCurrent() {
        // ATR 결측(§4c) → 현행 고정 금액 폴백 = 25만÷1만 = 25주 (확대 금지)
        assertThat(PositionSizer.judge(ASSET, PRICE, null, RISK_50K,
                new BigDecimal("0.50"), new BigDecimal("250000"))).isEqualTo(25);
        assertThat(PositionSizer.judge(ASSET, PRICE, BigDecimal.ZERO, RISK_50K,
                new BigDecimal("0.50"), new BigDecimal("250000"))).isEqualTo(25);
    }

    @Test
    void missingRiskBudget_fallsBackToCurrent() {
        assertThat(PositionSizer.judge(ASSET, PRICE, new BigDecimal("7.5"), null,
                new BigDecimal("0.50"), new BigDecimal("250000"))).isEqualTo(25);
        assertThat(PositionSizer.judge(ASSET, PRICE, new BigDecimal("7.5"), new BigDecimal("-1"),
                new BigDecimal("0.50"), new BigDecimal("250000"))).isEqualTo(25);
    }

    @Test
    void invalidPriceOrCap_zero() {
        assertThat(PositionSizer.judge(ASSET, null, new BigDecimal("7.5"), RISK_50K,
                new BigDecimal("0.50"), new BigDecimal("250000"))).isZero();
        assertThat(PositionSizer.judge(ASSET, BigDecimal.ZERO, new BigDecimal("7.5"), RISK_50K,
                new BigDecimal("0.50"), new BigDecimal("250000"))).isZero();
        assertThat(PositionSizer.judge(ASSET, PRICE, new BigDecimal("7.5"), RISK_50K,
                new BigDecimal("0.50"), null)).isZero();
        assertThat(PositionSizer.judge(ASSET, PRICE, new BigDecimal("7.5"), RISK_50K,
                new BigDecimal("0.50"), BigDecimal.ZERO)).isZero();
    }

    @Test
    void riskQtySmallerThanCap_used() {
        // 손절폭 10% → 주당 손실 1,000원 → 리스크 수량 50주 > ... 현행상한 100만원 = 100주 → 50주(리스크가 바인딩)
        int qty = PositionSizer.judge(new BigDecimal("2000000"), PRICE, new BigDecimal("10"),
                RISK_50K, new BigDecimal("0.50"), new BigDecimal("1000000"));
        assertThat(qty).isEqualTo(50);
    }

    @Test
    void expensiveStock_zeroSharesAffordable() {
        // 리스크 수량 0 (주당 손실 > riskBudget) → 0주 (최소 1주 강제 매수 안 함)
        int qty = PositionSizer.judge(ASSET, new BigDecimal("1000000"), new BigDecimal("10"),
                RISK_50K, new BigDecimal("0.50"), new BigDecimal("250000"));
        assertThat(qty).isZero();
    }

    @Test
    void nullMaxPositionPct_currentCapOnly() {
        // maxPositionPct null → 현행상한만 적용 (25만 → 25주 캡)
        int qty = PositionSizer.judge(ASSET, PRICE, new BigDecimal("7.5"), RISK_50K,
                null, new BigDecimal("250000"));
        assertThat(qty).isEqualTo(25);
    }
}
