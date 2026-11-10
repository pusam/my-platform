package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이상 점검 ⑯ 외부 종가 대조 — 정상이면 조용하고, 한꺼번에 틀리면 하루 만에 CRITICAL, 종목 하나는 이틀 연속일 때 WARNING,
 * '대조 못 함'은 일치가 아니라 따로 올린다.
 */
class DataAnomalyRulesExternalCrosscheckTest {

    private static final LocalDate RUN = LocalDate.of(2026, 11, 11);
    private static final LocalDate BAR = LocalDate.of(2026, 11, 10);

    private static DataAnomalyRules.CrosscheckRunStat run(LocalDate runDate, LocalDate bar, String status, int attempted,
                                                         int matched, int mismatched, Map<String, Integer> reasons,
                                                         List<String> codes, boolean mixed) {
        int notCompared = attempted - matched - mismatched;
        return new DataAnomalyRules.CrosscheckRunStat(runDate, bar, status, 40, attempted, matched, mismatched, notCompared,
                reasons, codes, mixed);
    }

    private static DataAnomalyRules.CrosscheckRunStat clean() {
        return run(RUN, BAR, "COMPLETE", 40, 39, 0, Map.of("NO_EXTERNAL_VALUE", 1), List.of(), false);
    }

    private static List<String> keys(List<DataAnomalyRules.Anomaly> a) {
        return a.stream().map(DataAnomalyRules.Anomaly::key).toList();
    }

    @Test
    @DisplayName("정상이면 조용하다")
    void quietWhenClean() {
        assertThat(DataAnomalyRules.externalCloseCrosscheck(List.of(clean()), 0, true)).isEmpty();
    }

    @Test
    @DisplayName("대조한 종목의 30% 이상이 어긋나면 하루 만에 CRITICAL — 아침 재수집이 저녁 값을 못 바꾼 경우(11/3 같은 날)")
    void wideMismatchIsCritical() {
        var r = run(RUN, BAR, "COMPLETE", 40, 26, 14, Map.of(), List.of("005930", "000660"), false);
        List<DataAnomalyRules.Anomaly> a = DataAnomalyRules.externalCloseCrosscheck(List.of(r), 0, true);
        assertThat(keys(a)).containsExactly("external-close-mismatch-wide");
        assertThat(a.get(0).severity()).isEqualTo(DataAnomalyRules.CRITICAL);
        assertThat(a.get(0).detail()).contains("07:40").contains("고치지 않는다");
        assertThat(a.get(0).evidence()).contains("005930");
    }

    @Test
    @DisplayName("2종목 불일치는 WARNING")
    void twoMismatchesWarn() {
        var r = run(RUN, BAR, "COMPLETE", 40, 38, 2, Map.of(), List.of("005930", "000660"), false);
        var a = DataAnomalyRules.externalCloseCrosscheck(List.of(r), 0, true);
        assertThat(keys(a)).containsExactly("external-close-mismatch");
        assertThat(a.get(0).severity()).isEqualTo(DataAnomalyRules.WARNING);
    }

    @Test
    @DisplayName("1종목 하루는 INFO, 같은 종목이 다른 날 봉으로도 어긋나면 WARNING")
    void singleThenRepeated() {
        var today = run(RUN, BAR, "COMPLETE", 40, 39, 1, Map.of(), List.of("005930"), false);
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(today), 0, true)))
                .containsExactly("external-close-mismatch-single");

        var yesterday = run(BAR, LocalDate.of(2026, 11, 9), "COMPLETE", 40, 39, 1, Map.of(), List.of("005930"), false);
        var a = DataAnomalyRules.externalCloseCrosscheck(List.of(today, yesterday), 0, true);
        assertThat(keys(a)).containsExactly("external-close-mismatch");
        assertThat(a.get(0).title()).contains("이틀 연속 005930");
    }

    @Test
    @DisplayName("같은 봉을 두 번 돌린 것(수동 재실행)은 '이틀 연속'이 아니다")
    void sameBarRerunIsNotRepeat() {
        var a1 = run(RUN, BAR, "COMPLETE", 40, 39, 1, Map.of(), List.of("005930"), false);
        var a2 = run(RUN, BAR, "COMPLETE", 40, 39, 1, Map.of(), List.of("005930"), false);
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(a1, a2), 0, true)))
                .containsExactly("external-close-mismatch-single");
    }

    @Test
    @DisplayName("대조 못 함이 절반 이상이거나 멈춘 실행은 '일치'가 아니라 따로 올린다 — 우회 헤더 금지를 말한다")
    void blind() {
        var aborted = run(RUN, BAR, "ABORTED", 5, 0, 0, Map.of("FETCH_FAILED", 5), List.of(), false);
        var a = DataAnomalyRules.externalCloseCrosscheck(List.of(aborted), 0, true);
        assertThat(keys(a)).containsExactly("external-crosscheck-blind");
        assertThat(a.get(0).detail()).contains("우회하지 말 것");
        assertThat(a.get(0).evidence()).contains("FETCH_FAILED 5");

        var half = run(RUN, BAR, "COMPLETE", 40, 20, 0, Map.of("NO_OUR_BAR", 20), List.of(), false);
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(half), 0, true))).containsExactly("external-crosscheck-blind");
    }

    @Test
    @DisplayName("'하루 늦음'이 넘어간 종목과 섞였으면 진짜 불일치일 수 있다고 말한다")
    void staleMixed() {
        var r = run(RUN, BAR, "COMPLETE", 40, 37, 0, Map.of("EXTERNAL_STALE", 3), List.of(), true);
        var a = DataAnomalyRules.externalCloseCrosscheck(List.of(r), 0, true);
        assertThat(keys(a)).containsExactly("external-close-stale-mixed");
        assertThat(a.get(0).detail()).contains("진짜 불일치일 수 있다");

        var withMismatch = run(RUN, BAR, "COMPLETE", 40, 35, 2, Map.of("EXTERNAL_STALE", 3), List.of("1", "2"), true);
        var b = DataAnomalyRules.externalCloseCrosscheck(List.of(withMismatch), 0, true);
        assertThat(keys(b)).containsExactly("external-close-mismatch");
        assertThat(b.get(0).detail()).contains("진짜 불일치일 수 있다");
    }

    @Test
    @DisplayName("거래일 2일 이상 안 돌았으면 멈춘 것 — 1일은 조용하다")
    void stale() {
        assertThat(DataAnomalyRules.externalCloseCrosscheck(List.of(clean()), 1, true)).isEmpty();
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(clean()), 2, true)))
                .containsExactly("external-crosscheck-stale");
    }

    @Test
    @DisplayName("기록이 없거나 꺼져 있으면 INFO — 조용히 안 도는 조합을 만들지 않는다")
    void noneOrOff() {
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(), 0, true))).containsExactly("external-crosscheck-none");
        assertThat(keys(DataAnomalyRules.externalCloseCrosscheck(List.of(clean()), 0, false))).containsExactly("external-crosscheck-off");
    }
}
