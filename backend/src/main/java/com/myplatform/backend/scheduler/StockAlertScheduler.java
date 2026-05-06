package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.CompositeAlertService;
import com.myplatform.backend.service.EarningSurpriseService;
import com.myplatform.backend.service.MarketTimingService;
import com.myplatform.backend.service.MorningBriefingService;
import com.myplatform.backend.service.QuantScreenerService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.ShortSellingService;
import com.myplatform.backend.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    private final EarningSurpriseService earningSurpriseService;
    private final MarketTimingService marketTimingService;
    private final MorningBriefingService morningBriefingService;
    private final QuantScreenerService quantScreenerService;
    private final ShortSellingService shortSellingService;
    private final WatchlistService watchlistService;
    private final SchedulerLockService schedulerLockService;

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
        if (!schedulerLockService.tryLock("alert.morning-briefing", Duration.ofMinutes(15))) {
            log.debug("모닝 브리핑 다른 인스턴스에서 발송 중 — 스킵");
            return;
        }
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
        if (!schedulerLockService.tryLock("alert.after-close", Duration.ofMinutes(15))) {
            log.debug("장 마감 후 알림 다른 인스턴스에서 진행 중 — 스킵");
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
        if (!schedulerLockService.tryLock("alert.morning", Duration.ofMinutes(15))) {
            log.debug("아침 알림 다른 인스턴스에서 진행 중 — 스킵");
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
        // 5분 cron — TTL 4분 이면 다음 cron 까지 락 풀림
        if (!schedulerLockService.tryLock("alert.watchlist", Duration.ofMinutes(4))) {
            log.debug("관심종목 알림 다른 인스턴스에서 진행 중 — 스킵");
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
        if (!schedulerLockService.tryLock("alert.composite", Duration.ofMinutes(8))) {
            log.debug("복합 조건 알림 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        try {
            compositeAlertService.checkCompositeSignals();
        } catch (Exception e) {
            log.error("복합 조건 알림 체크 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 어닝 서프라이즈 주간 알림 (매주 월요일 08:00)
     * - 분기 실적 비교 → 영업이익 20%+ 변동 종목 감지
     * - 적자→흑자 전환 종목 포함
     */
    @Scheduled(cron = "0 0 8 * * MON", zone = "Asia/Seoul")
    public void checkEarningSurprises() {
        if (!schedulerEnabled) return;
        if (!schedulerLockService.tryLock("alert.earning-surprise", Duration.ofMinutes(30))) {
            log.debug("어닝 서프라이즈 알림 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        try {
            log.info("=== 어닝 서프라이즈 주간 알림 시작 ===");
            earningSurpriseService.sendEarningSurpriseAlert();
            log.info("=== 어닝 서프라이즈 주간 알림 완료 ===");
        } catch (Exception e) {
            log.error("어닝 서프라이즈 알림 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 공매도 잔고 수집 (평일 18:30, 장 마감 후)
     * - 네이버 금융에서 공매도 잔고 데이터 크롤링
     */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Asia/Seoul")
    public void collectShortSellingBalance() {
        if (!schedulerEnabled) return;
        if (!schedulerLockService.tryLock("alert.short-collect", Duration.ofMinutes(20))) {
            log.debug("공매도 잔고 수집 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        try {
            log.info("=== 공매도 잔고 수집 시작 (18:30) ===");
            shortSellingService.collectShortSellingData();
            log.info("=== 공매도 잔고 수집 완료 ===");
        } catch (Exception e) {
            log.error("공매도 잔고 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 공매도 경보 발송 (평일 19:00)
     * - 공매도 비율 5% 이상 종목 텔레그램 알림
     */
    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Seoul")
    public void checkShortSellingAlert() {
        if (!schedulerEnabled) return;
        if (!schedulerLockService.tryLock("alert.short-notify", Duration.ofMinutes(15))) {
            log.debug("공매도 경보 발송 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        try {
            log.info("=== 공매도 경보 발송 시작 (19:00) ===");
            shortSellingService.sendHighShortSellingAlert();
            log.info("=== 공매도 경보 발송 완료 ===");
        } catch (Exception e) {
            log.error("공매도 경보 발송 실패: {}", e.getMessage(), e);
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
