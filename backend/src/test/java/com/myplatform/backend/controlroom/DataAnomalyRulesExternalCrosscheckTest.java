package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이상 점검 ⑯ 외부 종가 대조 — 정상이면 조용하고, 같은 봉의 실행들을 합쳐 판정하며(뒤 실행이 막혀도 앞 실행의 불일치는 남는다),
 * 한꺼번에 틀리면 하루 만에 CRITICAL, 종목 하나는 대상 봉이 다른 직전 실행에서도 어긋났을 때 WARNING, '대조 못 함'은 일치가 아니라
 * 따로 올리고, 돌았어야 할 날 실행이 없으면 그날 바로 경고한다.
 */
class DataAnomalyRulesExternalCrosscheckTest {

    private static final LocalDate RUN = LocalDate.of(2026, 11, 11);
    private static final LocalDate BAR = LocalDate.of(2026, 11, 10);
    private static final LocalDate PREV_BAR = LocalDate.of(2026, 11, 9);
    private static final DataAnomalyRules.CrosscheckDue ON_TIME = new DataAnomalyRules.CrosscheckDue(RUN, 0, false);

    /** 종목별 결과 — match 개 일치(종목 A…), mismatch 목록, 그 밖 사유별 개수. */
    static DataAnomalyRules.CrosscheckRunStat run(LocalDate runDate, LocalDate bar, String status, int match,
                                                  List<String> mismatch, Map<String, Integer> notCompared) {
        Map<String, String> byCode = new LinkedHashMap<>();
        Map<String, BigDecimal> diffs = new LinkedHashMap<>();
        for (int i = 0; i < match; i++) byCode.put(String.format("M%05d", i), "MATCH");
        for (String c : mismatch) {
            byCode.put(c, "MISMATCH");
            diffs.put(c, new BigDecimal("-1.1261"));
        }
        int n = 0;
        for (var e : notCompared.entrySet()) for (int i = 0; i < e.getValue(); i++) byCode.put(String.format("N%05d", n++), e.getKey());
        return new DataAnomalyRules.CrosscheckRunStat(runDate, bar, status, 40, byCode, diffs, false, true);
    }

    private static DataAnomalyRules.CrosscheckRunStat clean() {
        return run(RUN, BAR, "COMPLETE", 39, List.of(), Map.of("NO_EXTERNAL_VALUE", 1));
    }

    private static List<String> keys(List<DataAnomalyRules.Anomaly> a) {
        return a.stream().map(DataAnomalyRules.Anomaly::key).toList();
    }

    private static List<DataAnomalyRules.Anomaly> check(DataAnomalyRules.CrosscheckRunStat... runs) {
        return DataAnomalyRules.externalCloseCrosscheck(List.of(runs), ON_TIME, true);
    }

    private static List<String> codes(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> String.format("X%05d", i)).toList();
    }

    @Test
    @DisplayName("정상이면 조용하다")
    void quietWhenClean() {
        assertThat(check(clean())).isEmpty();
    }

    @Test
    @DisplayName("대조한 종목의 30% 이상이 어긋나면 하루 만에 CRITICAL — 차이를 같이 보인다")
    void wideMismatchIsCritical() {
        var a = check(run(RUN, BAR, "COMPLETE", 26, codes(14), Map.of()));
        assertThat(keys(a)).containsExactly("external-close-mismatch-wide");
        assertThat(a.get(0).severity()).isEqualTo(DataAnomalyRules.CRITICAL);
        assertThat(a.get(0).detail()).contains("07:40").contains("고치지 않는다").contains("diffPct");
        assertThat(a.get(0).evidence()).contains("X00000 -1.1261%");
    }

    @Test
    @DisplayName("2종목 불일치는 WARNING")
    void twoMismatchesWarn() {
        var a = check(run(RUN, BAR, "COMPLETE", 38, List.of("005930", "000660"), Map.of()));
        assertThat(keys(a)).containsExactly("external-close-mismatch");
        assertThat(a.get(0).severity()).isEqualTo(DataAnomalyRules.WARNING);
    }

    @Test
    @DisplayName("1종목은 INFO(차이 크기로 짐작만), 대상 봉이 다른 직전 실행에서도 어긋났으면 WARNING")
    void singleThenRepeated() {
        var today = run(RUN, BAR, "COMPLETE", 39, List.of("005930"), Map.of());
        var single = check(today);
        assertThat(keys(single)).containsExactly("external-close-mismatch-single");
        assertThat(single.get(0).detail()).contains("저녁 값").contains("다음 실행 표본에 다시");

        var yesterday = run(BAR, PREV_BAR, "COMPLETE", 39, List.of("005930"), Map.of());
        var a = check(today, yesterday);
        assertThat(keys(a)).containsExactly("external-close-mismatch");
        assertThat(a.get(0).title()).contains("대상 봉이 다른 직전 실행에서도 005930");
    }

    @Test
    @DisplayName("같은 봉을 두 번 돌린 것(수동 재실행)은 '직전 실행' 비교가 아니다 — 같은 봉으로 합친다")
    void sameBarRerunIsMergedNotRepeated() {
        var a1 = run(RUN, BAR, "COMPLETE", 39, List.of("005930"), Map.of());
        var a2 = run(RUN, BAR, "COMPLETE", 39, List.of("005930"), Map.of());
        assertThat(keys(check(a1, a2))).containsExactly("external-close-mismatch-single");
    }

    @Nested
    @DisplayName("같은 봉의 실행들을 합친다 — 뒤 실행이 막혀도 앞 실행의 불일치는 남는다(B3)")
    class Merge {

        @Test
        @DisplayName("10:21 CRITICAL 뒤 10:40 재실행이 429 로 멈춰도 CRITICAL 은 그대로다")
        void abortedRerunKeepsCritical() {
            var complete = run(RUN, BAR, "COMPLETE", 26, codes(14), Map.of());
            var aborted = run(RUN, BAR, "ABORTED", 0, List.of(), Map.of("FETCH_FAILED", 5));
            assertThat(keys(check(aborted, complete))).contains("external-close-mismatch-wide");
        }

        @Test
        @DisplayName("뒤 실행이 같은 종목을 다시 대조해 일치하면 그 불일치는 사라진다")
        void laterMatchClearsMismatch() {
            var first = run(RUN, BAR, "COMPLETE", 38, List.of("005930", "000660"), Map.of());
            Map<String, String> second = new LinkedHashMap<>(first.closeStatusByCode());
            second.put("005930", "MATCH");
            second.put("000660", "MATCH");
            var rerun = new DataAnomalyRules.CrosscheckRunStat(RUN, BAR, "COMPLETE", 40, second, Map.of(), false, true);
            assertThat(check(rerun, first)).isEmpty();
        }

        @Test
        @DisplayName("끝나지 못한 실행(INTERRUPTED)도 그때까지 찾은 불일치로 판정한다(A1)")
        void interruptedRunStillCounts() {
            var interrupted = run(RUN, BAR, "INTERRUPTED", 2, codes(12), Map.of());
            assertThat(keys(check(interrupted))).contains("external-close-mismatch-wide");
        }

        @Test
        @DisplayName("종목별 결과를 읽지 않은 옛 실행(detailed=false)은 판정에 쓰지 않는다")
        void undetailedRunsIgnored() {
            var old = new DataAnomalyRules.CrosscheckRunStat(RUN, BAR, "COMPLETE", 40, Map.of(), Map.of(), false, false);
            assertThat(keys(check(clean(), old))).isEmpty();
        }
    }

    @Test
    @DisplayName("대조 못 함이 절반 이상이거나 표본이 비면 '일치'가 아니라 따로 올린다 — 우회 헤더 금지를 말한다")
    void blind() {
        var aborted = run(RUN, BAR, "ABORTED", 0, List.of(), Map.of("FETCH_FAILED", 5));
        var a = check(aborted);
        assertThat(keys(a)).containsExactly("external-crosscheck-blind");
        assertThat(a.get(0).detail()).contains("우회하지 말 것");
        assertThat(a.get(0).evidence()).contains("FETCH_FAILED 5");

        var empty = new DataAnomalyRules.CrosscheckRunStat(RUN, BAR, "COMPLETE", 0, Map.of(), Map.of(), false, true);
        var b = check(empty);
        assertThat(keys(b)).containsExactly("external-crosscheck-blind");
        assertThat(b.get(0).detail()).contains("대조한 종목이 없다");
    }

    @Test
    @DisplayName("'하루 늦음'이 넘어간 종목과 섞였으면 진짜 불일치일 수 있다고 말한다")
    void staleMixed() {
        Map<String, String> byCode = new LinkedHashMap<>(run(RUN, BAR, "COMPLETE", 37, List.of(), Map.of("EXTERNAL_STALE", 3)).closeStatusByCode());
        var r = new DataAnomalyRules.CrosscheckRunStat(RUN, BAR, "COMPLETE", 40, byCode, Map.of(), true, true);
        var a = check(r);
        assertThat(keys(a)).containsExactly("external-close-stale-mixed");
        assertThat(a.get(0).detail()).contains("진짜 불일치일 수 있다");
    }

    @Nested
    @DisplayName("돌았어야 할 날 실행이 없으면 그날 바로 경고한다(A2)")
    class Missed {

        @Test
        void missedToday() {
            var yesterday = run(BAR, PREV_BAR, "COMPLETE", 40, List.of(), Map.of());
            var a = DataAnomalyRules.externalCloseCrosscheck(List.of(yesterday),
                    new DataAnomalyRules.CrosscheckDue(RUN, 1, false), true);
            assertThat(keys(a)).containsExactly("external-crosscheck-stale");
            assertThat(a.get(0).title()).contains(RUN + " 에 돌지 않았다");
        }

        @Test
        void neverRanAfterDue() {
            var a = DataAnomalyRules.externalCloseCrosscheck(List.of(), new DataAnomalyRules.CrosscheckDue(RUN, 0, true), true);
            assertThat(keys(a)).containsExactly("external-crosscheck-none");
            assertThat(a.get(0).severity()).isEqualTo(DataAnomalyRules.WARNING);
        }

        @Test
        void neverRanBeforeFirstDue() {
            var a = DataAnomalyRules.externalCloseCrosscheck(List.of(), new DataAnomalyRules.CrosscheckDue(RUN, 0, false), true);
            assertThat(a).extracting(DataAnomalyRules.Anomaly::severity).containsExactly(DataAnomalyRules.INFO);
        }
    }

    @Test
    @DisplayName("꺼져 있으면 INFO — 조용히 안 도는 조합을 만들지 않는다")
    void off() {
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(clean()), ON_TIME, false)))
                .containsExactly("external-crosscheck-off");
    }
}
