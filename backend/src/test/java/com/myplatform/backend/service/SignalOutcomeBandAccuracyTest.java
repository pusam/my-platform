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

    private SignalOutcome typed(String signalType) {
        return SignalOutcome.builder()
                .signalType(signalType).stockCode("005930")
                .signalDate(LocalDate.of(2026, 6, 26)).signalScore(60)
                .priceAtSignal(new BigDecimal("10000")).hit(true).build();
    }

    @Test
    @DisplayName("filterBoardSignals — 보드(STRONG_BUY/BUY)만, 다른 소스(AI_*/COMPOSITE/SURGE) 제외")
    void filterBoardSignals_isolatesBoard() {
        List<SignalOutcome> kept = SignalOutcomeService.filterBoardSignals(List.of(
                typed("BUY"), typed("STRONG_BUY"),
                typed("AI_BUY"), typed("COMPOSITE_5OF5"), typed("SURGE_HOT")));

        assertThat(kept).hasSize(2);
        assertThat(kept).allMatch(s -> s.getSignalType().equals("BUY") || s.getSignalType().equals("STRONG_BUY"));
    }

    @Test
    @DisplayName("resolveAccuracyFrom — phase-38 컷오프(6/25) 이전이면 컷오프로 클램프, 이후면 그대로")
    void resolveAccuracyFrom_clampsToPhase38() {
        LocalDate now = LocalDate.of(2026, 7, 10);
        assertThat(SignalOutcomeService.resolveAccuracyFrom(90, now)).isEqualTo(LocalDate.of(2026, 6, 25)); // 4/11 → 클램프
        assertThat(SignalOutcomeService.resolveAccuracyFrom(5, now)).isEqualTo(LocalDate.of(2026, 7, 5));   // 컷오프 이후 그대로
        assertThat(SignalOutcomeService.resolveAccuracyFrom(0, now)).isEqualTo(LocalDate.of(2026, 6, 25));  // days<1→90→클램프
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

    // ================================================================
    // V49 채널 스냅샷 — positionBand / aggregateChannels / computeChannelFromHistory
    // ================================================================

    @Test
    @DisplayName("positionBand 경계 — 33 하단 / 34 중단 / 66 중단 / 67 상단")
    void positionBand_boundaries() {
        assertThat(SignalOutcomeService.positionBand(0)).isEqualTo("LOW");
        assertThat(SignalOutcomeService.positionBand(33)).isEqualTo("LOW");
        assertThat(SignalOutcomeService.positionBand(34)).isEqualTo("MID");
        assertThat(SignalOutcomeService.positionBand(66)).isEqualTo("MID");
        assertThat(SignalOutcomeService.positionBand(67)).isEqualTo("HIGH");
        assertThat(SignalOutcomeService.positionBand(100)).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("channels: 방향×위치 9칸 분리 집계 — UP 하단 hit / UP 상단 miss 가 다른 칸, NULL(미수집) 제외")
    void channels_bucketByDirectionAndPosition() {
        SignalOutcome upLow = outcome(80, true, "4.00");
        upLow.setChannelDirectionAtSignal("UP");
        upLow.setChannelPositionAtSignal(15);    // 하단
        SignalOutcome upHigh = outcome(80, false, "-2.00");
        upHigh.setChannelDirectionAtSignal("UP");
        upHigh.setChannelPositionAtSignal(90);   // 상단
        SignalOutcome noChannel = outcome(80, true, "1.00");   // 미수집 → 어느 칸에도 안 잡힘

        var channels = SignalOutcomeService.aggregateChannels(List.of(upLow, upHigh, noChannel));

        assertThat(channels).hasSize(9);   // 3방향 × 3밴드
        var byKey = channels.stream().collect(java.util.stream.Collectors.toMap(
                c -> c.getDirection() + "-" + c.getPositionBand(), c -> c));
        assertThat(byKey.get("UP-LOW").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("UP-LOW").getHitRate()).isEqualByComparingTo("100");
        assertThat(byKey.get("UP-HIGH").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("UP-HIGH").getHitRate()).isEqualByComparingTo("0");
        assertThat(byKey.get("DOWN-LOW").getTotalSignals()).isEqualTo(0);   // 표본 0 = n 으로 정직 노출
    }

    @Test
    @DisplayName("computeChannelFromHistory: 최신순 30봉 상승 추세 → UP, 봉 부족(<10)/빈 입력 → null(§4c)")
    void computeChannelFromHistory_basics() {
        java.util.List<com.myplatform.backend.entity.StockPriceHistory> newestFirst = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double close = 130 - i;   // 최신 130 → 과거 101
            newestFirst.add(com.myplatform.backend.entity.StockPriceHistory.builder()
                    .stockCode("005930").tradeDate(LocalDate.of(2026, 7, 14).minusDays(i))
                    .highPrice(BigDecimal.valueOf(close + 1)).lowPrice(BigDecimal.valueOf(close - 1))
                    .closePrice(BigDecimal.valueOf(close)).build());
        }
        var ch = SignalOutcomeService.computeChannelFromHistory(newestFirst);
        assertThat(ch).isNotNull();
        assertThat(ch.direction()).isEqualTo("UP");
        assertThat(ch.position()).isBetween(0.0, 1.0);

        assertThat(SignalOutcomeService.computeChannelFromHistory(newestFirst.subList(0, 5))).isNull();
        assertThat(SignalOutcomeService.computeChannelFromHistory(java.util.List.of())).isNull();
        assertThat(SignalOutcomeService.computeChannelFromHistory(null)).isNull();
    }

    // ================================================================
    // V50 KOSPI 지수 채널 스냅샷 — aggregateIndexChannels (유효표본 = distinctDays)
    // ================================================================

    /** 지수 채널 필드를 세팅한 행 — signalDate 로 고유일 제어. */
    private SignalOutcome indexRow(LocalDate date, String dir, int pos, boolean hit) {
        SignalOutcome s = SignalOutcome.builder()
                .signalType("BUY").stockCode("005930").signalDate(date).signalScore(60)
                .priceAtSignal(new BigDecimal("10000")).pctChange3d(new BigDecimal(hit ? "4.00" : "-1.00")).hit(hit)
                .build();
        s.setIndexChannelDirectionAtSignal(dir);
        s.setIndexChannelPositionAtSignal(pos);
        return s;
    }

    @Test
    @DisplayName("indexChannels: 방향×위치 9칸 분리 + NULL(미수집) 제외")
    void indexChannels_bucketByDirectionAndPosition() {
        SignalOutcome uncollected = SignalOutcome.builder().signalType("BUY").stockCode("000660")
                .signalDate(LocalDate.of(2026, 7, 3)).priceAtSignal(new BigDecimal("10000")).hit(true)
                .build();   // index 채널 필드 미세팅 = NULL(미수집)
        var rows = List.of(
                indexRow(LocalDate.of(2026, 7, 1), "UP", 15, true),    // UP 하단
                indexRow(LocalDate.of(2026, 7, 2), "UP", 90, false),   // UP 상단
                uncollected);

        var channels = SignalOutcomeService.aggregateIndexChannels(rows);

        assertThat(channels).hasSize(9);   // 3방향 × 3밴드
        var byKey = channels.stream().collect(java.util.stream.Collectors.toMap(
                c -> c.getDirection() + "-" + c.getPositionBand(), c -> c));
        assertThat(byKey.get("UP-LOW").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("UP-LOW").getHitRate()).isEqualByComparingTo("100");
        assertThat(byKey.get("UP-HIGH").getTotalSignals()).isEqualTo(1);
        assertThat(byKey.get("UP-HIGH").getHitRate()).isEqualByComparingTo("0");
        assertThat(byKey.get("DOWN-LOW").getTotalSignals()).isZero();   // 미수집 행은 어느 칸에도 안 잡힘
    }

    @Test
    @DisplayName("indexChannels 유효표본 = distinctDays: 같은 날 20행이라도 고유일 1 → insufficientSample=true(§4c 위장 금지)")
    void indexChannels_sameDayManyRows_insufficientByDistinctDays() {
        // 같은 날(동일 지수값) 20행 — 행 수로 재면 표본충분처럼 보이지만 진짜 독립 표본은 1일뿐.
        java.util.List<SignalOutcome> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(indexRow(LocalDate.of(2026, 7, 1), "UP", 15, i % 2 == 0));
        }
        var up = SignalOutcomeService.aggregateIndexChannels(rows).stream()
                .filter(c -> c.getDirection().equals("UP") && c.getPositionBand().equals("LOW")).findFirst().orElseThrow();

        assertThat(up.getTotalSignals()).isEqualTo(20);   // 행 수는 20
        assertThat(up.getDistinctDays()).isEqualTo(1);     // 진짜 독립 표본은 1일
        assertThat(up.isInsufficientSample()).isTrue();    // distinctDays<10 → 표본부족(행 수로 위장 안 함)
    }

    @Test
    @DisplayName("indexChannels 유효표본 경계: 고유일 9 → 부족 / 10 → 충분")
    void indexChannels_distinctDaysBoundary() {
        java.util.List<SignalOutcome> nine = new java.util.ArrayList<>();
        for (int d = 1; d <= 9; d++) nine.add(indexRow(LocalDate.of(2026, 7, d), "FLAT", 50, true));
        var flat9 = SignalOutcomeService.aggregateIndexChannels(nine).stream()
                .filter(c -> c.getDirection().equals("FLAT") && c.getPositionBand().equals("MID")).findFirst().orElseThrow();
        assertThat(flat9.getDistinctDays()).isEqualTo(9);
        assertThat(flat9.isInsufficientSample()).isTrue();

        java.util.List<SignalOutcome> ten = new java.util.ArrayList<>();
        for (int d = 1; d <= 10; d++) ten.add(indexRow(LocalDate.of(2026, 7, d), "FLAT", 50, true));
        var flat10 = SignalOutcomeService.aggregateIndexChannels(ten).stream()
                .filter(c -> c.getDirection().equals("FLAT") && c.getPositionBand().equals("MID")).findFirst().orElseThrow();
        assertThat(flat10.getDistinctDays()).isEqualTo(10);
        assertThat(flat10.isInsufficientSample()).isFalse();   // 경계 10 = 충분
    }
}
