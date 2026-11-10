package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 섹터 3분 스냅샷은 그날의 정규장 안에서만 쌓는다(2026-11-10 수능일 리허설).
 *
 * <p>재현: 수집 판정이 09:00~15:40 고정이라 2026-11-19(정규장 10:00~16:30, 마감 버퍼 16:40)엔 ① 장이 열리기 전 09:00~09:57 에
 * 스냅샷을 쌓아 그 값이 다음 거래일의 '같은 시각' 자금 흐름 비교 기준이 되고 ② 15:40 뒤로는 장이 열려 있는데 수집을 멈춰,
 * 17:00 스냅샷(시그널 기록)의 국면 입력(섹터 전 종목 평균 등락률)이 15:39 값으로 굳는다. 크론 시간 필드(9-15)는 그대로라
 * 16:00~16:40 은 여전히 비고, 이 수정으로 수집 끝이 15:39 → 15:57 로 늘어난다.
 */
class SectorSnapshotLateSessionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate CSAT = LocalDate.of(2026, 11, 19);
    private static final LocalDate DAY_BEFORE = LocalDate.of(2026, 11, 18);

    private StockPriceService stockPriceService;
    private SectorTradingService service;

    @BeforeEach
    void setUp() {
        stockPriceService = mock(StockPriceService.class);
        ThreadPoolTaskExecutor sameThread = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(sameThread).execute(any(Runnable.class));
        service = new SectorTradingService(mock(SectorStockConfig.class), stockPriceService, mock(StockStatusService.class),
                sameThread, mock(RedisCacheService.class), new MarketCalendarService());
    }

    private void collectAt(LocalDate day, LocalTime time) {
        service.clock = Clock.fixed(LocalDateTime.of(day, time).atZone(KST).toInstant(), KST);
        service.scheduledSnapshotCollection();
    }

    @Test
    @DisplayName("재현: 수능일 09:30 은 장이 열리기 전이다 — 스냅샷을 쌓지 않는다")
    void lateOpenDaySkipsBeforeOpen() {
        collectAt(CSAT, LocalTime.of(9, 30));

        verify(stockPriceService, never()).getStockPrices(anyList());
    }

    @Test
    @DisplayName("재현: 수능일 15:51 은 아직 장중이다 — 스냅샷을 쌓는다")
    void lateCloseDayKeepsCollectingAfterUsualClose() {
        collectAt(CSAT, LocalTime.of(15, 51));

        verify(stockPriceService).getStockPrices(anyList());
    }

    @Test
    @DisplayName("평소 거래일은 종전과 같다 — 09:30 은 쌓고 15:51 은 쌓지 않는다")
    void ordinaryDayUnchanged() {
        collectAt(DAY_BEFORE, LocalTime.of(15, 51));
        verify(stockPriceService, never()).getStockPrices(anyList());

        collectAt(DAY_BEFORE, LocalTime.of(9, 30));
        verify(stockPriceService).getStockPrices(anyList());
    }
}
