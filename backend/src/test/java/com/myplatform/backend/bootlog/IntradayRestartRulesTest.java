package com.myplatform.backend.bootlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장중 재시작 판정(2026-11-10) — 표본 시작일 규칙 '08:30~15:50 재시작 없는 거래일'(CLAUDE.md §7)을 고정한다.
 * 실제 사례: 11/9 09:12 배포(장중 재시작 → 표본 시작 후보가 11/10 으로 밀림), 11/10 16:25 배포(장 마감 뒤 — 해당 없음).
 */
class IntradayRestartRulesTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final LocalDate TUE = LocalDate.of(2026, 11, 10);
    /** 주말만 휴장으로 보는 달력(테스트용). */
    private static final Predicate<LocalDate> WEEKDAYS = d ->
            d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;

    private static IntradayRestartRules.Boot boot(LocalDateTime t) {
        return new IntradayRestartRules.Boot(t, "a1b2c3d4e5f6", "1e3fb164aaaa");
    }

    @Test
    @DisplayName("경계 시각 — 08:30:00·15:50:00 은 장중, 08:29:59·15:50:01 은 아니다")
    void windowBoundaries() {
        assertThat(IntradayRestartRules.inWindow(TUE.atTime(8, 29, 59), WEEKDAYS)).isFalse();
        assertThat(IntradayRestartRules.inWindow(TUE.atTime(8, 30, 0), WEEKDAYS)).isTrue();
        assertThat(IntradayRestartRules.inWindow(TUE.atTime(15, 50, 0), WEEKDAYS)).isTrue();
        assertThat(IntradayRestartRules.inWindow(TUE.atTime(15, 50, 1), WEEKDAYS)).isFalse();
        assertThat(IntradayRestartRules.inWindow(TUE.atTime(16, 25, 0), WEEKDAYS)).isFalse();
    }

    @Test
    @DisplayName("장이 닫힌 날(주말·공휴일)의 재시작은 장중 재시작이 아니다")
    void closedDayIsNotIntraday() {
        LocalDate sat = LocalDate.of(2026, 11, 14);
        assertThat(IntradayRestartRules.inWindow(sat.atTime(10, 0), WEEKDAYS)).isFalse();
        // 달력을 모르면 거래일로 본다 — 숨기는 쪽보다 낫다
        assertThat(IntradayRestartRules.inWindow(sat.atTime(10, 0), null)).isTrue();
    }

    @Test
    @DisplayName("오늘 장중 재시작이 없으면 카드는 조용하고 마지막 장중 재시작 날짜를 말한다(11/9 09:12 배포)")
    void quietWhenNoRestartToday() {
        LocalDateTime current = TUE.atTime(16, 25, 10);
        var s = IntradayRestartRules.summarize(
                List.of(boot(MON.atTime(9, 12, 3)), boot(current)), current, TUE, WEEKDAYS, MON);

        assertThat(s.todayIntraday()).isEmpty();
        assertThat(s.currentBootRecorded()).isTrue();
        assertThat(s.lastIntraday().startedAt()).isEqualTo(MON.atTime(9, 12, 3));
        assertThat(s.lastBoot().startedAt()).isEqualTo(current);
        assertThat(IntradayRestartRules.note(s)).isNull();
        assertThat(IntradayRestartRules.noteDetail(s)).isNull();
        assertThat(IntradayRestartRules.lastIntradayLine(s)).isEqualTo("마지막 장중 재시작 11/09 09:12");
    }

    @Test
    @DisplayName("오늘 장중 재시작이 있으면 시각을 말하고 표본 시작일 후보가 아니라고 한다")
    void todayRestartIsReported() {
        LocalDateTime current = TUE.atTime(10, 4, 0);
        var s = IntradayRestartRules.summarize(
                List.of(boot(TUE.atTime(7, 50)), boot(TUE.atTime(9, 5)), boot(current)), current, TUE, WEEKDAYS, MON);

        assertThat(s.todayIntraday()).extracting(IntradayRestartRules.Boot::startedAt)
                .containsExactly(TUE.atTime(9, 5), current);
        assertThat(IntradayRestartRules.note(s)).isEqualTo("오늘 장중 재시작 09:05 외 1회 — 표본 시작일 후보 아님");
        assertThat(IntradayRestartRules.noteDetail(s))
                .contains("09:05(커밋 1e3fb164 · 컨테이너 a1b2c3d4e5f6)")
                .contains("CLAUDE.md §7");
    }

    @Test
    @DisplayName("지금 프로세스 부팅이 기록에 없으면 메모리 시각으로 세고 '기록 누락'을 말한다(§4c — 없음으로 위장 금지)")
    void missingCurrentBootIsNotSilent() {
        LocalDateTime current = TUE.atTime(11, 0, 0);
        var s = IntradayRestartRules.summarize(List.of(boot(MON.atTime(20, 0))), current, TUE, WEEKDAYS, MON);

        assertThat(s.currentBootRecorded()).isFalse();
        assertThat(s.todayIntraday()).extracting(IntradayRestartRules.Boot::startedAt).containsExactly(current);
        assertThat(IntradayRestartRules.noteDetail(s)).contains("기록 누락").contains("지금 프로세스의 부팅이 기록에 없다");

        // 장 밖 부팅의 기록 누락도 조용하지 않다
        LocalDateTime evening = TUE.atTime(18, 0);
        var s2 = IntradayRestartRules.summarize(List.of(), evening, TUE, WEEKDAYS, null);
        assertThat(IntradayRestartRules.note(s2)).isEqualTo("이번 부팅 기록 누락 — 재시작 기록이 빠졌을 수 있다");
    }

    @Test
    @DisplayName("기록과 메모리 시각은 초 단위로 맞춘다(DATETIME 은 밀리초를 버린다)")
    void sameSecondMatches() {
        LocalDateTime recorded = TUE.atTime(16, 25, 10);
        var s = IntradayRestartRules.summarize(List.of(boot(recorded)), recorded.plusNanos(734_000_000), TUE, WEEKDAYS, TUE);
        assertThat(s.currentBootRecorded()).isTrue();
    }

    @Test
    @DisplayName("장중 재시작이 한 번도 없으면 기록 시작일을 말한다 — '정말 없음'과 '기록 전'을 가른다")
    void noIntradayEver() {
        LocalDateTime current = TUE.atTime(16, 25);
        var s = IntradayRestartRules.summarize(List.of(boot(current)), current, TUE, WEEKDAYS, TUE);
        assertThat(IntradayRestartRules.lastIntradayLine(s)).isEqualTo("기록 시작(11/10) 이후 장중 재시작 없음");

        var empty = IntradayRestartRules.summarize(List.of(), null, TUE, WEEKDAYS, null);
        assertThat(IntradayRestartRules.lastIntradayLine(empty)).isEqualTo("장중 재시작 기록 없음(기록 전)");
    }

    @Test
    @DisplayName("휴장일엔 tradingDay=false 이고 그날 재시작은 세지 않는다")
    void holidayToday() {
        LocalDate sat = LocalDate.of(2026, 11, 14);
        LocalDateTime current = sat.atTime(10, 0);
        var s = IntradayRestartRules.summarize(List.of(boot(current)), current, sat, WEEKDAYS, MON);
        assertThat(s.tradingDay()).isFalse();
        assertThat(s.todayIntraday()).isEmpty();
        assertThat(IntradayRestartRules.note(s)).isNull();
    }
}
