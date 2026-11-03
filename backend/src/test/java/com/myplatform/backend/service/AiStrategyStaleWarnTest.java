package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import com.myplatform.core.util.DateTimeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AI 전략 스냅샷 '오래됨' 경고는 로테이션이 멈췄을 때만 — 정상 운영에서 울리지 않는다(2026-11-04).
 *
 * <p>재현: 전략별 갱신은 세 시간마다이고 19시 뒤로는 다음 날까지 없는데 기준이 경과 120분이라, 멀쩡한 날 하루 12번
 * 경고가 났다(11/3 20:05 VALUE 125분·TURNAROUND 185분, 그날 18시·17시 스냅샷). 이제 마지막으로 마감된 거래일의
 * 스냅샷이 있는지로 본다 — CLAUDE.md §5 정상 로그는 반복하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiStrategyStaleWarnTest {

    @Mock private AiStrategySnapshotRepository snapshotRepository;
    @Mock private MarketCalendarService marketCalendarService;
    @Mock private QuantScreenerService quantScreenerService;
    @Mock private StockPriceService stockPriceService;
    @Mock private GeminiService geminiService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private EarningSurpriseService earningSurpriseService;
    @Mock private StockStatusService stockStatusService;

    @InjectMocks private AiStrategySnapshotService service;

    private final Logger logger = (Logger) LoggerFactory.getLogger(AiStrategySnapshotService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private void snapshotAgedMinutes(long minutes) {
        AiStrategySnapshot snapshot = new AiStrategySnapshot();
        snapshot.setStrategyType(AiStrategySnapshot.StrategyType.SWING);
        snapshot.setStockCode("005930");
        snapshot.setStockName("삼성전자");
        snapshot.setCreatedAt(DateTimeUtil.kstNow().minusMinutes(minutes));
        when(snapshotRepository.findLatestByStrategyType(any())).thenReturn(List.of(snapshot));
    }

    private long staleWarnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("stale이지만 반환"))
                .count();
    }

    @Test
    @DisplayName("재현: 세 시간 주기 안의 스냅샷은 경고하지 않는다 — 예전엔 120분이 넘으면 울렸다")
    void normalRotationGapIsQuiet() {
        snapshotAgedMinutes(185);
        when(marketCalendarService.isFreshWithin(any(), any(), eq(0))).thenReturn(true);

        service.getAllLatestSnapshots();

        assertThat(staleWarnings()).isZero();
    }

    @Test
    @DisplayName("마지막으로 마감된 거래일의 스냅샷이 없으면 경고한다 — 로테이션이 멈춘 것")
    void missingLastSessionSnapshotWarns() {
        snapshotAgedMinutes(3000);
        when(marketCalendarService.isFreshWithin(any(), any(), eq(0))).thenReturn(false);

        service.getAllLatestSnapshots();

        assertThat(staleWarnings()).isPositive();
    }
}
