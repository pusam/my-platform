package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto.MarketCondition;
import com.myplatform.backend.dto.MarketTimingDto.MarketStatusDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 당일 등락비 실시간 보충 (점검 수정 2026-06-11) — 핵심 계약:
 *
 * <p>당일 등락비(하루짜리 adv/dec 비율)는 ADR(20일 누적)과 스케일이 달라, ADR 임계
 * (과열 120 / 침체 80)로 판정하면 평범한 상승일(등락비 150)도 '과열'로 오판한다.
 * 따라서 등락비 보충은 <b>dailyRatio 만 채우고 ADR 기반 condition 은 보존</b>해야 한다.
 * (과거 코드는 determineCondition(당일등락비)로 condition 을 덮어써 장중 진단이 극단으로 출렁였음)
 */
class MarketTimingDailyRatioTest {

    private MarketStatusDto status(MarketCondition condition) {
        return MarketStatusDto.builder()
                .marketType("KOSPI")
                .adr20(new BigDecimal("95"))
                .condition(condition)
                .build();
    }

    @Test
    @DisplayName("등락비 150(평범한 상승일) 보충 — ADR 기반 NORMAL condition 을 덮어쓰지 않는다")
    void applyDailyRatio_preservesAdrCondition() {
        MarketStatusDto s = status(MarketCondition.NORMAL);

        BigDecimal ratio = MarketTimingService.applyDailyRatio(s, 600, 400); // adv/dec = 1.5

        assertThat(ratio).isEqualByComparingTo("150");
        assertThat(s.getDailyRatio()).isEqualByComparingTo("150");
        // 과거 버그: 150 ≥ ADR_OVERHEATED(120) → OVERHEATED 로 덮어씀
        assertThat(s.getCondition()).isEqualTo(MarketCondition.NORMAL);
    }

    @Test
    @DisplayName("등락비 70(평범한 하락일) 보충 — OVERSOLD 로 강등시키지 않는다")
    void applyDailyRatio_doesNotDowngradeOnDownDay() {
        MarketStatusDto s = status(MarketCondition.NORMAL);

        MarketTimingService.applyDailyRatio(s, 350, 500); // adv/dec = 0.7

        assertThat(s.getDailyRatio()).isEqualByComparingTo("70");
        assertThat(s.getCondition()).isEqualTo(MarketCondition.NORMAL);
    }

    @Test
    @DisplayName("하락 종목 0 (분모 0) → 보충 스킵, 기존 상태 유지")
    void applyDailyRatio_zeroDeclinersSkips() {
        MarketStatusDto s = status(MarketCondition.OVERSOLD);

        BigDecimal ratio = MarketTimingService.applyDailyRatio(s, 500, 0);

        assertThat(ratio).isNull();
        assertThat(s.getDailyRatio()).isNull();
        assertThat(s.getCondition()).isEqualTo(MarketCondition.OVERSOLD);
    }

    @Test
    @DisplayName("status null → no-op")
    void applyDailyRatio_nullStatus() {
        assertThat(MarketTimingService.applyDailyRatio(null, 500, 400)).isNull();
    }

    @Test
    @DisplayName("등락비 계산식 보존 — divide(scale 2)×100: 457/333 → 1.37 → 137.00")
    void applyDailyRatio_keepsOriginalFormula() {
        MarketStatusDto s = status(MarketCondition.NORMAL);

        BigDecimal ratio = MarketTimingService.applyDailyRatio(s, 457, 333);

        assertThat(ratio).isEqualByComparingTo("137.00");
    }
}
