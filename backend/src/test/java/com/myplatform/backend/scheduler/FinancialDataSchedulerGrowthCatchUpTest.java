package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.AsyncCrawlerService;
import com.myplatform.backend.service.BatchJobMonitorService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.StockFinancialDataCollector;
import com.myplatform.backend.service.StockFinancialDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기동 시 성장률 따라잡기(2026-09-29).
 *
 * <p>올인원 배치(1 KIS 수집 → 2 성장률)는 메모리 안 비동기라 배포·재시작이 사이를 끊으면 그날 최신 행의 성장률이
 * 통째로 비었다(9/11·9/17·9/21 실측 — 수집일의 27%). V62 가 옛 값을 비운 직후도 같은 상태다.
 * 기동 때 최신 일별 행에 성장률이 하나도 없으면 2단계만 다시 돈다 — 분기 원본(DB)만 읽고 KIS 는 부르지 않는다.
 */
class FinancialDataSchedulerGrowthCatchUpTest {

    private StockFinancialDataRepository repo;
    private AsyncCrawlerService crawler;
    private StockFinancialDataCollector collector;
    private FinancialDataScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(StockFinancialDataRepository.class);
        crawler = mock(AsyncCrawlerService.class);
        collector = mock(StockFinancialDataCollector.class);
        scheduler = new FinancialDataScheduler(mock(StockFinancialDataService.class), repo, crawler,
                mock(BatchJobMonitorService.class), mock(SchedulerLockService.class),
                mock(MarketCalendarService.class), collector);
        when(repo.count()).thenReturn(120_000L);
    }

    @Test
    @DisplayName("최신 일별 행에 성장률이 하나도 없으면 기동 때 성장률 계산을 돈다")
    void catchesUpWhenLatestRowsHaveNoGrowth() {
        when(repo.countGrowthMeasuredAtLatestDate()).thenReturn(0L);

        scheduler.onApplicationReady();

        verify(collector).calculateAndUpdateGrowthRates();
    }

    @Test
    @DisplayName("이미 채워져 있으면 다시 돌지 않는다")
    void skipsWhenAlreadyMeasured() {
        when(repo.countGrowthMeasuredAtLatestDate()).thenReturn(2_100L);

        scheduler.onApplicationReady();

        verify(collector, never()).calculateAndUpdateGrowthRates();
    }

    @Test
    @DisplayName("올인원 배치가 돌고 있으면 겹쳐 돌지 않는다 — 그 2단계가 채운다")
    void skipsWhileAllInOneBatchRuns() {
        when(crawler.isTaskRunning(FinancialDataScheduler.ALL_IN_ONE_TASK)).thenReturn(true);
        when(repo.countGrowthMeasuredAtLatestDate()).thenReturn(0L);

        scheduler.onApplicationReady();

        verify(collector, never()).calculateAndUpdateGrowthRates();
    }

    @Test
    @DisplayName("판정 조회가 실패해도 기동 처리를 깨지 않는다 — 다음 올인원 배치가 채운다")
    void failureDoesNotPropagate() {
        when(repo.countGrowthMeasuredAtLatestDate()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> scheduler.onApplicationReady()).doesNotThrowAnyException();
        verify(collector, never()).calculateAndUpdateGrowthRates();
    }
}
