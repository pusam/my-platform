package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.MarketTimingService;
import com.myplatform.backend.service.QuantScreenerService;
import com.myplatform.backend.service.ShortSellingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주식 알림 스케줄러
 * - 시장 상태 알림
 * - 숏스퀴즈 후보 알림
 * - 마법의 공식/턴어라운드 종목 알림
 *
 * [활성화 방법]
 * application.yml에 다음 설정 추가:
 * alert:
 *   scheduler:
 *     enabled: true
 *
 * [실행 시간 (한국 시간 기준)]
 * - 장 마감 후 (16:00): 시장 상태, 숏스퀴즈
 * - 아침 (08:30): 마법의 공식, 턴어라운드 (장 시작 전 체크)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockAlertScheduler {

    private final MarketTimingService marketTimingService;
    private final ShortSellingService shortSellingService;
    private final QuantScreenerService quantScreenerService;

    @Value("${alert.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    /**
     * 장 마감 후 알림 (평일 16:00)
     * - 시장 상태 알림 (과열/공포 구간만)
     * - 숏스퀴즈 고점수 종목 알림
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void afterMarketCloseAlert() {
        if (!schedulerEnabled) {
            log.debug("스케줄러 비활성화 상태");
            return;
        }

        log.info("=== 장 마감 후 알림 시작 ===");

        try {
            // 1. 시장 데이터 수집 및 상태 알림
            marketTimingService.collectAndNotify();

            // 2. 숏스퀴즈 고점수 종목 알림 (70점 이상, 최대 3개)
            shortSellingService.sendHighScoreSqueezeAlerts(70, 3);

            log.info("=== 장 마감 후 알림 완료 ===");

        } catch (Exception e) {
            log.error("장 마감 후 알림 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 아침 알림 (평일 08:30)
     * - 마법의 공식 상위 종목
     * - 턴어라운드 종목
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void morningAlert() {
        if (!schedulerEnabled) {
            log.debug("스케줄러 비활성화 상태");
            return;
        }

        log.info("=== 아침 알림 시작 ===");

        try {
            // 1. 마법의 공식 Top 3 알림
            quantScreenerService.sendMagicFormulaAlerts(3);

            // 2. 턴어라운드 Top 2 알림
            quantScreenerService.sendTurnaroundAlerts(2);

            log.info("=== 아침 알림 완료 ===");

        } catch (Exception e) {
            log.error("아침 알림 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 수동 알림 트리거 (테스트/관리자용)
     */
    public void triggerAllAlerts() {
        log.info("수동 알림 트리거 시작");
        afterMarketCloseAlert();
        morningAlert();
        log.info("수동 알림 트리거 완료");
    }
}
