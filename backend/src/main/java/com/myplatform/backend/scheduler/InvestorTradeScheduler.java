package com.myplatform.backend.scheduler;

import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.service.InvestorTradeService;
import com.myplatform.backend.service.KoreaInvestmentService;
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
    private final KoreaInvestmentService koreaInvestmentService;
    private final SchedulerLockService schedulerLockService;
    private final MarketCalendarService marketCalendar;

    /**
     * 매일 15:50 자동 수집 (장 마감 직후)
     * - 당일 외국인/기관 순매수/순매도 상위 종목 수집
     */
    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectAfterMarketClose() {
        if (marketCalendar.isMarketClosed()) { log.info("[배치] 15:50 휴장일 — 수집 스킵"); return; }
        if (!schedulerLockService.tryLock("investor-trade.after-close", Duration.ofMinutes(30))) {
            log.debug("[배치] 15:50 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
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
        if (marketCalendar.isMarketClosed()) { log.info("[배치] 18:00 휴장일 — 보완 수집 스킵"); return; }

        LocalDate today = LocalDate.now();

        // 오늘 데이터가 있는지 확인
        boolean hasData = investorDailyTradeRepository.existsByTradeDate(today);
        if (hasData) {
            log.debug("[배치] 18:00 - 오늘 데이터 이미 존재, 스킵");
            return;
        }

        if (!schedulerLockService.tryLock("investor-trade.evening", Duration.ofMinutes(30))) {
            log.debug("[배치] 18:00 다른 인스턴스에서 진행 중 — 스킵");
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
     * - KIS 토큰 사용 가능 여부 먼저 확인 (불필요한 API 호출 방지)
     * - 60초 지연으로 다른 초기화 작업과 리소스 경합 방지
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void collectOnStartup() {
        try {
            // 60초 대기 (서버 시작 직후 리소스 경합 방지 - AI Warm-up, 섹터 초기화와 분산)
            Thread.sleep(60000);

            LocalDate today = LocalDate.now();

            // 휴장일(주말 + 고정 공휴일) 스킵
            if (marketCalendar.isMarketClosed(today)) {
                log.info("[시작시 수집] 휴장일이므로 투자자 데이터 수집 스킵");
                return;
            }

            // KIS 토큰 사용 가능 여부 먼저 확인 (실패 시 불필요한 API 호출 방지)
            if (!koreaInvestmentService.isTokenAvailable()) {
                log.warn("[시작시 수집] KIS 토큰 사용 불가 - 투자자 데이터 수집 스킵 (15:50/18:00 스케줄러에서 재시도)");
                return;
            }

            // 오늘 데이터가 있는지 확인
            boolean hasData = investorDailyTradeRepository.existsByTradeDate(today);
            if (hasData) {
                long tradeDays = investorDailyTradeRepository.countDistinctTradeDates();
                log.info("[시작시 수집] 오늘 데이터 존재 - 현재 {}일치 데이터 보유", tradeDays);
                return;
            }

            // ApplicationReady 는 인스턴스마다 발생 — 멀티 인스턴스 부팅 시 KIS 중복 호출 방지
            if (!schedulerLockService.tryLock("investor-trade.startup", Duration.ofMinutes(30))) {
                log.info("[시작시 수집] 다른 인스턴스가 수집 중 — 스킵");
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
