package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F2 — D+3 교정 평가는 <b>마감된 거래일</b>까지만 확정한다(2026-09-17 감사).
 *
 * <p><b>고치려는 결함</b>: {@code run()} 이 날짜만 비교해 {@code D+3 == 오늘}이면 시각과 무관하게
 * 평가했다. 두 방향으로 틀린다.
 * <ul>
 *   <li><b>장중</b>: KIS 응답에 오늘의 <b>미확정 장중 봉</b>이 들어 있으면 그 잠정 종가로 {@code OK} 가
 *       저장되고, OK 는 재평가 대상이 아니라 잠정값이 영구 고정된다.</li>
 *   <li><b>장전</b>: 오늘 봉이 아직 없으니 {@code MISSING_BARS} 가 기록되고, 재시도 간격 7일에 걸려
 *       정상 장마감 평가가 일주일 밀린다.</li>
 * </ul>
 *
 * <p><b>경계</b>는 KRX 정규장 종료({@link MarketCalendarService#MARKET_CLOSE} 15:40)에 일봉 확정
 * 여유를 더한 값이다 — NXT 표시 시간(20:00)과 합치지 않는다.
 */
class SignalD3TradingDayBoundaryTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-09-14(월) 기록 → D+1 9/15(화), D+2 9/16(수), D+3 9/17(목). */
    private static final LocalDate SIGNAL = LocalDate.of(2026, 9, 14);
    private static final LocalDate D1 = LocalDate.of(2026, 9, 15);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 16);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 17);

    private static Clock at(LocalDate date, LocalTime time) {
        return Clock.fixed(ZonedDateTime.of(date, time, KST).toInstant(), KST);
    }

    private static KoreaInvestmentService.OhlcvData bar(LocalDate d, String close) {
        var o = new KoreaInvestmentService.OhlcvData(new BigDecimal(close), new BigDecimal(close).add(BigDecimal.TEN),
                new BigDecimal(close).subtract(BigDecimal.TEN), new BigDecimal(close), new BigDecimal("1000"));
        o.setTradeDate(d);
        return o;
    }

    /** 창 3봉 + D0 — 응답에 담을 봉. {@code includeD3} 가 false 면 D+3 봉이 아직 없는 장전 상황. */
    private static List<KoreaInvestmentService.OhlcvData> response(boolean includeD3) {
        List<KoreaInvestmentService.OhlcvData> bars = new ArrayList<>();
        bars.add(bar(SIGNAL, "10000"));
        bars.add(bar(D1, "10100"));
        bars.add(bar(D2, "10200"));
        if (includeD3) bars.add(bar(D3, "10300"));   // 장중이면 이 값은 잠정 종가다
        return bars;
    }

    private record Rig(SignalOutcomeRepository repo, StockAnalysisService analysis,
                       SignalOutcome pending, SignalD3EvaluationService svc) {}

    @SuppressWarnings("unchecked")
    private static Rig rig(Clock clock, LocalDate signalDate) {
        var repo = mock(SignalOutcomeRepository.class);
        var hist = mock(StockPriceHistoryRepository.class);
        var analysis = mock(StockAnalysisService.class);
        var kis = mock(KoreaInvestmentService.class);
        ObjectProvider<StockAnalysisService> analysisP = mock(ObjectProvider.class);
        ObjectProvider<KoreaInvestmentService> kisP = mock(ObjectProvider.class);
        ObjectProvider<StockStatusService> statusP = mock(ObjectProvider.class);
        when(analysisP.getIfAvailable()).thenReturn(analysis);
        when(kisP.getIfAvailable()).thenReturn(kis);
        when(statusP.getIfAvailable()).thenReturn(null);
        when(kis.isConfigured()).thenReturn(true);
        // 지수는 창 전체 + 당일치까지 있다 — 지수 결측 때문에 막히지 않게
        when(kis.getIndexDailyOhlcv(any(), anyInt(), any())).thenReturn(List.of(
                new KoreaInvestmentService.IndexOhlcvData("20260915", null, null, null, new BigDecimal("3010")),
                new KoreaInvestmentService.IndexOhlcvData("20260916", null, null, null, new BigDecimal("3020")),
                new KoreaInvestmentService.IndexOhlcvData("20260917", null, null, null, new BigDecimal("3030"))));

        SignalOutcome pending = SignalOutcome.builder()
                .signalType("BUY").stockCode("005930").stockName("삼성전자").signalDate(signalDate)
                .priceAtSignal(new BigDecimal("10000")).bmPriceAtSignal(new BigDecimal("3000")).build();
        when(repo.findD3Pending(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(pending));
        when(hist.findByStockCodeAndDateRange(any(), any(), any())).thenReturn(List.<StockPriceHistory>of());

        var svc = new SignalD3EvaluationService(repo, hist, new MarketCalendarService(),
                analysisP, kisP, statusP, clock);
        return new Rig(repo, analysis, pending, svc);
    }

    private static void respondWith(Rig g, boolean includeD3) {
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(new StockAnalysisService.CollectResult(false, response(includeD3), 4));
    }

    // ==================== 장중 — 잠정값을 확정으로 저장하지 않는다 ====================

    @Nested
    @DisplayName("D+3 이 오늘인 행")
    class D3IsToday {

        @Test
        @DisplayName("10:00 장중: 응답에 오늘 장중 봉이 있어도 저장하지 않는다 — 잠정 종가가 OK 로 굳으면 영구 고정된다")
        void intradayBarIsNotStoredAsConfirmed() {
            Rig g = rig(at(D3, LocalTime.of(10, 0)), SIGNAL);
            respondWith(g, true);

            var report = g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).as("장중엔 어떤 상태도 쓰지 않는다").isNull();
            assertThat(report.notDue()).isEqualTo(1);
            assertThat(report.evaluatedByStatus()).isEmpty();
            verify(g.repo(), never()).save(any());
        }

        @Test
        @DisplayName("08:30 장전: 오늘 봉이 없어도 MISSING_BARS 를 쓰지 않는다 — 쓰면 재시도 7일에 걸려 정상 평가가 밀린다")
        void preMarketDoesNotRecordMissingBars() {
            Rig g = rig(at(D3, LocalTime.of(8, 30)), SIGNAL);
            respondWith(g, false);

            var report = g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).isNull();
            assertThat(report.notDue()).isEqualTo(1);
            verify(g.repo(), never()).save(any());
        }

        @Test
        @DisplayName("15:39 마감 직전: 아직 미도래")
        void justBeforeCloseIsNotDue() {
            Rig g = rig(at(D3, LocalTime.of(15, 39)), SIGNAL);
            respondWith(g, true);

            g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).isNull();
        }

        @Test
        @DisplayName("19:45 장후(야간 크론 시각): 확정 종가로 평가한다")
        void afterCloseIsEvaluated() {
            Rig g = rig(at(D3, LocalTime.of(19, 45)), SIGNAL);
            respondWith(g, true);

            var report = g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).isEqualTo("OK");
            assertThat(g.pending().getD3EndDate()).isEqualTo(D3);
            assertThat(g.pending().getD3Close()).isEqualByComparingTo("10300");
            assertThat(g.pending().getD3PctChange()).isEqualByComparingTo("3.0000");
            assertThat(report.evaluatedByStatus()).containsEntry("OK", 1);
        }

        @Test
        @DisplayName("force 도 같은 경계를 쓴다 — 장중 force 백필이 잠정값을 확정하지 않는다")
        void forceUsesTheSameBoundary() {
            Rig g = rig(at(D3, LocalTime.of(11, 0)), SIGNAL);
            respondWith(g, true);

            g.svc().run(false, 120, true);

            assertThat(g.pending().getD3Status()).isNull();
            verify(g.repo(), never()).save(any());
        }

        @Test
        @DisplayName("dryRun 도 같은 경계 — 미도래 행을 도래로 세지 않는다")
        void dryRunUsesTheSameBoundary() {
            Rig g = rig(at(D3, LocalTime.of(11, 0)), SIGNAL);

            var report = g.svc().run(true, 120, false);

            assertThat(report.dueRows()).isZero();
            assertThat(report.notDue()).isEqualTo(1);
            assertThat(report.coverageByType()).isEmpty();
        }
    }

    // ==================== 과거 확정일 — 장중에도 처리한다 ====================

    @Nested
    @DisplayName("D+3 이 과거 확정 거래일인 행")
    class D3IsPast {

        /** 9/11(금) 기록 → D+1 9/14, D+2 9/15, D+3 9/16. 9/17 장중이면 D+3 은 이미 확정. */
        private static final LocalDate PAST_SIGNAL = LocalDate.of(2026, 9, 11);

        @Test
        @DisplayName("장중이라도 평가한다 — 장중이라는 이유로 전체 백필을 막지 않는다")
        void pastWindowIsEvaluatedEvenIntraday() {
            Rig g = rig(at(D3, LocalTime.of(10, 0)), PAST_SIGNAL);
            when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                    .thenReturn(new StockAnalysisService.CollectResult(false, List.of(
                            bar(LocalDate.of(2026, 9, 11), "10000"),
                            bar(LocalDate.of(2026, 9, 14), "10100"),
                            bar(LocalDate.of(2026, 9, 15), "10200"),
                            bar(LocalDate.of(2026, 9, 16), "10300")), 4));

            var report = g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).isEqualTo("OK");
            assertThat(g.pending().getD3EndDate()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(report.evaluatedByStatus()).containsEntry("OK", 1);
        }
    }

    // ==================== 주말·연휴 ====================

    @Nested
    @DisplayName("휴장일 경계")
    class Holidays {

        @Test
        @DisplayName("토요일 오전: 금요일이 마지막 확정일 — 금요일 D+3 행은 평가된다")
        void saturdayUsesFridayAsSettled() {
            // 9/14(월) 기록 → D+3 9/17(목). 9/19(토) 오전 = 9/18(금)까지 확정
            Rig g = rig(at(LocalDate.of(2026, 9, 19), LocalTime.of(9, 0)), SIGNAL);
            respondWith(g, true);

            g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).isEqualTo("OK");
        }

        @Test
        @DisplayName("추석 연휴(9/24~9/26) 중 D+3 이 연휴 다음 거래일이면 그날 마감 전까지 미도래")
        void holidayWindowIsNotDueUntilItCloses() {
            // 9/22(화) 기록 → D+1 9/23(수), D+2 9/28(월, 연휴 9/24~26 + 주말 건너뜀), D+3 9/29(화)
            LocalDate signal = LocalDate.of(2026, 9, 22);
            Rig g = rig(at(LocalDate.of(2026, 9, 29), LocalTime.of(10, 0)), signal);
            when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                    .thenReturn(new StockAnalysisService.CollectResult(false, List.of(bar(signal, "10000")), 1));

            var report = g.svc().run(false, 120, false);

            assertThat(g.pending().getD3Status()).isNull();
            assertThat(report.notDue()).isEqualTo(1);
        }
    }

    // ==================== 확정 경계 순수 판정 ====================

    @Nested
    @DisplayName("lastSettledTradingDay — 시계 판정은 서비스 계층에")
    class SettledDay {

        private final MarketCalendarService cal = new MarketCalendarService();

        @Test
        @DisplayName("거래일 마감 전이면 직전 거래일, 마감+여유 뒤면 당일")
        void tradingDayBoundary() {
            assertThat(SignalD3EvaluationService.lastSettledTradingDay(
                    LocalDateTime.of(D3, LocalTime.of(10, 0)), cal)).isEqualTo(D2);
            assertThat(SignalD3EvaluationService.lastSettledTradingDay(
                    LocalDateTime.of(D3, LocalTime.of(15, 39)), cal)).isEqualTo(D2);
            assertThat(SignalD3EvaluationService.lastSettledTradingDay(
                    LocalDateTime.of(D3, LocalTime.of(19, 45)), cal)).isEqualTo(D3);
        }

        @Test
        @DisplayName("주말·공휴일은 직전 거래일")
        void closedDaysFallBack() {
            assertThat(SignalD3EvaluationService.lastSettledTradingDay(
                    LocalDateTime.of(LocalDate.of(2026, 9, 19), LocalTime.of(9, 0)), cal))
                    .isEqualTo(LocalDate.of(2026, 9, 18));
            assertThat(SignalD3EvaluationService.lastSettledTradingDay(
                    LocalDateTime.of(LocalDate.of(2026, 9, 25), LocalTime.of(23, 0)), cal))
                    .isEqualTo(LocalDate.of(2026, 9, 23));
        }

        @Test
        @DisplayName("KRX 종료(15:40) 기준이지 NXT 20:00 이 아니다 — 16:30 이면 이미 당일 확정")
        void usesKrxCloseNotNxt() {
            assertThat(SignalD3EvaluationService.lastSettledTradingDay(
                    LocalDateTime.of(D3, LocalTime.of(16, 30)), cal)).isEqualTo(D3);
        }
    }
}
