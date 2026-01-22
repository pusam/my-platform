package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.InvestorDailyTradeService;
import com.myplatform.backend.service.MarketInvestorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 시장 투자자 매매동향 스케줄러
 * - 매일 장 종료 후 데이터 저장
 * - 투자자별 상위 종목 데이터 수집
 */
@Component
public class MarketInvestorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketInvestorScheduler.class);

    private final MarketInvestorService marketInvestorService;
    private final InvestorDailyTradeService investorDailyTradeService;

    public MarketInvestorScheduler(MarketInvestorService marketInvestorService,
                                    InvestorDailyTradeService investorDailyTradeService) {
        this.marketInvestorService = marketInvestorService;
        this.investorDailyTradeService = investorDailyTradeService;
    }

    /**
     * 평일 장 종료 후 (15:40) 투자자별 매매동향 저장
     * - 한국 시간 기준
     */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void saveMarketInvestorData() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // 주말 체크 (cron에서 처리하지만 이중 체크)
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            log.info("주말 - 스킵");
            return;
        }

        log.info("===== 장 종료 투자자 매매동향 저장 시작 =====");
        log.info("실행 시간: {}", LocalDateTime.now());

        try {
            marketInvestorService.saveDailyRecords();
            log.info("===== 투자자 매매동향 저장 완료 =====");
        } catch (Exception e) {
            log.error("투자자 매매동향 저장 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 장 종료 후 추가 저장 (16:00) - 데이터 보완
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void saveMarketInvestorDataSupplementary() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return;
        }

        log.info("===== 장 종료 투자자 매매동향 보완 저장 =====");

        try {
            // 캐시 초기화 후 다시 조회/저장
            marketInvestorService.clearCache();
            marketInvestorService.saveDailyRecords();
            log.info("===== 보완 저장 완료 =====");
        } catch (Exception e) {
            log.error("보완 저장 실패: {}", e.getMessage());
        }
    }

    /**
     * 평일 장 종료 후 (16:10) 투자자별 상위 종목 수집
     * - 외국인, 기관, 연기금 등 투자자별 매수/매도 상위 20종목
     */
    @Scheduled(cron = "0 10 16 * * MON-FRI", zone = "Asia/Seoul")
    public void collectInvestorDailyTrades() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            log.info("주말 - 투자자별 종목 수집 스킵");
            return;
        }

        log.info("===== 투자자별 상위 종목 수집 시작 =====");
        log.info("실행 시간: {}", LocalDateTime.now());

        try {
            Map<String, Integer> result = investorDailyTradeService.collectAllInvestorTrades(today);
            log.info("수집 결과: {}", result);
            log.info("===== 투자자별 상위 종목 수집 완료 =====");
        } catch (Exception e) {
            log.error("투자자별 상위 종목 수집 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 저녁 시간 (18:30) 추가 수집 - 데이터 확정 후 보완
     * - 장 종료 후 데이터가 확정되면 재수집
     */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Asia/Seoul")
    public void collectInvestorDailyTradesSupplementary() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return;
        }

        log.info("===== 투자자별 상위 종목 보완 수집 =====");

        try {
            Map<String, Integer> result = investorDailyTradeService.collectAllInvestorTrades(today);
            log.info("보완 수집 결과: {}", result);
        } catch (Exception e) {
            log.error("보완 수집 실패: {}", e.getMessage());
        }
    }
}
