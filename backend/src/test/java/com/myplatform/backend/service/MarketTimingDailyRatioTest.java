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
    @DisplayName("등락비는 ×100 뒤에 소수 둘째 자리로 — 457/333 = 137.24(예전엔 1.37 로 먼저 잘라 137.00)")
    void applyDailyRatio_roundsAfterScaling() {
        MarketStatusDto s = status(MarketCondition.NORMAL);

        BigDecimal ratio = MarketTimingService.applyDailyRatio(s, 457, 333);

        assertThat(ratio).isEqualByComparingTo("137.24");
    }

    /**
     * 2026-11-09 16:30 운영 수집값 — 코스피 상승 340·하락 530, 코스닥 상승 837·하락 838.
     * 비율을 소수 둘째 자리(0.64·1.00)로 먼저 자르고 100 을 곱해 '오늘' 탭 진단 문장이 '코스피 등락비 64.0 · 코스닥 100.0' 이었다
     * — 하락 종목이 더 많은 날의 코스닥이 '상승 = 하락'으로 읽혔다(실제 99.88).
     */
    @Test
    @DisplayName("운영 11/9 등락 수 — 코스피 64.15, 코스닥 99.88(100.00 아님)")
    void dailyRatioOf_realCountsOf20261109() {
        assertThat(MarketTimingService.dailyRatioOf(340, 530)).isEqualByComparingTo("64.15");
        assertThat(MarketTimingService.dailyRatioOf(837, 838)).isEqualByComparingTo("99.88");
        assertThat(MarketTimingService.dailyRatioOf(837, 838)).isLessThan(new BigDecimal("100"));
    }

    @Test
    @DisplayName("분모(하락 종목) 0 이하면 모름(null) — 상승 0 은 0.00 이라는 값")
    void dailyRatioOf_edges() {
        assertThat(MarketTimingService.dailyRatioOf(500, 0)).isNull();
        assertThat(MarketTimingService.dailyRatioOf(0, 900)).isEqualByComparingTo("0.00");
    }
}
