package com.myplatform.backend.service;

import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.CategoryCell;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.CategoryTrend;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.RegimeGroup;
import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 예측력 측정 집계 — 순수 함수 단위 테스트 (P1-6 상설화).
 *
 * regime 파티션(UNKNOWN 분리)·표본부족(n<10) 명시·이번주 vs 누적 델타·수급 역상관 스트릭/경고 검증.
 */
class WeeklyAccuracyAggregatorTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 29);
    private static final LocalDate WEEK_END = LocalDate.of(2026, 7, 5);

    /** 강세 수급(supply≥15) 시그널 — regime/hit/alpha 지정. */
    private SignalOutcome supplySig(String regime, boolean hit, String alpha) {
        return SignalOutcome.builder()
                .signalType("BUY").stockCode("005930").signalDate(WEEK_START)
                .signalScore(60).priceAtSignal(new BigDecimal("10000"))
                .regimeAtSignal(regime)
                .supplyDemandAtSignal(16)
                .pctChange3d(new BigDecimal("1.00"))
                .alpha3d(alpha == null ? null : new BigDecimal(alpha))
                .hit(hit).build();
    }

    private List<SignalOutcome> nSupply(String regime, int count, boolean hit, String alpha) {
        List<SignalOutcome> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(supplySig(regime, hit, alpha));
        return rows;
    }

    /** 변동성 국면(V46) 지정 시그널 — NORMAL/HIGH_VOL 분리 집계 검증용. */
    private SignalOutcome volSig(String volRegime, boolean hit, String alpha) {
        return SignalOutcome.builder()
                .signalType("BUY").stockCode("005930").signalDate(WEEK_START)
                .signalScore(60).priceAtSignal(new BigDecimal("10000"))
                .volRegimeAtSignal(volRegime)
                .supplyDemandAtSignal(16)
                .pctChange3d(new BigDecimal("1.00"))
                .alpha3d(alpha == null ? null : new BigDecimal(alpha))
                .hit(hit).build();
    }

    private List<SignalOutcome> nVol(String volRegime, int count, boolean hit, String alpha) {
        List<SignalOutcome> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(volSig(volRegime, hit, alpha));
        return rows;
    }

    private Map<String, RegimeGroup> groupsByRegime(List<RegimeGroup> groups) {
        return groups.stream().collect(Collectors.toMap(RegimeGroup::getRegime, g -> g));
    }

    private CategoryCell cell(RegimeGroup g, String key) {
        return g.getCategories().stream().filter(c -> c.getKey().equals(key)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------
    // regime 파티션
    // ---------------------------------------------------------------

    @Test
    @DisplayName("partitionByRegime: NULL/미정의 regime → UNKNOWN 버킷, 항상 4버킷 키 존재")
    void partition_unknownBucket() {
        List<SignalOutcome> rows = new ArrayList<>();
        rows.add(supplySig("BULL", true, "1.0"));
        rows.add(supplySig(null, true, "1.0"));      // NULL → UNKNOWN
        rows.add(supplySig("", true, "1.0"));         // blank → UNKNOWN
        rows.add(supplySig("GARBAGE", true, "1.0"));  // 미정의 → UNKNOWN

        Map<String, List<SignalOutcome>> parts = WeeklyAccuracyAggregator.partitionByRegime(rows);

        assertThat(parts.keySet()).containsExactlyInAnyOrder("BULL", "BEAR", "SIDEWAYS", "UNKNOWN");
        assertThat(parts.get("BULL")).hasSize(1);
        assertThat(parts.get("UNKNOWN")).hasSize(3);
        assertThat(parts.get("BEAR")).isEmpty();
        assertThat(parts.get("SIDEWAYS")).isEmpty();
    }

    // ---------------------------------------------------------------
    // 변동성 국면(V46) 파티션 — regime 과 별개 축
    // ---------------------------------------------------------------

    @Test
    @DisplayName("partitionByVolRegime: NULL/미정의 → UNKNOWN, 항상 3버킷(NORMAL/HIGH_VOL/UNKNOWN)")
    void partitionVol_unknownBucket() {
        List<SignalOutcome> rows = new ArrayList<>();
        rows.add(volSig("NORMAL", true, "1.0"));
        rows.add(volSig("HIGH_VOL", true, "1.0"));
        rows.add(volSig(null, true, "1.0"));         // NULL → UNKNOWN(§4c)
        rows.add(volSig("GARBAGE", true, "1.0"));    // 미정의 → UNKNOWN

        Map<String, List<SignalOutcome>> parts = WeeklyAccuracyAggregator.partitionByVolRegime(rows);

        assertThat(parts.keySet()).containsExactlyInAnyOrder("NORMAL", "HIGH_VOL", "UNKNOWN");
        assertThat(parts.get("NORMAL")).hasSize(1);
        assertThat(parts.get("HIGH_VOL")).hasSize(1);
        assertThat(parts.get("UNKNOWN")).hasSize(2);
    }

    @Test
    @DisplayName("buildVolRegimeGroups: 3버킷(빈 버킷 포함), NORMAL/HIGH_VOL 분리 집계")
    void buildVol_threeBuckets() {
        List<SignalOutcome> rows = new ArrayList<>();
        rows.addAll(nVol("HIGH_VOL", 3, false, "-1.0"));
        rows.addAll(nVol("NORMAL", 2, true, "1.0"));

        Map<String, RegimeGroup> byV = groupsByRegime(WeeklyAccuracyAggregator.buildVolRegimeGroups(rows));

        assertThat(byV.keySet()).containsExactlyInAnyOrder("NORMAL", "HIGH_VOL", "UNKNOWN");
        assertThat(byV.get("HIGH_VOL").getTotalSignals()).isEqualTo(3);
        assertThat(byV.get("NORMAL").getTotalSignals()).isEqualTo(2);
        assertThat(byV.get("UNKNOWN").getTotalSignals()).isZero();
        assertThat(byV.get("HIGH_VOL").getCategories()).isNotEmpty();   // regime 그룹과 동일 형태 재사용
    }

    @Test
    @DisplayName("assembleReport: volRegimeGroups 포함(기존 regimeGroups 축과 병존)")
    void assemble_includesVolRegimeGroups() {
        List<SignalOutcome> weekly = nVol("HIGH_VOL", 2, false, "-1.0");

        WeeklySignalAccuracyDto dto = WeeklyAccuracyAggregator.assembleReport(
                WEEK_START, WEEK_END, weekly, weekly, List.of());

        assertThat(dto.getVolRegimeGroups()).isNotNull();
        assertThat(groupsByRegime(dto.getVolRegimeGroups()).keySet())
                .containsExactlyInAnyOrder("NORMAL", "HIGH_VOL", "UNKNOWN");
        assertThat(dto.getRegimeGroups()).isNotNull();   // 기존 regime 축 유지
    }

    // ---------------------------------------------------------------
    // P3-11: 버킷 유효표본 = distinctDays (지수 축 — 같은 날 시그널 동일값)
    // ---------------------------------------------------------------

    private SignalOutcome volSigOn(LocalDate date, String volRegime, boolean hit) {
        return SignalOutcome.builder()
                .signalType("BUY").stockCode("005930").signalDate(date)
                .signalScore(60).priceAtSignal(new BigDecimal("10000"))
                .volRegimeAtSignal(volRegime).supplyDemandAtSignal(16)
                .pctChange3d(new BigDecimal("1.00")).alpha3d(new BigDecimal(hit ? "1.0" : "-1.0"))
                .hit(hit).build();
    }

    @Test
    @DisplayName("buildVolRegimeGroups 유효표본 = distinctDays: 같은 날 HIGH_VOL 15행 → 고유일 1 → insufficientSample=true(P3-11)")
    void buildVol_sameDayManyRows_insufficientByDistinctDays() {
        List<SignalOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) rows.add(volSigOn(WEEK_START, "HIGH_VOL", i % 2 == 0));

        RegimeGroup high = groupsByRegime(WeeklyAccuracyAggregator.buildVolRegimeGroups(rows)).get("HIGH_VOL");
        assertThat(high.getTotalSignals()).isEqualTo(15);   // 행 수 15
        assertThat(high.getDistinctDays()).isEqualTo(1);     // 진짜 독립 표본 1일
        assertThat(high.isInsufficientSample()).isTrue();    // P2-18 승격이 이 판정 의존 — 행 수 위장 차단(§4c)
    }

    @Test
    @DisplayName("buildVolRegimeGroups distinctDays 경계: 10일에 걸친 HIGH_VOL → 고유일 10 → 충분")
    void buildVol_distinctDaysBoundarySufficient() {
        List<SignalOutcome> rows = new ArrayList<>();
        for (int d = 0; d < 10; d++) rows.add(volSigOn(WEEK_START.plusDays(d), "HIGH_VOL", false));

        RegimeGroup high = groupsByRegime(WeeklyAccuracyAggregator.buildVolRegimeGroups(rows)).get("HIGH_VOL");
        assertThat(high.getDistinctDays()).isEqualTo(10);
        assertThat(high.isInsufficientSample()).isFalse();
    }

    @Test
    @DisplayName("distinctSignalDays: 고유 signal_date 카운트(null 제외)")
    void distinctSignalDays_counts() {
        List<SignalOutcome> rows = new ArrayList<>();
        rows.add(volSigOn(WEEK_START, "NORMAL", true));
        rows.add(volSigOn(WEEK_START, "NORMAL", false));        // 같은 날 중복
        rows.add(volSigOn(WEEK_START.plusDays(1), "NORMAL", true));
        assertThat(WeeklyAccuracyAggregator.distinctSignalDays(rows)).isEqualTo(2);
        assertThat(WeeklyAccuracyAggregator.distinctSignalDays(List.of())).isZero();
    }

    @Test
    @DisplayName("buildRegimeGroups: 항상 4버킷(빈 버킷 포함), 각 4카테고리+4밴드 셀")
    void groups_alwaysFourBuckets() {
        List<RegimeGroup> groups = WeeklyAccuracyAggregator.buildRegimeGroups(
                List.of(supplySig("BULL", true, "1.0")));

        assertThat(groups).hasSize(4);
        Map<String, RegimeGroup> byRegime = groupsByRegime(groups);
        assertThat(byRegime.get("BULL").getCategories()).hasSize(4);
        assertThat(byRegime.get("BULL").getBands()).hasSize(4);
        // 빈 버킷도 노출(0건 정직)
        assertThat(byRegime.get("BEAR").getTotalSignals()).isZero();
        assertThat(byRegime.get("BEAR").isInsufficientSample()).isTrue();
    }

    // ---------------------------------------------------------------
    // 표본부족(§4c)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("isInsufficient: n<10 표본부족, n≥10 충분")
    void insufficient_threshold() {
        assertThat(WeeklyAccuracyAggregator.isInsufficient(9)).isTrue();
        assertThat(WeeklyAccuracyAggregator.isInsufficient(10)).isFalse();
        assertThat(WeeklyAccuracyAggregator.isInsufficient(0)).isTrue();
    }

    @Test
    @DisplayName("셀 표본부족 플래그: 수급 9건→부족(true), 10건→충분(false)이되 hitRate는 계산됨(위장 아님)")
    void cell_insufficientFlagButStillComputed() {
        // 9건 = 부족
        RegimeGroup g9 = groupsByRegime(WeeklyAccuracyAggregator.buildRegimeGroups(
                nSupply("BULL", 9, true, "2.0"))).get("BULL");
        CategoryCell supply9 = cell(g9, "supplyDemand");
        assertThat(supply9.getTotalSignals()).isEqualTo(9);
        assertThat(supply9.isInsufficientSample()).isTrue();
        assertThat(supply9.getHitRate()).isEqualByComparingTo("100");   // 계산은 됨(숨기지 않음)

        // 10건 = 충분
        RegimeGroup g10 = groupsByRegime(WeeklyAccuracyAggregator.buildRegimeGroups(
                nSupply("BULL", 10, true, "2.0"))).get("BULL");
        CategoryCell supply10 = cell(g10, "supplyDemand");
        assertThat(supply10.getTotalSignals()).isEqualTo(10);
        assertThat(supply10.isInsufficientSample()).isFalse();
        assertThat(supply10.getAvgAlpha()).isEqualByComparingTo("2.00");
    }

    // ---------------------------------------------------------------
    // 추세 (이번 주 vs 누적)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("categoryTrends: 양쪽 표본 충분 & 이번주 alpha<누적 → worsening=true, 델타 계산")
    void trends_worseningWhenBothSufficient() {
        // 이번 주: 수급 강세 10건, hit 3건(30%), alpha -1
        List<SignalOutcome> weekly = new ArrayList<>();
        weekly.addAll(nSupply("BULL", 3, true, "-1.0"));
        weekly.addAll(nSupply("BULL", 7, false, "-1.0"));
        // 누적: 수급 강세 20건, hit 12건(60%), alpha +2
        List<SignalOutcome> cumulative = new ArrayList<>();
        cumulative.addAll(nSupply("BULL", 12, true, "2.0"));
        cumulative.addAll(nSupply("BULL", 8, false, "2.0"));

        List<CategoryTrend> trends = WeeklyAccuracyAggregator.buildCategoryTrends(weekly, cumulative);
        CategoryTrend supply = trends.stream().filter(t -> t.getKey().equals("supplyDemand"))
                .findFirst().orElseThrow();

        assertThat(supply.isWeeklyInsufficient()).isFalse();
        assertThat(supply.isCumulativeInsufficient()).isFalse();
        assertThat(supply.getHitRateDelta()).isEqualByComparingTo("-30");   // 30% - 60%
        assertThat(supply.getAvgAlphaDelta()).isEqualByComparingTo("-3.00"); // -1 - 2
        assertThat(supply.isWorsening()).isTrue();
    }

    @Test
    @DisplayName("categoryTrends: 이번 주 표본부족이면 델타 null·worsening=false (위장 방지)")
    void trends_insufficientNoDeltaNoWorsening() {
        List<SignalOutcome> weekly = nSupply("BULL", 3, false, "-5.0");  // 3건 = 부족
        List<SignalOutcome> cumulative = new ArrayList<>();
        cumulative.addAll(nSupply("BULL", 12, true, "2.0"));
        cumulative.addAll(nSupply("BULL", 8, false, "2.0"));

        List<CategoryTrend> trends = WeeklyAccuracyAggregator.buildCategoryTrends(weekly, cumulative);
        CategoryTrend supply = trends.stream().filter(t -> t.getKey().equals("supplyDemand"))
                .findFirst().orElseThrow();

        assertThat(supply.isWeeklyInsufficient()).isTrue();
        assertThat(supply.getHitRateDelta()).isNull();
        assertThat(supply.getAvgAlphaDelta()).isNull();
        assertThat(supply.isWorsening()).isFalse();
    }

    // ---------------------------------------------------------------
    // 수급 역상관 스트릭 / 경고
    // ---------------------------------------------------------------

    @Test
    @DisplayName("isSupplyInverted: 누적 강세수급 n≥10 & alpha<0 → true, n<10이면 alpha<0라도 false")
    void supplyInverted_needsSufficientSample() {
        assertThat(WeeklyAccuracyAggregator.isSupplyInverted(nSupply("BULL", 10, false, "-2.0"))).isTrue();
        assertThat(WeeklyAccuracyAggregator.isSupplyInverted(nSupply("BULL", 9, false, "-2.0"))).isFalse();
        assertThat(WeeklyAccuracyAggregator.isSupplyInverted(nSupply("BULL", 10, true, "2.0"))).isFalse();
    }

    @Test
    @DisplayName("supplyStreak: current=false→0, true + prior[true,true,false]→3")
    void supplyStreak_countsConsecutive() {
        assertThat(WeeklyAccuracyAggregator.supplyStreak(false, List.of(true, true))).isZero();
        assertThat(WeeklyAccuracyAggregator.supplyStreak(true, List.of(true, true, false, true))).isEqualTo(3);
        assertThat(WeeklyAccuracyAggregator.supplyStreak(true, List.of())).isEqualTo(1);
        assertThat(WeeklyAccuracyAggregator.supplyStreak(true, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("detectWarnings: 수급 역상관 3주째 경고 문구 + 이번주 표본부족 안내")
    void warnings_supplyStreakAndLowSample() {
        List<CategoryTrend> trends = List.of();  // 악화 경고 없음
        List<String> warnings = WeeklyAccuracyAggregator.detectWarnings(
                trends, true, List.of(true, true), /*weeklyN*/ 3);

        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("수급 역상관 지속 3주째"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("표본부족"));
    }

    @Test
    @DisplayName("detectWarnings: 스트릭 1주(<최소2)면 수급 경고 없음")
    void warnings_noStreakWhenBelowMin() {
        List<String> warnings = WeeklyAccuracyAggregator.detectWarnings(
                List.of(), true, List.of(), /*weeklyN*/ 50);
        assertThat(warnings).noneSatisfy(w -> assertThat(w).contains("수급 역상관"));
    }

    // ---------------------------------------------------------------
    // 전체 조립
    // ---------------------------------------------------------------

    @Test
    @DisplayName("assembleReport: weeklyN/cumulativeN·4regime 그룹·추세·경고 조립")
    void assemble_full() {
        List<SignalOutcome> weekly = nSupply("BULL", 12, false, "-2.0");
        List<SignalOutcome> cumulative = new ArrayList<>(nSupply("BULL", 20, false, "-2.0"));

        WeeklySignalAccuracyDto dto = WeeklyAccuracyAggregator.assembleReport(
                WEEK_START, WEEK_END, weekly, cumulative, List.of(true, true));

        assertThat(dto.getWeekStart()).isEqualTo(WEEK_START);
        assertThat(dto.getWeeklyN()).isEqualTo(12);
        assertThat(dto.getCumulativeN()).isEqualTo(20);
        assertThat(dto.getRegimeGroups()).hasSize(4);
        assertThat(dto.getCategoryTrends()).isNotEmpty();
        // 누적 강세수급 20건·alpha 음수 → supplyInverted, prior 2주 연속 → 3주째 경고
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("수급 역상관 지속 3주째"));
    }
}
