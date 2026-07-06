package com.myplatform.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * ATR×2.5 청산 레벨 테스트 — 손익비 5/3(현행 -3/+5) 유지가 백테스트와의 계약.
 */
class AtrExitRuleTest {

    @Test
    void levels_basic() {
        // 진입 10,000 · ATR 300 → 손절폭 2.5×300/10000 = 7.5% → stop -7.5 / target +12.5
        AtrExitRule.Levels levels = AtrExitRule.judge(new BigDecimal("10000"), new BigDecimal("300"));
        assertThat(levels).isNotNull();
        assertThat(levels.stopPct()).isEqualByComparingTo("-7.5");
        assertThat(levels.targetPct()).isEqualByComparingTo("12.5");
    }

    @Test
    void rewardRiskRatio_matchesCurrentFiveOverThree() {
        AtrExitRule.Levels levels = AtrExitRule.judge(new BigDecimal("50000"), new BigDecimal("1200"));
        // 손익비 = target ÷ |stop| = 5/3 (백테스트 riskReward 1.667 과 동일)
        BigDecimal ratio = levels.targetPct().divide(levels.stopPct().abs(), 4, java.math.RoundingMode.HALF_UP);
        assertThat(ratio).isEqualByComparingTo("1.6667");
    }

    @Test
    void missingInputs_null() {
        assertThat(AtrExitRule.judge(null, new BigDecimal("300"))).isNull();
        assertThat(AtrExitRule.judge(new BigDecimal("10000"), null)).isNull();
        assertThat(AtrExitRule.judge(BigDecimal.ZERO, new BigDecimal("300"))).isNull();
        assertThat(AtrExitRule.judge(new BigDecimal("10000"), BigDecimal.ZERO)).isNull();
        assertThat(AtrExitRule.judge(new BigDecimal("10000"), new BigDecimal("-5"))).isNull();
    }
}
