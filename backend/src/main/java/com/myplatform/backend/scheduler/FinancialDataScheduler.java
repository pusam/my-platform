package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.StockFinancialDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재무 데이터 자동 수집 스케줄러
 * - 매일 아침 8시 (장 시작 전)
 * - 매일 오후 4시 (장 마감 후)
 * - 서버 시작 시 데이터 없으면 자동 수집
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinancialDataScheduler {

    private final StockFinancialDataService stockFinancialDataService;
    private final StockFinancialDataRepository stockFinancialDataRepository;

    /**
     * 매일 아침 8시 자동 수집 (장 시작 전)
     */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void collectMorning() {
        log.info("=== [스케줄러] 아침 8시 재무 데이터 자동 수집 시작 ===");
        try {
            stockFinancialDataService.collectAllStocksFinancialData();
            log.info("=== [스케줄러] 아침 8시 재무 데이터 수집 완료 ===");
        } catch (Exception e) {
            log.error("[스케줄러] 아침 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 매일 오후 4시 자동 수집 (장 마감 후)
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void collectAfternoon() {
        log.info("=== [스케줄러] 오후 4시 재무 데이터 자동 수집 시작 ===");
        try {
            stockFinancialDataService.collectAllStocksFinancialData();
            log.info("=== [스케줄러] 오후 4시 재무 데이터 수집 완료 ===");
        } catch (Exception e) {
            log.error("[스케줄러] 오후 수집 실패: {}", e.getMessage(), e);
        }
    }

    // 최소 데이터 건수 (이보다 적으면 재수집)
    private static final long MIN_DATA_COUNT = 500;

    /**
     * 서버 시작 시 데이터가 없거나 부족하면 자동 수집
     * - @Async로 비동기 실행 (서버 시작 지연 방지)
     * - 데이터가 500건 미만이면 재수집
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        log.info("[스케줄러] 서버 시작 - 재무 데이터 확인 중...");

        try {
            long dataCount = stockFinancialDataRepository.count();

            if (dataCount < MIN_DATA_COUNT) {
                log.info("[스케줄러] 재무 데이터가 부족합니다 (현재: {}건, 최소: {}건). 전체 수집을 시작합니다...",
                        dataCount, MIN_DATA_COUNT);

                // 기존 데이터 삭제 후 재수집
                if (dataCount > 0) {
                    stockFinancialDataService.deleteAllFinancialData();
                    log.info("[스케줄러] 기존 데이터 {}건 삭제 완료", dataCount);
                }

                stockFinancialDataService.collectAllStocksFinancialData();
                log.info("[스케줄러] 초기 재무 데이터 수집 완료");
            } else {
                log.info("[스케줄러] 재무 데이터 {}건 존재. 수집 스킵.", dataCount);
            }
        } catch (Exception e) {
            log.error("[스케줄러] 초기 수집 실패: {}", e.getMessage(), e);
        }
    }
}
