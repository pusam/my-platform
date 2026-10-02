package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 휴장일 '유령 일자 행' 가드(2026-09-28).
 *
 * <p><b>무슨 일이 있었나</b>: 2026 추석 연휴(9/24·25, 목·금)는 달력에 <b>제대로 들어 있었는데도</b> 날짜가
 * 휴장일인 행이 쌓였다 — 투자자 일별 수급 167행/일, 시장 상태(ADR 원천) 2행/일. 달력을 안 보는 수집 경로가
 * 따로 있었기 때문이다: 둘 다 {@code MON-FRI} cron + <b>주말만 거르는</b> 옛 가드(달력 도입 전 코드)라
 * 평일 공휴일을 통과시켰고, KIS/시세 소스는 휴장일에도 직전 거래일 값을 200 으로 준다.
 *
 * <p>ADR 은 "최근 20<b>행</b>"으로 계산해서({@code calculateAdr}) 휴장일 행이 들어가면 직전 거래일 값이
 * 20일 창에 여러 번 세어진다. 연속 순매수는 달력으로 휴장일을 건너뛰어 부풀지 않지만 행은 남는다.
 */
class HolidayPhantomRowGateTest {

    @Nested
    @DisplayName("투자자 일별 수급 16:00 보완 수집(KisInvestorDataCollector)")
    class InvestorDaily {

        private final InvestorDailyTradeRepository repo = mock(InvestorDailyTradeRepository.class);
        private final KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        private final MarketCalendarService calendar = mock(MarketCalendarService.class);
        private final KisInvestorDataCollector collector = new KisInvestorDataCollector(
                repo, kis, new ObjectMapper(), mock(StockMasterService.class), calendar);

        @Test
        @DisplayName("휴장일이면 DB 도 KIS 도 건드리지 않는다 — 주말만 보던 가드가 평일 공휴일을 통과시켰다")
        void skipsOnHoliday() {
            when(calendar.isMarketClosed(any(LocalDate.class))).thenReturn(true);

            collector.scheduledDailyCollection();

            verifyNoInteractions(repo, kis);
        }

        @Test
        @DisplayName("거래일이면 '그날 확정 기록이 있나'부터 본다 — 있으면 KIS 를 부르지 않는다")
        void proceedsOnTradingDay() {
            when(calendar.isMarketClosed(any(LocalDate.class))).thenReturn(false);
            // 15:50 정규 수집이 외국인·기관을 둘 다 확정 시각 이후에 썼다(2026-10-01 — 행 존재가 아니라 확정 여부)
            when(repo.summarizeByInvestorType(any())).thenAnswer(inv -> {
                LocalDate d = inv.getArgument(0);
                return java.util.List.<Object[]>of(
                        new Object[]{"FOREIGN", d.atTime(15, 50, 1)},
                        new Object[]{"INSTITUTION", d.atTime(15, 50, 2)});
            });

            collector.scheduledDailyCollection();

            verify(repo).summarizeByInvestorType(any());
            verifyNoInteractions(kis);   // 15:50 에 이미 확정 수집된 경로
        }

        @Test
        @DisplayName("행은 있지만 장중 잠정치면 16:00 은 덧붙이지 않고 18:00 보완 수집(삭제 후 재수집)에 맡긴다")
        void provisionalRowsAreLeftToEveningRetry() {
            when(calendar.isMarketClosed(any(LocalDate.class))).thenReturn(false);
            when(repo.summarizeByInvestorType(any())).thenAnswer(inv -> {
                LocalDate d = inv.getArgument(0);
                return java.util.List.<Object[]>of(new Object[]{"FOREIGN", d.atTime(11, 13)});
            });

            collector.scheduledDailyCollection();

            verifyNoInteractions(kis);   // 이 경로는 지우지 않고 덧붙이기만 해서 중복이 생긴다
        }
    }

    @Nested
    @DisplayName("시장 상태(ADR 원천) 수집(MarketTimingService)")
    class MarketDailyStatus {

        private final MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);

        private MarketTimingService serviceWith(MarketCalendarService calendar) {
            return new MarketTimingService(repo, mock(TelegramNotificationService.class),
                    mock(RedisCacheService.class), calendar, mock(KoreaInvestmentService.class));
        }

        @Test
        @DisplayName("16:30 스케줄 수집은 휴장일이면 아무 행도 만들지 않는다")
        void scheduledCollectionSkipsOnHoliday() {
            MarketCalendarService calendar = mock(MarketCalendarService.class);
            when(calendar.isMarketClosed()).thenReturn(true);

            serviceWith(calendar).scheduledMarketDataCollection();

            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("부팅 초기 수집은 휴장일(평일 공휴일·주말)이면 조회도 수집도 하지 않는다")
        void bootInitializationSkipsOnClosedDays() {
            MarketTimingService s = serviceWith(new MarketCalendarService());

            s.initializeIfEmptyNow(LocalDate.of(2026, 9, 25));   // 추석(금)
            s.initializeIfEmptyNow(LocalDate.of(2026, 9, 27));   // 일요일 — 예전엔 금요일 행이 없으면 일요일 날짜로 저장했다

            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("부팅 초기 수집은 거래일이면 종전대로 오늘 행 유무부터 본다")
        void bootInitializationChecksTodayOnTradingDay() {
            when(repo.findByMarketTypeAndTradeDate(any(), any())).thenReturn(java.util.Optional.of(new com.myplatform.backend.entity.MarketDailyStatus()));

            serviceWith(new MarketCalendarService()).initializeIfEmptyNow(LocalDate.of(2026, 9, 28));

            verify(repo).findByMarketTypeAndTradeDate("KOSPI", LocalDate.of(2026, 9, 28));
        }

        // 기간 백필 경로(collectMarketDataForPeriod)는 2026-10-02 은퇴 — 과거 등락 수 소스가 없다(AdrBackfillRetiredTest)
    }
}
