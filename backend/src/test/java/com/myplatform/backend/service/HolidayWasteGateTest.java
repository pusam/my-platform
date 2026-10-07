package com.myplatform.backend.service;

import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.backend.scheduler.DailyReportScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 장이 닫힌 날엔 돌 이유가 없는 평일 크론 다섯 개 — 휴장일 게이트(2026-10-07).
 *
 * <p>재현: cron 요일 필드(MON-FRI)는 공휴일을 모른다(CLAUDE.md §4c 휴장일 달력). 대체공휴일 10/5 에 다음이 그대로 돌았다(운영
 * batch_job_execution·생성 행): 08:00 재료 일괄 워밍(Gemini 분류 27건 저장 + 발굴 5트랙 계산) · 09/12/15시 AI 주식 분석(90점+
 * 종목마다 'AI TOP PICK' 텔레그램 — 직전 거래일 데이터 그대로라 같은 알림이 회차마다) · 관심종목 리스크 10분 감시(급락 판정이
 * 시세 등락률을 보는데 휴장일 시세는 직전 거래일 값이다 — 쿨다운 1시간이라 직전 거래일에 −3% 였던 관심종목이면 하루 종일
 * '장중 급락' 알림) · 16:00 일일 포트폴리오 리포트(텔레그램) · 20:10 인기종목 차트 프리워밍(KIS). 수동 트리거(관리 화면)는
 * 그대로 둔다 — 게이트는 크론 입구에만.
 */
class HolidayWasteGateTest {

    private static MarketCalendarService calendar(boolean closedToday) {
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(closedToday);
        when(calendar.isMarketClosed(any())).thenReturn(closedToday);
        return calendar;
    }

    @Nested
    @SuppressWarnings("unchecked")
    class CatalystUnionWarm {
        private final StockCatalystService catalysts = mock(StockCatalystService.class);
        private final SchedulerLockService locks = mock(SchedulerLockService.class);

        private CatalystWarmingService service(boolean closed) {
            CatalystWarmingService s = new CatalystWarmingService(mock(RecommendationService.class), catalysts, locks,
                    mock(StockWatchlistRepository.class), mock(BotTradingPositionRepository.class),
                    mock(ObjectProvider.class), mock(ObjectProvider.class), calendar(closed));
            ReflectionTestUtils.setField(s, "unionWarmEnabled", true);
            return s;
        }

        @Test
        @DisplayName("재현: 휴장일엔 08:00 재료 일괄 워밍이 Gemini 분류·발굴 계산을 하지 않는다")
        void skipsOnHoliday() {
            service(true).scheduledWarmUnion();
            verifyNoInteractions(locks, catalysts);
        }

        @Test
        @DisplayName("거래일엔 종전대로")
        void runsOnTradingDay() {
            service(false).scheduledWarmUnion();
            verify(locks).tryLock(anyString(), any());
        }
    }

    @Nested
    class AiAnalysis {
        private final InvestorTradeService investorTrade = mock(InvestorTradeService.class);
        private final TelegramNotificationService telegram = mock(TelegramNotificationService.class);

        private AiStockAnalysisService service(boolean closed) {
            AiStockAnalysisService s = mock(AiStockAnalysisService.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(s, "investorTradeService", investorTrade);
            ReflectionTestUtils.setField(s, "telegramService", telegram);
            ReflectionTestUtils.setField(s, "marketCalendar", calendar(closed));
            return s;
        }

        @Test
        @DisplayName("재현: 휴장일엔 AI 주식 분석이 돌지 않는다 — 같은 데이터로 'AI TOP PICK' 알림이 회차마다 나갔다")
        void skipsOnHoliday() {
            service(true).scheduledAnalysis();
            verifyNoInteractions(investorTrade, telegram);
        }

        @Test
        @DisplayName("거래일엔 종전대로 후보를 모은다")
        void runsOnTradingDay() {
            service(false).scheduledAnalysis();
            verify(investorTrade).getConsecutiveBuyStocks("FOREIGN", 3);
        }
    }

    @Nested
    class WatchlistRisk {
        private final SchedulerLockService locks = mock(SchedulerLockService.class);
        private final StockWatchlistRepository watchlist = mock(StockWatchlistRepository.class);

        private WatchlistRiskMonitorService service(boolean closed) {
            return new WatchlistRiskMonitorService(watchlist, mock(AlertHistoryRepository.class), mock(StockPriceService.class),
                    mock(DartService.class), mock(InvestorDailyTradeRepository.class), mock(TelegramNotificationService.class),
                    locks, calendar(closed));
        }

        @Test
        @DisplayName("재현: 휴장일엔 관심종목 리스크 감시가 돌지 않는다 — 직전 거래일 등락률로 '장중 급락'이 1시간마다 나갔다")
        void skipsOnHoliday() {
            service(true).scheduledRiskMonitor();
            verifyNoInteractions(locks, watchlist);
        }

        @Test
        @DisplayName("거래일엔 종전대로")
        void runsOnTradingDay() {
            service(false).scheduledRiskMonitor();
            verify(locks).tryLock(anyString(), any());
        }
    }

    @Nested
    class DailyReport {
        private final SchedulerLockService locks = mock(SchedulerLockService.class);
        private final TelegramNotificationService telegram = mock(TelegramNotificationService.class);

        private DailyReportScheduler scheduler(boolean closed) {
            DailyReportScheduler s = new DailyReportScheduler(mock(VirtualTradeService.class),
                    mock(AiStrategySnapshotRepository.class), telegram, locks, calendar(closed));
            ReflectionTestUtils.setField(s, "schedulerEnabled", true);
            return s;
        }

        @Test
        @DisplayName("재현: 휴장일엔 16:00 일일 포트폴리오 리포트를 보내지 않는다")
        void skipsOnHoliday() {
            scheduler(true).sendDailyReport();
            verifyNoInteractions(locks, telegram);
        }

        @Test
        @DisplayName("거래일엔 종전대로")
        void runsOnTradingDay() {
            scheduler(false).sendDailyReport();
            verify(locks).tryLock(anyString(), any());
        }
    }

    @Nested
    class ChartWarm {
        private final StockDetailCacheService cache = mock(StockDetailCacheService.class);
        private final StockWatchlistRepository watchlist = mock(StockWatchlistRepository.class);

        private StockChartWarmService service(boolean closed) {
            StockChartWarmService s = new StockChartWarmService(cache, watchlist, mock(BotTradingPositionRepository.class),
                    calendar(closed));
            ReflectionTestUtils.setField(s, "warmEnabled", true);
            return s;
        }

        @Test
        @DisplayName("재현: 휴장일엔 20:10 인기종목 차트 프리워밍이 KIS 를 부르지 않는다")
        void skipsOnHoliday() {
            service(true).warmPopularCharts();
            verifyNoInteractions(cache, watchlist);
        }

        @Test
        @DisplayName("거래일엔 종전대로 대상을 모은다")
        void runsOnTradingDay() {
            service(false).warmPopularCharts();
            verify(watchlist).findByIsActiveTrue();
        }
    }
}
