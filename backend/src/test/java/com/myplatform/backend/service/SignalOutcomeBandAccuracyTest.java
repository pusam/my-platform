package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat;
import com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat;
import com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat;
import com.myplatform.backend.dto.SignalBandAccuracyDto.RegimeStat;
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
 * aggregateCategories: 시그널 시점 카테고리 점수 강세 표본별 hit-rate — 카테고리별 임계(실적≥20/수급≥15/기술≥13/섹터≥14, P1-6 실측).
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
    @DisplayName("catalysts(V31): 호재 hit / 재료없음 miss / 미수집(null) 제외")
    void catalysts_groupedByDirection() {
        SignalOutcome positive = outcome(80, true, "4.00");
        positive.setCatalystTypeAtSignal("ORDER_WIN");
        positive.setCatalystDirectionAtSignal("POSITIVE");
        SignalOutcome none = outcome(60, false, "-1.00");
        none.setCatalystTypeAtSignal("NONE");
        none.setCatalystDirectionAtSignal("NONE");
        SignalOutcome uncollected = outcome(70, true, "5.00"); // catalyst null — V31 이전 행

        List<CatalystStat> cats = SignalOutcomeService.aggregateCatalysts(
                List.of(positive, none, uncollected));

        CatalystStat pos = cats.stream().filter(c -> c.getDirection().equals("POSITIVE")).findFirst().orElseThrow();
        CatalystStat noneStat = cats.stream().filter(c -> c.getDirection().equals("NONE")).findFirst().orElseThrow();

        assertThat(cats).hasSize(4); // POSITIVE/NEGATIVE/NEUTRAL/NONE 항상 반환
        assertThat(pos.getTotalSignals()).isEqualTo(1);
        assertThat(pos.getHitRate()).isEqualByComparingTo("100");
        assertThat(pos.getLabel()).isEqualTo("호재");
        assertThat(noneStat.getTotalSignals()).isEqualTo(1);
        assertThat(noneStat.getHitRate()).isEqualByComparingTo("0");
        // 미수집 행은 어느 그룹에도 안 들어감: 전체 표본 합 = 2
        long sum = cats.stream().mapToLong(CatalystStat::getTotalSignals).sum();
        assertThat(sum).isEqualTo(2);
    }

    @Test
    @DisplayName("regimes(V32): 상승장 hit / 하락장 miss / 미수집(null) 제외")
    void regimes_groupedByRegime() {
        SignalOutcome bull = outcome(80, true, "4.00");
        bull.setRegimeAtSignal("BULL");
        SignalOutcome bear = outcome(76, false, "-2.00");
        bear.setRegimeAtSignal("BEAR");
        SignalOutcome uncollected = outcome(70, true, "5.00"); // regime null — V32 이전 행

        List<RegimeStat> regimes = SignalOutcomeService.aggregateRegimes(
                List.of(bull, bear, uncollected));

        RegimeStat bullStat = regimes.stream().filter(r -> r.getRegime().equals("BULL")).findFirst().orElseThrow();
        RegimeStat bearStat = regimes.stream().filter(r -> r.getRegime().equals("BEAR")).findFirst().orElseThrow();
        RegimeStat sideStat = regimes.stream().filter(r -> r.getRegime().equals("SIDEWAYS")).findFirst().orElseThrow();

        assertThat(regimes).hasSize(3); // BULL/BEAR/SIDEWAYS 항상 반환
        assertThat(bullStat.getTotalSignals()).isEqualTo(1);
        assertThat(bullStat.getHitRate()).isEqualByComparingTo("100");
        assertThat(bullStat.getLabel()).isEqualTo("상승장");
        assertThat(bearStat.getHitRate()).isEqualByComparingTo("0");
        assertThat(sideStat.getTotalSignals()).isZero();
        // 미수집 행은 어느 국면에도 안 들어감: 전체 표본 합 = 2
        long sum = regimes.stream().mapToLong(RegimeStat::getTotalSignals).sum();
        assertThat(sum).isEqualTo(2);
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

    @Test
    @DisplayName("categories: 카테고리별 임계 경계 — 섹터≥14/실적≥20/기술≥13/수급≥15만 강세로 잡힘 (P1-6 측정 정합)")
    void categories_perCategoryThreshold() {
        // 경계 바로 위 = 강세(각 1건 잡혀야)
        SignalOutcome strong = outcome(80, true, "4.00");
        strong.setSectorMomentumAtSignal(14);   // ≥14
        strong.setEarningsAtSignal(20);          // ≥20
        strong.setTechnicalAtSignal(13);         // ≥13
        strong.setSupplyDemandAtSignal(15);      // ≥15
        // 경계 바로 아래 = 약세(하나도 안 잡혀야) — 단일 15였다면 실적19·기술14 등이 잘못 잡히거나 빠졌음
        SignalOutcome weak = outcome(60, false, "-1.00");
        weak.setSectorMomentumAtSignal(13);      // 14 미만
        weak.setEarningsAtSignal(19);            // 20 미만 (단일15면 잘못 강세)
        weak.setTechnicalAtSignal(12);           // 13 미만
        weak.setSupplyDemandAtSignal(14);        // 15 미만

        List<CategoryStat> cats = SignalOutcomeService.aggregateCategories(List.of(strong, weak));
        java.util.Map<String, CategoryStat> byKey = cats.stream()
                .collect(java.util.stream.Collectors.toMap(CategoryStat::getKey, c -> c));

        // 각 카테고리: 경계 위 1건만(약세행 제외) + strongThreshold 카테고리별
        assertThat(byKey.get("sectorMomentum").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("sectorMomentum").getStrongThreshold()).isEqualTo(14);
        assertThat(byKey.get("earnings").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("earnings").getStrongThreshold()).isEqualTo(20);
        assertThat(byKey.get("technical").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("technical").getStrongThreshold()).isEqualTo(13);
        assertThat(byKey.get("supplyDemand").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("supplyDemand").getStrongThreshold()).isEqualTo(15);
    }
}
