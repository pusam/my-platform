package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.InvestorIntradaySnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장중 수급 스냅샷 '오래됨' WARN 은 같은 스냅샷이면 한 번만(2026-10-07).
 *
 * <p>재현: 기관 순매수는 KIS 가 10시 전엔 아직 주지 않아(빈 응답이 정상) 그 전까지 최신 스냅샷이 직전 거래일 것이다. 그동안 화면·
 * 워머가 조회할 때마다 같은 "스냅샷 데이터 오래됨" WARN 이 쌓였다(10/6 운영 하루 ~30줄). 경보를 끄지 않고 반복만 없앤다(§5 —
 * 상태가 바뀐 순간만). '지금'은 주입된 시계로 본다 — 예전엔 벽시계라 이 판정을 시험할 수 없었다.
 */
class InvestorSurgeStaleWarnTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private InvestorIntradaySnapshotRepository snapshots;
    private InvestorSurgeService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        snapshots = mock(InvestorIntradaySnapshotRepository.class);
        // 10/7(수) 09:20 — 거래일 장중, 기관 데이터 제공 전
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 7, 9, 20).atZone(KST).toInstant(), KST);
        service = new InvestorSurgeService(snapshots, mock(AlertHistoryRepository.class), mock(KoreaInvestmentService.class),
                mock(TelegramNotificationService.class), mock(StockPriceService.class), mock(RedisCacheService.class),
                mock(SchedulerLockService.class), mock(ObjectProvider.class), clock, new MarketCalendarService());

        when(snapshots.findLatestSnapshotTime(eq(LocalDate.of(2026, 10, 7)), eq("INSTITUTION"))).thenReturn(Optional.empty());
        when(snapshots.findLatestSnapshotTime(eq(LocalDate.of(2026, 10, 6)), eq("INSTITUTION")))
                .thenReturn(Optional.of(LocalTime.of(15, 32)));
        when(snapshots.findLatestSnapshots(any(), any(), any())).thenReturn(List.of());

        logger = (Logger) LoggerFactory.getLogger(InvestorSurgeService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private long staleWarnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("오래됨"))
                .count();
    }

    @Test
    @DisplayName("재현: 같은 오래된 스냅샷을 세 번 읽어도 '오래됨' WARN 은 한 번")
    void sameStaleSnapshotWarnsOnce() {
        service.getSurgeStocks("INSTITUTION", null);
        service.getSurgeStocks("INSTITUTION", null);
        service.getSurgeStocks("INSTITUTION", null);

        assertThat(staleWarnings()).isEqualTo(1);
    }
}
