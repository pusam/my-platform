package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.AsyncCrawlerService;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.StockFinancialDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 재무 데이터 자동 수집 스케줄러 (배치)
 *
 * [스케줄]
 * - 매일 08:30 (장 시작 전) - 원버튼 전체 데이터 수집 (4단계)
 * - 매일 15:40 (장 마감 직후) - 원버튼 전체 데이터 수집 (4단계)
 * - 서버 시작 시 데이터 없으면 1회 자동 수집
 *
 * [수집 단계]
 * 1️⃣ 기본 재무 데이터 (KIS API)
 * 2️⃣ 영업이익률 크롤링 (네이버 금융)
 * 3️⃣ 분기별 재무제표 (네이버 금융)
 * 4️⃣ 성장률 계산 (PEG 스크리너용)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinancialDataScheduler {

    private final StockFinancialDataService stockFinancialDataService;
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final AsyncCrawlerService asyncCrawlerService;
    private final com.myplatform.backend.service.BatchJobMonitorService batchMonitor;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;

    /**
     * 매일 08:30 자동 수집 (장 시작 전)
     * - 원버튼 전체 데이터 수집 (4단계)
     */
    /**
     * 매일 08:30 자동 수집 (장 시작 전)
     * ★ 비동기 실행 — 스케줄러 스레드 즉시 반환 (16분+ 동기 실행으로 스케줄러 죽는 문제 수정)
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void collectMorning() {
        if (marketCalendar.isMarketClosed()) {
            log.info("[배치] 08:30 휴장일 — 재무데이터 수집 스킵");
            return;
        }
        // 비동기 작업 trigger 라 lock TTL 짧게 잡고 빠르게 빠짐 — 다음 cron 까지 충분
        if (!schedulerLockService.tryLock("financial-data.morning", Duration.ofMinutes(5))) {
            log.debug("[배치] 08:30 다른 인스턴스에서 비동기 수집 트리거 — 스킵");
            return;
        }
        log.info("=== [배치] 08:30 원버튼 전체 데이터 비동기 수집 시작 ===");
        try {
            asyncCrawlerService.collectAllInOneAsync();
        } catch (Exception e) {
            log.error("[배치] 아침 수집 시작 실패: {}", e.getMessage(), e);
            batchMonitor.alertFailure("재무데이터_아침수집", e.getMessage());
        }
    }

    /**
     * 매일 15:38 자동 수집 (장 마감 직후)
     * ★ 비동기 실행
     */
    @Scheduled(cron = "0 38 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectAfternoon() {
        if (marketCalendar.isMarketClosed()) {
            log.info("[배치] 15:38 휴장일 — 재무데이터 수집 스킵");
            return;
        }
        if (!schedulerLockService.tryLock("financial-data.afternoon", Duration.ofMinutes(5))) {
            log.debug("[배치] 15:38 다른 인스턴스에서 비동기 수집 트리거 — 스킵");
            return;
        }
        log.info("=== [배치] 15:38 원버튼 전체 데이터 비동기 수집 시작 ===");
        try {
            asyncCrawlerService.collectAllInOneAsync();
        } catch (Exception e) {
            log.error("[배치] 오후 수집 시작 실패: {}", e.getMessage(), e);
            batchMonitor.alertFailure("재무데이터_오후수집", e.getMessage());
        }
    }

    // 최소 데이터 건수 (이보다 적으면 재수집)
    private static final long MIN_DATA_COUNT = 500;

    /**
     * 서버 시작 시 데이터가 없으면 1회 자동 수집
     * - @Async로 비동기 실행 (서버 시작 지연 방지)
     * - 데이터가 0건이면 초기 수집 실행
     * - 500건 미만이면 경고 로그만 출력 (수동 수집 권장)
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        log.info("[배치] 서버 시작 - 재무 데이터 확인 중...");

        try {
            long dataCount = stockFinancialDataRepository.count();

            if (dataCount == 0) {
                log.info("[배치] 재무 데이터가 없습니다. 초기 수집을 시작합니다...");
                stockFinancialDataService.collectAllStocksFinancialData();
                log.info("[배치] 초기 재무 데이터 수집 완료");
            } else if (dataCount < MIN_DATA_COUNT) {
                log.warn("[배치] 재무 데이터가 부족합니다 (현재: {}건, 권장: {}건 이상). " +
                         "관리자 페이지에서 수동 수집을 권장합니다.", dataCount, MIN_DATA_COUNT);
            } else {
                log.info("[배치] 재무 데이터 {}건 존재. 스크리너 사용 가능.", dataCount);
            }
        } catch (Exception e) {
            log.error("[배치] 초기 확인 실패: {}", e.getMessage(), e);
        }
    }
}
