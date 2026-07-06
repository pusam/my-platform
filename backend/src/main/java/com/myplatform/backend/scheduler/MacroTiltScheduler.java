package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.MacroTiltService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 매크로 tilt 일일 스냅샷 크론 (P3-7).
 *
 * <p>08:15 KST — 모닝브리핑(07:30)과 아침 알림(08:30) 사이. StockAlertScheduler(알림 도메인)와
 * 분리된 전용 클래스(리포 관례: 도메인별 스케줄러). 스냅샷은 측정 전용(산식 무관)이라
 * 기본 ON — 표시 API 와 <b>같은 compute 경로</b>의 결과를 영속(사후검증 데이터).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MacroTiltScheduler {

    private final MacroTiltService macroTiltService;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;

    /** 측정 전용 스냅샷이라 기본 ON (1일 1행 — 무해). */
    @Value("${macro.tilt.snapshot-enabled:true}")
    private boolean snapshotEnabled;

    @Scheduled(scheduler = "batchScheduler", cron = "0 15 8 * * MON-FRI", zone = "Asia/Seoul")
    public void dailySnapshot() {
        if (!snapshotEnabled) return;
        if (marketCalendar.isMarketClosed()) { log.debug("[매크로tilt] 휴장일 — 스냅샷 스킵"); return; }
        if (!schedulerLockService.tryLock("macro.tilt-snapshot", Duration.ofMinutes(10))) {
            log.debug("[매크로tilt] 다른 인스턴스에서 스냅샷 중 — 스킵");
            return;
        }
        try {
            macroTiltService.snapshotToday();
        } catch (Exception e) {
            log.error("[매크로tilt] 일일 스냅샷 실패: {}", e.getMessage(), e);
        }
    }
}
