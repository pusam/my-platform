package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.service.InvestorTradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 투자자(외국인/기관) 일별 매매 데이터 자동 수집 스케줄러
 *
 * [스케줄]
 * - 매일 15:50 (장 마감 후) - 당일 투자자별 순매수/순매도 상위 종목 수집
 * - 매일 18:00 (저녁) - 혹시 누락된 데이터 재수집 (보완)
 * - 서버 시작 시 오늘 데이터 없으면 1회 자동 수집
 *
 * [중요]
 * - KIS API는 당일 데이터만 반환 (과거 데이터 수집 불가)
 * - 따라서 매일 자동 수집하여 데이터를 누적해야 함
 * - 연속 매수 종목 분석은 최소 3일 이상 데이터 필요
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvestorTradeScheduler {

    private final InvestorTradeService investorTradeService;
    private final InvestorDailyTradeRepository investorDailyTradeRepository;

    /**
     * 매일 15:50 자동 수집 (장 마감 직후)
     * - 당일 외국인/기관 순매수/순매도 상위 종목 수집
     */
    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectAfterMarketClose() {
        log.info("=== [배치] 15:50 투자자 매매 데이터 자동 수집 시작 ===");
        try {
            LocalDate today = LocalDate.now();
            Map<String, Integer> result = investorTradeService.collectInvestorTradeData(today);
            int total = result.values().stream().mapToInt(Integer::intValue).sum();
            log.info("=== [배치] 15:50 투자자 매매 데이터 수집 완료: 총 {}건 ===", total);
            logCollectionResult(result);
        } catch (Exception e) {
            log.error("[배치] 장 마감 후 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 매일 18:00 보완 수집 (누락 방지)
     * - 장 마감 수집이 실패했거나 데이터가 누락된 경우 재시도
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Seoul")
    public void collectEvening() {
        LocalDate today = LocalDate.now();

        // 오늘 데이터가 있는지 확인
        boolean hasData = investorDailyTradeRepository.existsByTradeDate(today);
        if (hasData) {
            log.debug("[배치] 18:00 - 오늘 데이터 이미 존재, 스킵");
            return;
        }

        log.info("=== [배치] 18:00 투자자 매매 데이터 보완 수집 시작 ===");
        try {
            Map<String, Integer> result = investorTradeService.collectInvestorTradeData(today);
            int total = result.values().stream().mapToInt(Integer::intValue).sum();
            log.info("=== [배치] 18:00 투자자 매매 데이터 보완 수집 완료: 총 {}건 ===", total);
        } catch (Exception e) {
            log.error("[배치] 저녁 보완 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 서버 시작 시 자동 수집 (오늘 데이터 없으면)
     * - 서버가 재시작되거나 처음 배포될 때 데이터 확보
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void collectOnStartup() {
        try {
            // 시작 시 잠시 대기 (다른 초기화 작업 완료 대기)
            Thread.sleep(10000);

            LocalDate today = LocalDate.now();

            // 주말이면 스킵
            int dayOfWeek = today.getDayOfWeek().getValue();
            if (dayOfWeek >= 6) {
                log.info("[시작시 수집] 주말이므로 투자자 데이터 수집 스킵");
                return;
            }

            // 오늘 데이터가 있는지 확인
            boolean hasData = investorDailyTradeRepository.existsByTradeDate(today);
            if (hasData) {
                // 데이터 일수 확인
                long tradeDays = investorDailyTradeRepository.countDistinctTradeDates();
                log.info("[시작시 수집] 오늘 데이터 존재 - 현재 {}일치 데이터 보유", tradeDays);
                return;
            }

            log.info("=== [시작시 수집] 오늘 투자자 매매 데이터 없음, 자동 수집 시작 ===");
            Map<String, Integer> result = investorTradeService.collectInvestorTradeData(today);
            int total = result.values().stream().mapToInt(Integer::intValue).sum();
            log.info("=== [시작시 수집] 투자자 매매 데이터 수집 완료: 총 {}건 ===", total);
            logCollectionResult(result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[시작시 수집] 중단됨");
        } catch (Exception e) {
            log.error("[시작시 수집] 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 수집 결과 상세 로그
     */
    private void logCollectionResult(Map<String, Integer> result) {
        result.forEach((key, value) -> {
            if (value > 0) {
                log.info("  - {}: {}건", key, value);
            }
        });
    }
}
