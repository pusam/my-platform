package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.AsyncCrawlerService;
import com.myplatform.backend.service.BatchJobMonitorService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.StockFinancialDataCollector;
import com.myplatform.backend.service.StockFinancialDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장이 늦게 닫히는 날(2026-11-19 수능일, 정규장 10:00~16:30)의 15:38 재무 수집(2026-11-10 리허설).
 *
 * <p>재현: 15:38 회차는 '장 마감 직후' 수집이라 그 시각 주가로 그날 일별 행의 시가총액·PER·PBR 을 다시 만든다. 수능일 15:38 은
 * 장 마감 한 시간 전이라 장중 가격이 그날(11/19) 일별 행에 굳는다 — 그 행을 17:00 스냅샷(시그널 기록)·20:05 스냅샷·16~19시 AI
 * 로테이션이 읽는다. 크론 시각은 그대로 두고 그날은 건너뛴다 — 그날 행은 08:30 회차 값(직전 거래일 종가 기준)으로 남고,
 * 그날 종가 기준 값은 다음 거래일 08:30 회차가 쓴다.
 */
class FinancialDataSchedulerLateCloseTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate CSAT = LocalDate.of(2026, 11, 19);
    private static final LocalDate DAY_BEFORE = LocalDate.of(2026, 11, 18);

    private final AsyncCrawlerService crawler = mock(AsyncCrawlerService.class);
    private final SchedulerLockService lock = mock(SchedulerLockService.class);

    private FinancialDataScheduler schedulerAt(LocalDate day, LocalTime time) {
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        Clock clock = Clock.fixed(LocalDateTime.of(day, time).atZone(KST).toInstant(), KST);
        return new FinancialDataScheduler(mock(StockFinancialDataService.class), mock(StockFinancialDataRepository.class),
                crawler, mock(BatchJobMonitorService.class), lock, new MarketCalendarService(),
                mock(StockFinancialDataCollector.class), clock);
    }

    @Test
    @DisplayName("재현: 수능일 15:38 은 장중이다 — 장중 가격으로 그날 일별 재무 행을 다시 만들지 않는다")
    void lateCloseDaySkipsAfternoonCollection() {
        schedulerAt(CSAT, LocalTime.of(15, 38)).collectAfternoon();

        verify(crawler, never()).collectAllInOneAsync();
    }

    @Test
    @DisplayName("평소 거래일 15:38 은 종전대로 수집한다")
    void ordinaryDayStillCollects() {
        schedulerAt(DAY_BEFORE, LocalTime.of(15, 38)).collectAfternoon();

        verify(crawler).collectAllInOneAsync();
    }

    @Test
    @DisplayName("수능일 08:30 회차는 종전대로 — 장 시작 전이고 직전 거래일 종가 기준이다")
    void lateCloseDayMorningStillCollects() {
        schedulerAt(CSAT, LocalTime.of(8, 30)).collectMorning();

        verify(crawler).collectAllInOneAsync();
    }
}
