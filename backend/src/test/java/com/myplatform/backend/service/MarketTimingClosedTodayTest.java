package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 시장 상태 응답이 '오늘 휴장'을 말한다(2026-10-07) — 화면이 평일 공휴일에 '장 진행 중'이라고 하지 않게.
 *
 * <p>재현: 허브의 시간대 배너는 주말만 알아서 평일 공휴일(10/9 한글날 같은 날)엔 08~20시 내내 '🟢 장 진행 중 · 실시간 추적 중'이었다
 * (2026-10-04 점검의 남은 LOW). 휴장일 달력은 백엔드 {@link MarketCalendarService} 한 곳이다 — 화면에 두 번째 달력을 만들지 않고
 * 이미 부르는 시장 상태 응답에 그 판정을 싣는다.
 */
class MarketTimingClosedTodayTest {

    private final MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);

    private MarketTimingService serviceWith(MarketCalendarService calendar) {
        return new MarketTimingService(repo, mock(TelegramNotificationService.class),
                mock(RedisCacheService.class), calendar, mock(KoreaInvestmentService.class)) {
            @Override
            BigDecimal[] crawlIndexInfo(String marketType) {
                return null;
            }

            @Override
            BigDecimal[] fetchIndexFromNaverApi(String marketType) {
                return null;
            }
        };
    }

    private static MarketDailyStatus row(String market) {
        return MarketDailyStatus.builder()
                .marketType(market).tradeDate(LocalDate.of(2026, 10, 8))
                .advancingCount(500).decliningCount(400).unchangedCount(20).totalCount(920)
                .indexClose(new BigDecimal("6900.00")).indexChangeRate(new BigDecimal("0.50"))
                .build();
    }

    private MarketTimingDto timingWhenClosedToday(boolean closed) {
        MarketCalendarService calendar = spy(new MarketCalendarService());
        doReturn(closed).when(calendar).isMarketClosed();
        when(repo.findLatestAll()).thenReturn(List.of(row("KOSPI"), row("KOSDAQ")));
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(any(), any(), any()))
                .thenAnswer(inv -> List.of(row(inv.getArgument(0))));
        return serviceWith(calendar).getCurrentMarketTiming();
    }

    @Test
    @DisplayName("재현: 오늘이 휴장일이면 응답에 marketClosedToday=true")
    void closedTodayIsReported() {
        assertThat(timingWhenClosedToday(true).getMarketClosedToday()).isTrue();
    }

    @Test
    @DisplayName("거래일이면 false")
    void tradingDayIsNotClosed() {
        assertThat(timingWhenClosedToday(false).getMarketClosedToday()).isFalse();
    }
}
