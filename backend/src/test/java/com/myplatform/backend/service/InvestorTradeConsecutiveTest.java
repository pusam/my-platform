package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수급 연속 매수일 계산 ({@link InvestorTradeService#resolveConsecutiveBuyDates}).
 *
 * <p>버그 재현: 기존 로직은 "DB 에 행이 존재하는 날짜"만 이어 세어, 수집이 실패한 거래일을
 * 인접일처럼 붙여 consecutiveDays 를 부풀렸다(§4c 위반 — 결측을 연속으로 위장).
 * 수정 후: 거래일 달력 기준으로 역행하며, 거래일인데 그날 데이터가 통째로 없으면
 * (그날 매수 여부를 알 수 없으므로) 연속을 끊는다. 주말·공휴일은 건너뛴다.
 */
class InvestorTradeConsecutiveTest {

    /** 2026-07-25(토)·26(일) 만 휴장인 달력 — 테스트 결정성 위해 요일 기반. */
    private static final Predicate<LocalDate> WEEKEND_ONLY = d -> {
        DayOfWeek dow = d.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    };

    private static final LocalDate TUE = LocalDate.of(2026, 7, 28);
    private static final LocalDate MON = LocalDate.of(2026, 7, 27);
    private static final LocalDate FRI = LocalDate.of(2026, 7, 24);
    private static final LocalDate THU = LocalDate.of(2026, 7, 23);

    @Test
    @DisplayName("수집 결측 거래일(월요일 행 전체 부재)은 연속을 끊는다 — 붙여 세기 금지")
    void collectionGapBreaksStreak() {
        // 화·금만 데이터 존재(월요일 수집 실패), 종목은 두 날 모두 매수 상위
        Map<LocalDate, Set<String>> dailyStocks = Map.of(
                TUE, Set.of("005930"),
                FRI, Set.of("005930"));

        List<LocalDate> streak = InvestorTradeService.resolveConsecutiveBuyDates(
                "005930", TUE, FRI, dailyStocks, WEEKEND_ONLY);

        // 기존 버그: 2일 연속으로 판정. 수정 후: 월요일이 미지(未知)이므로 화요일 1일만.
        assertThat(streak).containsExactly(TUE);
    }

    @Test
    @DisplayName("주말은 건너뛰고 연속으로 인정한다 (금→월 은 인접 거래일)")
    void weekendIsSkipped() {
        Map<LocalDate, Set<String>> dailyStocks = Map.of(
                TUE, Set.of("005930"),
                MON, Set.of("005930"),
                FRI, Set.of("005930"));

        List<LocalDate> streak = InvestorTradeService.resolveConsecutiveBuyDates(
                "005930", TUE, FRI, dailyStocks, WEEKEND_ONLY);

        assertThat(streak).containsExactly(TUE, MON, FRI);
    }

    @Test
    @DisplayName("데이터는 있는데 종목이 상위에 없으면 연속 끊김 (기존 semantics 유지)")
    void absentFromRankBreaksStreak() {
        Map<LocalDate, Set<String>> dailyStocks = Map.of(
                TUE, Set.of("005930"),
                MON, Set.of("000660"));

        List<LocalDate> streak = InvestorTradeService.resolveConsecutiveBuyDates(
                "005930", TUE, MON, dailyStocks, WEEKEND_ONLY);

        assertThat(streak).containsExactly(TUE);
    }

    @Test
    @DisplayName("분석 하한(earliestDate) 밖은 세지 않는다")
    void boundedByEarliestDate() {
        Map<LocalDate, Set<String>> dailyStocks = Map.of(
                TUE, Set.of("005930"),
                MON, Set.of("005930"),
                FRI, Set.of("005930"),
                THU, Set.of("005930"));

        List<LocalDate> streak = InvestorTradeService.resolveConsecutiveBuyDates(
                "005930", TUE, FRI, dailyStocks, WEEKEND_ONLY);

        assertThat(streak).containsExactly(TUE, MON, FRI);
    }

    @Test
    @DisplayName("최신 거래일에 데이터가 없으면 빈 streak")
    void emptyWhenLatestMissing() {
        Map<LocalDate, Set<String>> dailyStocks = Map.of(FRI, Set.of("005930"));

        List<LocalDate> streak = InvestorTradeService.resolveConsecutiveBuyDates(
                "005930", TUE, FRI, dailyStocks, WEEKEND_ONLY);

        assertThat(streak).isEmpty();
    }
}
