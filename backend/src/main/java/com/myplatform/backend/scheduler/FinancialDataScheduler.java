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
 * 재무 데이터 자동 수집 스케줄러 (배치)
 *
 * [스케줄]
 * - 매일 08:30 (장 시작 전) - 전일 종가 반영 데이터 수집
 * - 매일 15:40 (장 마감 직후) - 당일 종가 반영 데이터 수집
 * - 서버 시작 시 데이터 없으면 1회 자동 수집
 *
 * [설계 원칙]
 * - 조회와 수집 분리: 스크리너는 DB만 조회, 수집은 배치로 처리
 * - 사용자가 새로고침해도 API 호출 없이 DB 데이터만 반환
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinancialDataScheduler {

    private final StockFinancialDataService stockFinancialDataService;
    private final StockFinancialDataRepository stockFinancialDataRepository;

    /**
     * 매일 08:30 자동 수집 (장 시작 전)
     * - 전일 종가 및 재무 데이터 반영
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void collectMorning() {
        log.info("=== [배치] 08:30 재무 데이터 자동 수집 시작 ===");
        try {
            stockFinancialDataService.collectAllStocksFinancialData();
            log.info("=== [배치] 08:30 재무 데이터 수집 완료 ===");
        } catch (Exception e) {
            log.error("[배치] 아침 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 매일 15:40 자동 수집 (장 마감 직후)
     * - 당일 종가 및 최신 재무 데이터 반영
     */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectAfternoon() {
        log.info("=== [배치] 15:40 재무 데이터 자동 수집 시작 ===");
        try {
            stockFinancialDataService.collectAllStocksFinancialData();
            log.info("=== [배치] 15:40 재무 데이터 수집 완료 ===");
        } catch (Exception e) {
            log.error("[배치] 오후 수집 실패: {}", e.getMessage(), e);
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
