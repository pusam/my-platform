package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat;
import com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.BandCell;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.CategoryCell;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.CategoryTrend;
import com.myplatform.backend.dto.WeeklySignalAccuracyDto.RegimeGroup;
import com.myplatform.backend.entity.SignalOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시그널 예측력 주간 측정 — <b>순수 함수</b> 집계 (P1-6 상설화, 테스트 대상).
 *
 * <p>입력은 이미 board(STRONG_BUY/BUY) 격리 + 평가완료된 {@link SignalOutcome} 리스트라고 가정한다
 * (격리는 {@link SignalOutcomeService#filterBoardSignals}, 컷오프 창은 호출부 책임).
 *
 * <p>측정 축 = 카테고리 × regime × 밴드 를 <b>2D 파티션</b>으로 실현: regime 버킷별로
 * {@link SignalOutcomeService#aggregateCategories}/{@link SignalOutcomeService#aggregateBands} 를 재호출.
 * 완전 3중 크로스탭(≤64셀)은 현재 표본(n≈88)에선 거의 전셀 표본부족이라 무의미 — 표본 축적 후
 * report_json 기반으로 스키마 변경 없이 3D 추가 가능(설계 결정).
 *
 * <p>regime_at_signal NULL/blank → {@code UNKNOWN} 버킷으로 정직 분리(pykrx 깨졌던 구간).
 * 셀 n &lt; {@link #INSUFFICIENT_SAMPLE_THRESHOLD} → insufficientSample 명시(§4c, 위장 금지).
 */
final class WeeklyAccuracyAggregator {

    private WeeklyAccuracyAggregator() {}

    /** 표본부족 임계 — n<10 셀은 hitRate/alpha 계산하되 신뢰 낮음으로 표기. */
    static final int INSUFFICIENT_SAMPLE_THRESHOLD = 10;

    /** 수급 역상관 경고를 띄우는 최소 연속 주차. */
    static final int SUPPLY_STREAK_WARN_MIN = 2;

    static final String REGIME_UNKNOWN = "UNKNOWN";

    /** regime 버킷 정의 — 표시 순서 고정. UNKNOWN = 국면 미수집(NULL). */
    private static final String[][] REGIME_BUCKETS = {
            {"BULL", "상승장"},
            {"BEAR", "하락장"},
            {"SIDEWAYS", "횡보장"},
            {REGIME_UNKNOWN, "국면 미수집"},
    };

    /** 변동성 국면(V46) 버킷 정의 — 표시 순서 고정. UNKNOWN = 미수집(NULL). */
    private static final String[][] VOL_REGIME_BUCKETS = {
            {"NORMAL", "정상 변동성"},
            {"HIGH_VOL", "고변동"},
            {REGIME_UNKNOWN, "변동성 미수집"},
    };

    static boolean isInsufficient(long n) {
        return n < INSUFFICIENT_SAMPLE_THRESHOLD;
    }

    /** regime_at_signal → 버킷 키. NULL/blank/미정의 값은 UNKNOWN. */
    static String regimeBucket(SignalOutcome s) {
        String r = s.getRegimeAtSignal();
        if (r == null || r.isBlank()) return REGIME_UNKNOWN;
        return switch (r) {
            case "BULL", "BEAR", "SIDEWAYS" -> r;
            default -> REGIME_UNKNOWN;
        };
    }

    /** 변동성 국면(V46) → 버킷 키. NULL/blank/미정의 값은 UNKNOWN(§4c). */
    static String volRegimeBucket(SignalOutcome s) {
        String r = s.getVolRegimeAtSignal();
        if (r == null || r.isBlank()) return REGIME_UNKNOWN;
        return switch (r) {
            case "NORMAL", "HIGH_VOL" -> r;
            default -> REGIME_UNKNOWN;
        };
    }

    /** rows 를 버킷별로 분할 — 버킷 정의 순서/키 보존(빈 버킷도 존재), classifier 로 배정. */
    private static Map<String, List<SignalOutcome>> partitionBy(
            List<SignalOutcome> rows, String[][] bucketDefs, java.util.function.Function<SignalOutcome, String> classifier) {
        Map<String, List<SignalOutcome>> map = new LinkedHashMap<>();
        for (String[] def : bucketDefs) map.put(def[0], new ArrayList<>());
        for (SignalOutcome s : rows) {
            map.get(classifier.apply(s)).add(s);   // classifier 는 항상 정의된 키 반환
        }
        return map;
    }

    /** rows 를 regime 버킷별로 분할 (정의된 4버킷 순서 보존, 빈 버킷도 키 존재). */
    static Map<String, List<SignalOutcome>> partitionByRegime(List<SignalOutcome> rows) {
        return partitionBy(rows, REGIME_BUCKETS, WeeklyAccuracyAggregator::regimeBucket);
    }

    /** rows 를 변동성 국면(V46) 버킷별로 분할. */
    static Map<String, List<SignalOutcome>> partitionByVolRegime(List<SignalOutcome> rows) {
        return partitionBy(rows, VOL_REGIME_BUCKETS, WeeklyAccuracyAggregator::volRegimeBucket);
    }

    /**
     * 버킷 파티션 → 카테고리/밴드 셀 그룹. 빈 버킷도 포함(표본부족·0건 정직 노출). RegimeGroup 형태 재사용.
     *
     * <p><b>⚠ 버킷 유의 판정 = distinctDays(고유 signal_date)</b>(P3-11): regime/vol_regime 은 지수 축이라
     * 같은 날 전 시그널이 동일값 = 서로 독립 아님. 버킷 {@code insufficientSample} 을 행 수(n)로 재면
     * 며칠 데이터만으로 표본충분 위장(§4c). <b>P2-18(VKOSPI 게이트 승격)이 이 판정에 의존</b>하므로
     * distinctDays 로 판정한다. (카테고리/밴드 셀은 종목 축이라 셀 내부는 행 수 기준 유지 — 티켓 범위.)
     */
    private static List<RegimeGroup> buildGroups(String[][] bucketDefs, Map<String, List<SignalOutcome>> byBucket) {
        List<RegimeGroup> groups = new ArrayList<>();
        for (String[] def : bucketDefs) {
            List<SignalOutcome> subset = byBucket.get(def[0]);
            long n = subset.size();
            long distinctDays = distinctSignalDays(subset);
            groups.add(RegimeGroup.builder()
                    .regime(def[0])
                    .label(def[1])
                    .totalSignals(n)
                    .distinctDays(distinctDays)
                    .insufficientSample(isInsufficient(distinctDays))   // P3-11: 행 수 아닌 고유일 기준
                    .categories(toCategoryCells(SignalOutcomeService.aggregateCategories(subset)))
                    .bands(toBandCells(SignalOutcomeService.aggregateBands(subset)))
                    .build());
        }
        return groups;
    }

    /** 고유 signal_date 수 — 지수 축(regime/vol_regime) 진짜 독립 표본 수(P3-11). null 날짜는 제외. */
    static long distinctSignalDays(List<SignalOutcome> rows) {
        java.util.Set<LocalDate> days = new java.util.HashSet<>();
        for (SignalOutcome s : rows) {
            if (s.getSignalDate() != null) days.add(s.getSignalDate());
        }
        return days.size();
    }

    /** 이번 주 rows → regime 파티션별 카테고리/밴드 셀. */
    static List<RegimeGroup> buildRegimeGroups(List<SignalOutcome> weeklyRows) {
        return buildGroups(REGIME_BUCKETS, partitionByRegime(weeklyRows));
    }

    /** 이번 주 rows → 변동성 국면(V46) 파티션별 카테고리/밴드 셀 (NORMAL vs HIGH_VOL 분리 집계). */
    static List<RegimeGroup> buildVolRegimeGroups(List<SignalOutcome> weeklyRows) {
        return buildGroups(VOL_REGIME_BUCKETS, partitionByVolRegime(weeklyRows));
    }

    private static List<CategoryCell> toCategoryCells(List<CategoryStat> stats) {
        List<CategoryCell> cells = new ArrayList<>();
        for (CategoryStat c : stats) {
            cells.add(CategoryCell.builder()
                    .key(c.getKey())
                    .label(c.getLabel())
                    .strongThreshold(c.getStrongThreshold())
                    .totalSignals(c.getTotalSignals())
                    .hitCount(c.getHitCount())
                    .hitRate(c.getHitRate())
                    .avgAlpha(c.getAvgAlpha())
                    .avgPctChange(c.getAvgPctChange())
                    .insufficientSample(isInsufficient(c.getTotalSignals()))
                    .build());
        }
        return cells;
    }

    private static List<BandCell> toBandCells(List<BandStat> stats) {
        List<BandCell> cells = new ArrayList<>();
        for (BandStat b : stats) {
            cells.add(BandCell.builder()
                    .band(b.getBand())
                    .scoreFrom(b.getScoreFrom())
                    .scoreTo(b.getScoreTo())
                    .totalSignals(b.getTotalSignals())
                    .hitCount(b.getHitCount())
                    .hitRate(b.getHitRate())
                    .avgAlpha(b.getAvgAlpha())
                    .avgPctChange(b.getAvgPctChange())
                    .insufficientSample(isInsufficient(b.getTotalSignals()))
                    .build());
        }
        return cells;
    }

    /**
     * 카테고리별 추세 — 이번 주 vs 누적 전체(악화 감지). 강세 표본(카테고리별 임계) 기준.
     * 델타/악화는 <b>양쪽 표본 충분할 때만</b> 계산(표본부족 구간은 null/false = 위장 방지).
     */
    static List<CategoryTrend> buildCategoryTrends(List<SignalOutcome> weeklyRows,
                                                   List<SignalOutcome> cumulativeRows) {
        Map<String, CategoryStat> weekly = byKey(SignalOutcomeService.aggregateCategories(weeklyRows));
        Map<String, CategoryStat> cumulative = byKey(SignalOutcomeService.aggregateCategories(cumulativeRows));

        List<CategoryTrend> trends = new ArrayList<>();
        // 누적 aggregateCategories 순서(실적/수급/기술/섹터)를 기준으로 순회.
        for (Map.Entry<String, CategoryStat> e : cumulative.entrySet()) {
            String key = e.getKey();
            CategoryStat cum = e.getValue();
            CategoryStat wk = weekly.get(key);

            long weeklyN = wk == null ? 0 : wk.getTotalSignals();
            long cumN = cum.getTotalSignals();
            boolean weeklyInsuf = isInsufficient(weeklyN);
            boolean cumInsuf = isInsufficient(cumN);
            boolean bothSufficient = !weeklyInsuf && !cumInsuf;

            BigDecimal weeklyHit = wk == null ? null : wk.getHitRate();
            BigDecimal cumHit = cum.getHitRate();
            BigDecimal weeklyAlpha = wk == null ? null : wk.getAvgAlpha();
            BigDecimal cumAlpha = cum.getAvgAlpha();

            BigDecimal hitDelta = bothSufficient ? subtract(weeklyHit, cumHit) : null;
            BigDecimal alphaDelta = bothSufficient ? subtract(weeklyAlpha, cumAlpha) : null;
            // 악화 = 적중률 또는 alpha 가 누적 대비 하락(양쪽 표본 충분).
            boolean worsening = bothSufficient
                    && ((hitDelta != null && hitDelta.signum() < 0)
                        || (alphaDelta != null && alphaDelta.signum() < 0));

            trends.add(CategoryTrend.builder()
                    .key(key)
                    .label(cum.getLabel())
                    .weeklyN(weeklyN)
                    .cumulativeN(cumN)
                    .weeklyHitRate(weeklyHit)
                    .cumulativeHitRate(cumHit)
                    .hitRateDelta(hitDelta)
                    .weeklyAvgAlpha(weeklyAlpha)
                    .cumulativeAvgAlpha(cumAlpha)
                    .avgAlphaDelta(alphaDelta)
                    .weeklyInsufficient(weeklyInsuf)
                    .cumulativeInsufficient(cumInsuf)
                    .worsening(worsening)
                    .build());
        }
        return trends;
    }

    /**
     * 누적 수급 역상관 플래그 — 강세 수급(≥15) 표본이 <b>충분(n≥10)하고 평균 alpha&lt;0</b> 이면 true.
     * 스트릭(연속 주차) 카운트의 주간 입력. 표본부족이면 false(위장 방지 — "역상관 확정" 아님).
     */
    static boolean isSupplyInverted(List<SignalOutcome> cumulativeRows) {
        CategoryStat supply = byKey(SignalOutcomeService.aggregateCategories(cumulativeRows))
                .get("supplyDemand");
        if (supply == null) return false;
        if (isInsufficient(supply.getTotalSignals())) return false;
        BigDecimal alpha = supply.getAvgAlpha();
        return alpha != null && alpha.signum() < 0;
    }

    /**
     * 수급 역상관 연속 주차. current=이번 주 플래그, prior=직전 스냅샷들의 플래그(최신 먼저).
     * current=false 면 0. true 면 1 + prior 선두의 연속 true 개수.
     */
    static int supplyStreak(boolean current, List<Boolean> prior) {
        if (!current) return 0;
        int streak = 1;
        if (prior != null) {
            for (Boolean p : prior) {
                if (Boolean.TRUE.equals(p)) streak++;
                else break;
            }
        }
        return streak;
    }

    /**
     * 경고 생성 — 순수 함수. ① 수급 역상관 지속 N주차(≥{@link #SUPPLY_STREAK_WARN_MIN}) ②
     * 카테고리별 악화(이번 주 vs 누적, 양쪽 표본 충분) ③ 이번 주 전체 표본부족 안내.
     */
    static List<String> detectWarnings(List<CategoryTrend> trends,
                                       boolean cumulativeSupplyInverted,
                                       List<Boolean> priorSupplyInverted,
                                       int weeklyN) {
        List<String> warnings = new ArrayList<>();

        int streak = supplyStreak(cumulativeSupplyInverted, priorSupplyInverted);
        if (streak >= SUPPLY_STREAK_WARN_MIN) {
            warnings.add(String.format("수급 역상관 지속 %d주째 (누적 강세-수급 평균 alpha 음수) — 가중치 재조정 후보", streak));
        }

        for (CategoryTrend t : trends) {
            if (t.isWorsening()) {
                StringBuilder sb = new StringBuilder();
                sb.append(t.getLabel()).append(" 예측력 악화(이번 주 vs 누적)");
                if (t.getHitRateDelta() != null && t.getHitRateDelta().signum() < 0) {
                    sb.append(String.format(" 적중률 %s%%p", t.getHitRateDelta().toPlainString()));
                }
                if (t.getAvgAlphaDelta() != null && t.getAvgAlphaDelta().signum() < 0) {
                    sb.append(String.format(" alpha %s%%p", t.getAvgAlphaDelta().toPlainString()));
                }
                warnings.add(sb.toString());
            }
        }

        if (isInsufficient(weeklyN)) {
            warnings.add(String.format("이번 주 표본부족(n=%d<%d) — 주간 수치는 참고만, 누적 추세 우선",
                    weeklyN, INSUFFICIENT_SAMPLE_THRESHOLD));
        }
        return warnings;
    }

    /**
     * 전체 리포트 조립 — 순수 함수. 격리·컷오프 완료된 weekly/cumulative rows 입력.
     * @param priorSupplyInverted 직전 스냅샷들의 supplyInverted 플래그(최신 먼저) — 스트릭 계산용.
     */
    static WeeklySignalAccuracyDto assembleReport(LocalDate weekStart, LocalDate weekEnd,
                                                  List<SignalOutcome> weeklyRows,
                                                  List<SignalOutcome> cumulativeRows,
                                                  List<Boolean> priorSupplyInverted) {
        List<RegimeGroup> groups = buildRegimeGroups(weeklyRows);
        List<RegimeGroup> volGroups = buildVolRegimeGroups(weeklyRows);
        List<CategoryTrend> trends = buildCategoryTrends(weeklyRows, cumulativeRows);
        boolean supplyInverted = isSupplyInverted(cumulativeRows);
        List<String> warnings = detectWarnings(trends, supplyInverted, priorSupplyInverted, weeklyRows.size());

        return WeeklySignalAccuracyDto.builder()
                .weekStart(weekStart)
                .weekEnd(weekEnd)
                .weeklyN(weeklyRows.size())
                .cumulativeN(cumulativeRows.size())
                .regimeGroups(groups)
                .volRegimeGroups(volGroups)
                .categoryTrends(trends)
                .warnings(warnings)
                .build();
    }

    private static Map<String, CategoryStat> byKey(List<CategoryStat> stats) {
        Map<String, CategoryStat> map = new LinkedHashMap<>();
        for (CategoryStat c : stats) map.put(c.getKey(), c);
        return map;
    }

    private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.subtract(b);
    }
}
