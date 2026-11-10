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

import static org.assertj.core.api.Assertions.assertThat;
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
 * 17:00 스냅샷(시그널 기록)의 국면 입력(섹터 전 종목 평균 등락률)이 15:39 값으로 굳는다. 판정을 달력으로 바꾸고 크론 시간 필드를
 * 9-16 으로 넓혀(2026-11-10 사용자 결정) 그날 16:39 까지 쌓는다 — 평소 거래일 16시대 회차는 로그 없이 아무것도 하지 않는다.
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
    @DisplayName("재현: 크론이 16시대까지 돈다 — 수능일 16:00~16:39 도 수집 대상이다(2026-11-10 사용자 결정)")
    void cronCoversTheSixteenHour() throws Exception {
        org.springframework.scheduling.annotation.Scheduled scheduled = SectorTradingService.class
                .getMethod("scheduledSnapshotCollection")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        org.springframework.scheduling.support.CronExpression cron =
                org.springframework.scheduling.support.CronExpression.parse(scheduled.cron());
        LocalDateTime next = cron.next(LocalDateTime.of(CSAT, LocalTime.of(15, 58)));
        assertThat(next).isEqualTo(LocalDateTime.of(CSAT, LocalTime.of(16, 0)));

        collectAt(CSAT, LocalTime.of(16, 39));
        verify(stockPriceService).getStockPrices(anyList());
    }

    @Test
    @DisplayName("평소 거래일 16시대 회차는 아무것도 수집·저장하지 않고 로그도 남기지 않는다(정상 경로 로그 반복 금지)")
    void ordinaryDaySixteenHourIsSilentNoOp() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SectorTradingService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int minute = 0; minute < 60; minute += 3) {
                collectAt(DAY_BEFORE, LocalTime.of(16, minute));
            }
        } finally {
            logger.detachAppender(appender);
        }

        verify(stockPriceService, never()).getStockPrices(anyList());
        assertThat(appender.list).isEmpty();
        assertThat(service.getSectorRotation()).isEmpty();
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
