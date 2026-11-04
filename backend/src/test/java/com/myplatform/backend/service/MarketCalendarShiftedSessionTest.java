package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장이 늦게 열리고 늦게 닫히는 날(수능일) — 고정 시각으로 판정하면 그날 수급·봉·등락 수가 장중 값으로 굳는다(2026-11-04).
 *
 * <p>재현: 코드에 지연 장 개념이 없어 2026-11-19(정규장 10:00~16:30 예정)의 15:50 수집이 장중 잠정치를 확정으로 저장하고,
 * 18:00 보완 수집도 '확정 기록 있음'으로 건너뛰어 그날 값이 잠정치로 남을 뻔했다. 16:10 이 지나면 형성 봉이 확정 봉으로
 * 저장될 수도 있었다(마지막 마감 거래일 판정이 15:40 고정).
 */
class MarketCalendarShiftedSessionTest {

    private static final LocalDate CSAT = LocalDate.of(2026, 11, 19);
    private static final LocalDate DAY_BEFORE = LocalDate.of(2026, 11, 18);

    private final MarketCalendarService calendar = new MarketCalendarService();

    @Test
    @DisplayName("수능일 정규장은 10:00~16:40(종가 버퍼 포함), 다른 날은 평소대로")
    void hoursByDate() {
        assertThat(calendar.marketOpenOf(CSAT)).isEqualTo(LocalTime.of(10, 0));
        assertThat(calendar.marketCloseOf(CSAT)).isEqualTo(LocalTime.of(16, 40));
        assertThat(calendar.closesLate(CSAT)).isTrue();

        assertThat(calendar.marketOpenOf(DAY_BEFORE)).isEqualTo(MarketCalendarService.MARKET_OPEN);
        assertThat(calendar.marketCloseOf(DAY_BEFORE)).isEqualTo(MarketCalendarService.MARKET_CLOSE);
        assertThat(calendar.closesLate(DAY_BEFORE)).isFalse();
    }

    @Test
    @DisplayName("정규장 판정이 그날 시각을 따른다")
    void regularSessionFollowsTheDay() {
        assertThat(calendar.isRegularSession(CSAT, LocalTime.of(9, 30))).isFalse();
        assertThat(calendar.isRegularSession(CSAT, LocalTime.of(10, 0))).isTrue();
        assertThat(calendar.isRegularSession(CSAT, LocalTime.of(16, 30))).isTrue();
        assertThat(calendar.isRegularSession(CSAT, LocalTime.of(16, 41))).isFalse();
        assertThat(calendar.isRegularSession(DAY_BEFORE, LocalTime.of(9, 30))).isTrue();
    }

    @Test
    @DisplayName("재현: 수능일 16:00 은 아직 마감 전이다 — 형성 봉·D+3 경계가 밀린다")
    void lastClosedDayWaitsForTheLateClose() {
        assertThat(calendar.lastClosedTradingDay(CSAT.atTime(16, 0))).isEqualTo(DAY_BEFORE);
        assertThat(calendar.lastClosedTradingDay(CSAT.atTime(16, 40))).isEqualTo(CSAT);
        assertThat(calendar.lastClosedTradingDay(DAY_BEFORE.atTime(15, 40))).isEqualTo(DAY_BEFORE);
    }

    @Test
    @DisplayName("재현: 수능일 15:50 저장 행은 확정이 아니다 — 마감 10분 뒤부터 확정")
    void investorRowsConfirmAfterTheLateClose() {
        LocalTime close = calendar.marketCloseOf(CSAT);
        List<Object[]> atFifteenFifty = List.of(
                new Object[]{"FOREIGN", CSAT.atTime(15, 50)}, new Object[]{"INSTITUTION", CSAT.atTime(15, 50)});
        List<Object[]> afterClose = List.of(
                new Object[]{"FOREIGN", CSAT.atTime(18, 0)}, new Object[]{"INSTITUTION", CSAT.atTime(18, 0)});

        assertThat(InvestorDailyConfirmation.isConfirmed(CSAT, atFifteenFifty, close)).isFalse();
        assertThat(InvestorDailyConfirmation.isConfirmed(CSAT, afterClose, close)).isTrue();
        assertThat(InvestorDailyConfirmation.isConfirmedWindow(CSAT.atTime(15, 50), close)).isFalse();
        assertThat(InvestorDailyConfirmation.isConfirmedWindow(CSAT.atTime(16, 50), close)).isTrue();

        // 평일은 종전과 같다
        LocalTime normal = calendar.marketCloseOf(DAY_BEFORE);
        assertThat(InvestorDailyConfirmation.confirmedFrom(normal)).isEqualTo(InvestorDailyConfirmation.CONFIRMED_FROM);
    }

    @Test
    @DisplayName("재현: 수능일 16:30 등락 수는 마감 전 값이다 — 16:45 회차가 저장한다")
    void breadthCountsSettleAfterTheLateClose() {
        LocalTime close = calendar.marketCloseOf(CSAT);

        assertThat(MarketBreadth.countsSettled(LocalTime.of(16, 30, 20), close)).isFalse();
        assertThat(MarketBreadth.countsSettled(LocalTime.of(16, 45, 45), close)).isTrue();
        assertThat(MarketBreadth.countsSettled(LocalTime.of(15, 40), null)).isTrue();
    }

    @Test
    @DisplayName("재현: 수능일 09:30 지수는 아직 직전 거래일 세션이다")
    void indexSessionStartsAtTheLateOpen() {
        assertThat(MarketTimingService.indexSessionDate(CSAT.atTime(9, 30), calendar)).isEqualTo(DAY_BEFORE);
        assertThat(MarketTimingService.indexSessionDate(CSAT.atTime(10, 0), calendar)).isEqualTo(CSAT);
    }

    @Test
    @DisplayName("수동 수집 거절 문구가 그날 시각을 말한다")
    void manualRefusalUsesTheDayClose() {
        String refusal = InvestorTradeService.manualCollectRefusal(CSAT.atTime(16, 0), false, calendar.marketCloseOf(CSAT));

        assertThat(refusal).contains("16:50");
    }
}
