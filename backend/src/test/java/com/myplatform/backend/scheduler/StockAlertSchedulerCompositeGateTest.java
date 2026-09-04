package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.CompositeAlertService;
import com.myplatform.backend.service.EarningSurpriseService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.MarketTimingService;
import com.myplatform.backend.service.MorningBriefingService;
import com.myplatform.backend.service.QuantScreenerService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.ShortSellingService;
import com.myplatform.backend.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 복합 조건 알림(10분 크론)은 마스터 플래그와 별개로 <b>자기 플래그(기본 false)</b> 뒤에 있다.
 *
 * <p>왜(2026-09-04 실측): 알림 스케줄러를 처음 켠 날 첫 tick(10:00)에 <b>36건</b>, 10:10 에 12건 — 16분 동안
 * 시그널 채널로 65통. 이 알림은 prod 에서 한 번도 돈 적이 없어 임계(기관 +8억·등락 +4% 등)가 보정된 적이
 * 없다. 60분 쿨다운은 같은 종목의 반복만 막지 tick 마다 새 종목 수십 건을 막지 못한다. 임계를 실측으로
 * 다시 잡기 전까지는 명시적으로 끈다 — 나머지 7개 크론(브리핑·장전/장마감·관심종목·어닝·공매도)은 마스터만 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockAlertSchedulerCompositeGateTest {

    @Mock private CompositeAlertService compositeAlertService;
    @Mock private EarningSurpriseService earningSurpriseService;
    @Mock private MarketTimingService marketTimingService;
    @Mock private MorningBriefingService morningBriefingService;
    @Mock private QuantScreenerService quantScreenerService;
    @Mock private ShortSellingService shortSellingService;
    @Mock private WatchlistService watchlistService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private MarketCalendarService marketCalendar;

    @InjectMocks private StockAlertScheduler scheduler;

    @BeforeEach
    void marketOpenAndLockFree() {
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", true);
        when(marketCalendar.isMarketClosed()).thenReturn(false);
        when(schedulerLockService.tryLock(anyString(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("마스터 ON + 복합 플래그 OFF(기본) → 복합 알림은 돌지 않는다")
    void compositeOffByDefaultEvenWhenMasterOn() {
        ReflectionTestUtils.setField(scheduler, "compositeAlertEnabled", false);

        scheduler.checkCompositeAlerts();

        verify(compositeAlertService, never()).checkCompositeSignals();
    }

    @Test
    @DisplayName("복합 플래그 ON → 돈다 (마스터 ON, 개장, 락 획득)")
    void compositeRunsWhenExplicitlyEnabled() {
        ReflectionTestUtils.setField(scheduler, "compositeAlertEnabled", true);

        scheduler.checkCompositeAlerts();

        verify(compositeAlertService, times(1)).checkCompositeSignals();
    }

    @Test
    @DisplayName("복합 플래그 ON 이어도 마스터 OFF 면 돌지 않는다 — 마스터가 상위")
    void masterOffWins() {
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", false);
        ReflectionTestUtils.setField(scheduler, "compositeAlertEnabled", true);

        scheduler.checkCompositeAlerts();

        verify(compositeAlertService, never()).checkCompositeSignals();
    }

    /**
     * 관심종목 크론은 :00 초를 피한다 — 잔고 모니터(급락 2분·공시 5분)와 워머가 :00 에 KIS 를 부르고, KIS 는 계좌당
     * 초당 건수를 자른다(EGW00215). 2026-09-04 이 크론이 처음 돈 날 10:30:00 에 시세 1건이 잔고 조회(CRITICAL)를
     * 밀어냈다. 초 필드를 0 으로 되돌리면 그 충돌이 tick 마다 돌아온다.
     */
    @Test
    @DisplayName("관심종목 5분 크론의 초 필드는 0 이 아니다 — :00 잔고 모니터와 같은 초 KIS 충돌 회피")
    void watchlistCronAvoidsTopOfMinute() throws Exception {
        org.springframework.scheduling.annotation.Scheduled s = StockAlertScheduler.class
                .getMethod("checkWatchlistAlerts")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        org.assertj.core.api.Assertions.assertThat(s.cron()).as("초 필드").doesNotStartWith("0 ");
        org.assertj.core.api.Assertions.assertThat(s.cron()).as("5분 주기는 유지").contains("*/5 9-15");
    }

    @Test
    @DisplayName("다른 크론(관심종목 5분)은 복합 플래그와 무관 — 마스터만 본다")
    void watchlistIgnoresCompositeFlag() {
        ReflectionTestUtils.setField(scheduler, "compositeAlertEnabled", false);

        scheduler.checkWatchlistAlerts();

        verify(watchlistService, times(1)).checkWatchlistAlerts();
    }
}
