package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.EarningsDisclosureService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 실적공시 자동 수집 스케줄러
 *
 * [스케줄]
 * - 매일 08:00 (장 시작 전) - DART 실적공시 수집
 * - 매일 16:30 (장 마감 후) - DART 실적공시 수집 + 관심종목 알림
 * - 서버 시작 시 1회 자동 수집
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EarningsDisclosureScheduler {

    private final EarningsDisclosureService earningsService;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;

    /**
     * 매일 08:00 - 장 시작 전 실적공시 수집
     */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void collectMorning() {
        if (marketCalendar.isMarketClosed()) {
            log.info("[실적공시] 08:00 휴장일 — 수집 스킵");
            return;
        }
        if (!schedulerLockService.tryLock("earnings.morning", Duration.ofMinutes(30))) {
            log.debug("[실적공시] 08:00 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        log.info("=== [실적공시] 08:00 자동 수집 시작 ===");
        try {
            int collected = earningsService.collectEarningsDisclosures();
            log.info("=== [실적공시] 08:00 수집 완료: {}건 ===", collected);
        } catch (Exception e) {
            log.error("=== [실적공시] 08:00 수집 실패: {} ===", e.getMessage(), e);
        }
    }

    /**
     * 매일 16:30 - 장 마감 후 실적공시 수집 + 관심종목 알림
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    public void collectAfternoonAndNotify() {
        if (marketCalendar.isMarketClosed()) {
            log.info("[실적공시] 16:30 휴장일 — 수집/알림 스킵");
            return;
        }
        if (!schedulerLockService.tryLock("earnings.afternoon", Duration.ofMinutes(30))) {
            log.debug("[실적공시] 16:30 다른 인스턴스에서 진행 중 — 스킵 (텔레그램 중복 방지)");
            return;
        }
        log.info("=== [실적공시] 16:30 자동 수집 + 알림 시작 ===");
        try {
            int collected = earningsService.collectEarningsDisclosures();
            log.info("[실적공시] 16:30 수집 완료: {}건", collected);

            int notified = earningsService.checkAndNotifyWatchlist();
            log.info("=== [실적공시] 16:30 완료 - 수집: {}건, 알림: {}건 ===", collected, notified);
        } catch (Exception e) {
            log.error("=== [실적공시] 16:30 수집/알림 실패: {} ===", e.getMessage(), e);
        }
    }

    /**
     * 서버 시작 시 자동 수집 (데이터 없을 경우)
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onStartup() {
        try {
            Thread.sleep(30000); // 30초 후 실행 (다른 초기화 완료 대기)

            if (!earningsService.isAvailable()) {
                log.info("[실적공시] DART API 미설정 - 자동 수집 생략");
                return;
            }

            // 최근 데이터 있는지 확인
            var recent = earningsService.getRecentDisclosures(1);
            if (recent.isEmpty()) {
                log.info("[실적공시] 데이터 없음 - 초기 수집 시작");
                int collected = earningsService.collectEarningsDisclosures();
                log.info("[실적공시] 초기 수집 완료: {}건", collected);
            } else {
                log.info("[실적공시] 기존 데이터 {}건 존재 - 초기 수집 생략", recent.size());
            }
        } catch (Exception e) {
            log.error("[실적공시] 초기 수집 실패: {}", e.getMessage());
        }
    }
}
