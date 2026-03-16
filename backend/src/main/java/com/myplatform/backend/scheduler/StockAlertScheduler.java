package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.CompositeAlertService;
import com.myplatform.backend.service.MarketTimingService;
import com.myplatform.backend.service.MorningBriefingService;
import com.myplatform.backend.service.QuantScreenerService;
import com.myplatform.backend.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주식 알림 스케줄러
 * - 시장 상태 알림
 * - 마법의 공식/턴어라운드 종목 알림
 * - 관심종목 목표가 알림 (장중 5분 간격)
 *
 * [활성화 방법]
 * application.yml에 다음 설정 추가:
 * alert:
 *   scheduler:
 *     enabled: true
 *
 * [실행 시간 (한국 시간 기준)]
 * - 장중 5분 간격 (09:00~15:30): 관심종목 목표가 알림
 * - 장 마감 후 (16:45): 시장 상태 알림
 * - 모닝 브리핑 (07:30): 전일 시장 요약 텔레그램 발송
 * - 아침 (08:30): 마법의 공식, 턴어라운드 (장 시작 전 체크)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockAlertScheduler {

    private final CompositeAlertService compositeAlertService;
    private final MarketTimingService marketTimingService;
    private final MorningBriefingService morningBriefingService;
    private final QuantScreenerService quantScreenerService;
    private final WatchlistService watchlistService;

    @Value("${alert.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    /**
     * 모닝 브리핑 (평일 07:30)
     * - 장 시작 전 전일 시장 요약 텔레그램 발송
     * - 시장 상태, 외국인/기관 연속매수, 관심종목, 마법의 공식 정보
     */
    @Scheduled(cron = "0 30 7 * * MON-FRI", zone = "Asia/Seoul")
    public void morningBriefing() {
        if (!schedulerEnabled) return;
        try {
            morningBriefingService.sendMorningBriefing();
        } catch (Exception e) {
            log.error("모닝 브리핑 발송 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 장 마감 후 알림 (평일 16:45)
     * - 16:00 투자자 데이터 수집, 16:30 ADR 수집이 완료된 후 실행
     * - 시장 상태 알림 (과열/공포 구간만)
     */
    @Scheduled(cron = "0 45 16 * * MON-FRI", zone = "Asia/Seoul")
    public void afterMarketCloseAlert() {
        if (!schedulerEnabled) {
            log.debug("스케줄러 비활성화 상태");
            return;
        }

        log.info("=== 장 마감 후 알림 시작 ===");

        try {
            // 1. 시장 데이터 수집 및 상태 알림
            marketTimingService.collectAndNotify();

            log.info("=== 장 마감 후 알림 완료 ===");

        } catch (Exception e) {
            log.error("장 마감 후 알림 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 아침 알림 (평일 08:30)
     * - 마법의 공식 Top 5 종목
     * - 턴어라운드 종목
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void morningAlert() {
        if (!schedulerEnabled) {
            log.debug("스케줄러 비활성화 상태");
            return;
        }

        log.info("=== 아침 알림 시작 (08:30) ===");

        try {
            // 1. 마법의 공식 Top 5 알림
            quantScreenerService.sendMagicFormulaAlerts(5);

            // 2. 턴어라운드 Top 2 알림
            quantScreenerService.sendTurnaroundAlerts(2);

            log.info("=== 아침 알림 완료 ===");

        } catch (Exception e) {
            log.error("아침 알림 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 관심종목 목표가 알림 (장중 5분 간격)
     * - 09:00~15:30 사이 5분마다 실행
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkWatchlistAlerts() {
        if (!schedulerEnabled) {
            log.debug("스케줄러 비활성화 상태");
            return;
        }

        try {
            watchlistService.checkWatchlistAlerts();
        } catch (Exception e) {
            log.error("관심종목 알림 체크 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 복합 조건 알림 (장중 10분 간격)
     * - 09:00~15:50 사이 10분마다 실행
     * - 여러 조건 동시 충족 종목 감지
     */
    @Scheduled(cron = "0 */10 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkCompositeAlerts() {
        if (!schedulerEnabled) return;
        try {
            compositeAlertService.checkCompositeSignals();
        } catch (Exception e) {
            log.error("복합 조건 알림 체크 실패: {}", e.getMessage(), e);
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
