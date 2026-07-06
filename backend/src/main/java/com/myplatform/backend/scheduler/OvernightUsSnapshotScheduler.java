package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.OvernightUsMarketService;
import com.myplatform.backend.service.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 간밤 미국장 tilt 일일 스냅샷 크론 (P3-5, V40 — MacroTiltScheduler/V39 패턴 복제).
 *
 * <p>08:10 KST — 미국장 마감(새벽) 이후·MacroTiltScheduler(08:15)와 분리된 시각.
 * 도메인별 스케줄러 관례에 따라 전용 클래스. 스냅샷은 측정 전용(산식 무관)이라 기본 ON —
 * 표시 API(/api/global-futures/overnight-us)와 <b>같은 compute 경로</b>의 결과를 영속(사후검증 데이터).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OvernightUsSnapshotScheduler {

    private final OvernightUsMarketService overnightUsMarketService;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;

    /** 측정 전용 스냅샷이라 기본 ON (1일 1행 — 무해). */
    @Value("${overnight.us.snapshot-enabled:true}")
    private boolean snapshotEnabled;

    @Scheduled(scheduler = "batchScheduler", cron = "0 10 8 * * MON-FRI", zone = "Asia/Seoul")
    public void dailySnapshot() {
        if (!snapshotEnabled) return;
        // 휴장일 스킵 — 캘리브레이션 기준(KOSPI 익일 시초가)이 없는 날의 행은 무의미.
        if (marketCalendar.isMarketClosed()) { log.debug("[간밤미국장] 휴장일 — 스냅샷 스킵"); return; }
        if (!schedulerLockService.tryLock("overnight.us-snapshot", Duration.ofMinutes(10))) {
            log.debug("[간밤미국장] 다른 인스턴스에서 스냅샷 중 — 스킵");
            return;
        }
        try {
            overnightUsMarketService.snapshotToday();
        } catch (Exception e) {
            log.error("[간밤미국장] 일일 스냅샷 실패: {}", e.getMessage(), e);
        }
    }
}
