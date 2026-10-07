package com.myplatform.backend.service;

import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 종합추천은 평일 공휴일에 '장중'으로 굴지 않는다(2026-10-07).
 *
 * <p>재현: 장중 판정({@code isTradingHours})과 두 크론(가격 도달 알림 5분·상승 가속 알림 09:00)이 요일만 봐서, 대체공휴일 10/5 에
 * getTop5 가 백그라운드 계산을 돌리고 그 기술 채점이 일봉 595종목을 KIS 로 받았다(운영 실측 — 10/2 봉이 10/5 09시에 생성).
 * 가격 도달 알림은 더 나쁘다 — 휴장일 시세는 직전 거래일 값이고 '오늘 보낸 임계' 기록은 날마다 초기화되므로, 직전 거래일에
 * +5% 오른 후보가 휴장일에 다시 '매수후보 수익 도달 +5%' 로 나간다. cron 요일 필드는 공휴일을 모른다(CLAUDE.md §4c 휴장일 달력).
 */
class RecommendationHolidayGateTest {

    private static final LocalDate HANGUL_DAY = LocalDate.of(2026, 10, 9);   // 금요일, 한글날
    private static final LocalDate TRADING_DAY = LocalDate.of(2026, 10, 8);

    private RecommendationService serviceOn(boolean holidayToday) {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(holidayToday);
        when(calendar.isMarketClosed(HANGUL_DAY)).thenReturn(true);
        when(calendar.isMarketClosed(TRADING_DAY)).thenReturn(false);
        ReflectionTestUtils.setField(service, "marketCalendar", calendar);
        ReflectionTestUtils.setField(service, "stockPriceService", mock(StockPriceService.class));
        ReflectionTestUtils.setField(service, "priceAlertedToday", new java.util.concurrent.ConcurrentHashMap<>());
        return service;
    }

    @Test
    @DisplayName("재현: 평일 공휴일 10시는 장중이 아니다 — 예전엔 요일만 봐서 백그라운드 계산·'실시간' 표시가 켜졌다")
    void weekdayHolidayIsNotTradingHours() {
        RecommendationService service = serviceOn(false);
        Boolean holiday = ReflectionTestUtils.invokeMethod(service, "isTradingHours", HANGUL_DAY.atTime(10, 0));
        Boolean trading = ReflectionTestUtils.invokeMethod(service, "isTradingHours", TRADING_DAY.atTime(10, 0));
        Boolean night = ReflectionTestUtils.invokeMethod(service, "isTradingHours", TRADING_DAY.atTime(21, 0));
        assertThat(holiday).isFalse();
        assertThat(trading).isTrue();     // 거래일 판정은 그대로
        assertThat(night).isFalse();
    }

    @Test
    @DisplayName("재현: 휴장일엔 가격 도달 알림이 후보·시세를 읽지 않는다 — 직전 거래일 등락률로 알림이 다시 나갔다")
    void priceTargetCronSkipsOnHoliday() {
        RecommendationService service = serviceOn(true);
        doReturn(new RecommendationService.Top5Response(
                List.of(RecommendationDto.builder().stockCode("005930").stockName("삼성전자").totalScore(70).build()),
                "15:00 기준", true, Map.of())).when(service).getTop5();

        service.checkRecommendationPriceTargets();

        verify(service, never()).getTop5();
    }

    @Test
    @DisplayName("거래일엔 종전대로 후보를 읽는다")
    void priceTargetCronRunsOnTradingDay() {
        RecommendationService service = serviceOn(false);
        doReturn(new RecommendationService.Top5Response(List.of(), "15:00 기준", true, Map.of())).when(service).getTop5();

        service.checkRecommendationPriceTargets();

        verify(service).getTop5();
    }

    @Test
    @DisplayName("재현: 휴장일엔 09:00 상승 가속 알림이 전 종목 계산을 돌리지 않는다")
    void strongBuyCronSkipsOnHoliday() {
        RecommendationService service = serviceOn(true);
        doReturn(CompletableFuture.completedFuture(List.<RecommendationDto>of())).when(service).startOrJoinCalculation();

        service.detectAndAlertNewStrongBuys();

        verify(service, never()).startOrJoinCalculation();
    }

    @Test
    @DisplayName("거래일엔 종전대로 계산한다")
    void strongBuyCronRunsOnTradingDay() {
        RecommendationService service = serviceOn(false);
        doReturn(CompletableFuture.completedFuture(List.<RecommendationDto>of())).when(service).startOrJoinCalculation();

        service.detectAndAlertNewStrongBuys();

        verify(service).startOrJoinCalculation();
    }
}
