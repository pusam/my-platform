package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 캐시 워머는 장이 열린 날의 NXT 시간(08:00~20:00)에만 돈다(2026-10-07).
 *
 * <p>재현: 워머들의 '장 시간' 판정이 시각만 봐서 토·일·평일 공휴일에도 08~20시 내내 돌았다 — 스마트머니 워머는 30초마다 KIS 를
 * 두 번(하루 약 2,880회), 🎯종합 순위 워머는 시각조차 보지 않아 밤·주말·공휴일에도 25분마다 차트 패턴용 KIS 일봉을 불렀다
 * (패턴 캐시 30분이라 50분마다 약 80회 — 10/5 대체공휴일 실행 58회). 장이 닫힌 날 받은 값은 직전 거래일 그대로라 쓸모가 없다.
 * 시간 창은 달력 한 곳({@link MarketCalendarService#isNxtSession})이 정한다 — §2 의 '표시·추천·캐시워밍 = NXT 08:00~20:00'.
 */
class MarketWarmWindowHolidayTest {

    private final MarketCalendarService calendar = new MarketCalendarService();

    @Nested
    class NxtSession {
        @Test
        @DisplayName("거래일 08:00~20:00 은 NXT 시간(양 끝 포함)")
        void tradingDayWindow() {
            LocalDate thu = LocalDate.of(2026, 10, 8);
            assertThat(calendar.isNxtSession(thu, LocalTime.of(8, 0))).isTrue();
            assertThat(calendar.isNxtSession(thu, LocalTime.of(10, 0))).isTrue();
            assertThat(calendar.isNxtSession(thu, LocalTime.of(20, 0))).isTrue();
            assertThat(calendar.isNxtSession(thu, LocalTime.of(7, 59))).isFalse();
            assertThat(calendar.isNxtSession(thu, LocalTime.of(20, 1))).isFalse();
        }

        @Test
        @DisplayName("재현: 평일 공휴일·주말은 같은 시각이어도 아니다")
        void closedDays() {
            assertThat(calendar.isNxtSession(LocalDate.of(2026, 10, 9), LocalTime.of(10, 0))).isFalse();    // 한글날(금)
            assertThat(calendar.isNxtSession(LocalDate.of(2026, 10, 5), LocalTime.of(10, 0))).isFalse();    // 개천절 대체(월)
            assertThat(calendar.isNxtSession(LocalDate.of(2026, 10, 10), LocalTime.of(10, 0))).isFalse();   // 토
        }
    }

    @Test
    @DisplayName("재현: 장이 닫힌 날엔 스마트머니 워머가 KIS 를 부르지 않는다")
    void smartMoneyWarmerSkipsClosedDay() {
        InvestorTradeService investorTrade = mock(InvestorTradeService.class);
        MarketCalendarService closed = mock(MarketCalendarService.class);
        when(closed.isNxtSession(any(), any())).thenReturn(false);
        MarketCacheWarmerService warmer = new MarketCacheWarmerService(
                mock(RedisCacheService.class), investorTrade, mock(InvestorSurgeService.class), mock(SectorTradingService.class),
                mock(SectorAnalysisService.class), mock(AiStockAnalysisService.class), mock(SectorOpportunityService.class),
                mock(MarketIndicatorService.class), mock(MarketTimingService.class),
                mock(ChartPatternClient.class), mock(SectorStockConfig.class), closed);

        warmer.warmSmartMoneyRealtime();
        warmer.warmKisApiData();

        verifyNoInteractions(investorTrade);
    }

    @Nested
    @SuppressWarnings("unchecked")
    class CompositeRankingWarmup {
        private final com.myplatform.backend.repository.StockPriceRepository prices =
                mock(com.myplatform.backend.repository.StockPriceRepository.class);
        private final MarketCalendarService session = mock(MarketCalendarService.class);
        private final CompositeSignalService service = new CompositeSignalService(
                mock(ChartPatternService.class), mock(StockPriceService.class), mock(InvestorTradeService.class),
                mock(AiStockAnalysisService.class), mock(StockMasterService.class), prices, mock(CacheManager.class),
                mock(ObjectProvider.class), session);

        @Test
        @DisplayName("재현: 장이 닫힌 시간(밤·주말·공휴일)엔 🎯종합 순위 워밍이 평가를 시작하지 않는다")
        void skipsOutsideSession() {
            when(session.isNxtSession(any(), any())).thenReturn(false);

            service.scheduledWarmup();

            verifyNoInteractions(prices);
            assertThat((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(service, "rankingComputing"))
                    .isFalse();
        }

        @Test
        @DisplayName("장 시간엔 종전대로 평가한다")
        void runsInSession() {
            when(session.isNxtSession(any(), any())).thenReturn(true);

            service.scheduledWarmup();

            verify(prices).findTopVolumeStockCodes(any(), any());
        }
    }
}
