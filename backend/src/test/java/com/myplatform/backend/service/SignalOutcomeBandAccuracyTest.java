package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat;
import com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat;
import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건부 적중률 집계 (V30) — 순수 함수 단위 테스트.
 *
 * aggregateBands: signalScore 구간(55~64/65~74/75~84/85~100)별 hit-rate.
 * aggregateCategories: 시그널 시점 카테고리 점수 강세(≥15) 표본별 hit-rate.
 */
class SignalOutcomeBandAccuracyTest {

    private SignalOutcome outcome(Integer score, boolean hit, String pct) {
        return SignalOutcome.builder()
                .signalType(score != null && score >= 75 ? "STRONG_BUY" : "BUY")
                .stockCode("005930")
                .signalDate(LocalDate.of(2026, 6, 1))
                .signalScore(score)
                .priceAtSignal(new BigDecimal("10000"))
                .pctChange3d(pct == null ? null : new BigDecimal(pct))
                .hit(hit)
                .build();
    }

    @Test
    @DisplayName("bands: 점수 78 hit / 점수 90 miss → 75~84 구간 100%, 85~100 구간 0%")
    void bands_separateByScore() {
        List<BandStat> bands = SignalOutcomeService.aggregateBands(List.of(
                outcome(78, true, "4.00"),
                outcome(90, false, "-1.00")));

        BandStat b75 = bands.stream().filter(b -> b.getBand().equals("75~84")).findFirst().orElseThrow();
        BandStat b85 = bands.stream().filter(b -> b.getBand().equals("85~100")).findFirst().orElseThrow();

        assertThat(b75.getTotalSignals()).isEqualTo(1);
        assertThat(b75.getHitRate()).isEqualByComparingTo("100");
        assertThat(b75.getAvgPctChange()).isEqualByComparingTo("4.00");
        assertThat(b85.getHitRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("bands: 4 구간 항상 반환, 표본 0 구간은 hitRate 0 / avg null")
    void bands_emptyBandsSafe() {
        List<BandStat> bands = SignalOutcomeService.aggregateBands(List.of(outcome(60, true, "3.50")));

        assertThat(bands).hasSize(4);
        BandStat b55 = bands.get(0);
        assertThat(b55.getBand()).isEqualTo("55~64");
        assertThat(b55.getTotalSignals()).isEqualTo(1);
        BandStat b65 = bands.get(1);
        assertThat(b65.getTotalSignals()).isZero();
        assertThat(b65.getHitRate()).isEqualByComparingTo("0");
        assertThat(b65.getAvgPctChange()).isNull();
    }

    @Test
    @DisplayName("bands: signalScore null 행은 모든 구간에서 제외")
    void bands_nullScoreExcluded() {
        List<BandStat> bands = SignalOutcomeService.aggregateBands(List.of(outcome(null, true, "5.00")));

        assertThat(bands).allSatisfy(b -> assertThat(b.getTotalSignals()).isZero());
    }

    @Test
    @DisplayName("categories: 수급 16(강) hit + 기술 10(약) → 수급 표본 1건 100%, 기술 표본 0건")
    void categories_strongOnly() {
        SignalOutcome s = outcome(80, true, "4.00");
        s.setSupplyDemandAtSignal(16);
        s.setTechnicalAtSignal(10);
        s.setEarningsAtSignal(15);
        // sectorMomentumAtSignal null — V30 이전 행 시뮬레이션

        List<CategoryStat> cats = SignalOutcomeService.aggregateCategories(List.of(s));

        CategoryStat supply = cats.stream().filter(c -> c.getKey().equals("supplyDemand")).findFirst().orElseThrow();
        CategoryStat tech = cats.stream().filter(c -> c.getKey().equals("technical")).findFirst().orElseThrow();
        CategoryStat sector = cats.stream().filter(c -> c.getKey().equals("sectorMomentum")).findFirst().orElseThrow();

        assertThat(supply.getTotalSignals()).isEqualTo(1);
        assertThat(supply.getHitRate()).isEqualByComparingTo("100");
        assertThat(tech.getTotalSignals()).isZero();   // 15 미만 — 강세 표본 아님
        assertThat(sector.getTotalSignals()).isZero(); // null — V30 이전 행 제외
    }

    @Test
    @DisplayName("categories: hit/miss 혼합 → hitRate 50%")
    void categories_mixedHitRate() {
        SignalOutcome hit = outcome(80, true, "4.00");
        hit.setSupplyDemandAtSignal(18);
        SignalOutcome miss = outcome(76, false, "-2.00");
        miss.setSupplyDemandAtSignal(15);

        List<CategoryStat> cats = SignalOutcomeService.aggregateCategories(List.of(hit, miss));

        CategoryStat supply = cats.stream().filter(c -> c.getKey().equals("supplyDemand")).findFirst().orElseThrow();
        assertThat(supply.getTotalSignals()).isEqualTo(2);
        assertThat(supply.getHitRate()).isEqualByComparingTo("50");
        assertThat(supply.getAvgPctChange()).isEqualByComparingTo("1.00");
    }
}
