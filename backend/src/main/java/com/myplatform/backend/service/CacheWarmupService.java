package com.myplatform.backend.service;

import com.myplatform.backend.dto.SectorTradingDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 캐시 워밍업 서비스
 * - 서버 시작 시 주요 캐시 데이터 미리 로드
 * - 첫 사용자 요청의 긴 응답 시간 방지
 * - MarketCacheWarmerService 활성화 시 중복 워밍업 방지 (Redis L2가 처리)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheWarmupService {

    private final SectorTradingService sectorTradingService;

    @Autowired(required = false)
    private MarketCacheWarmerService marketCacheWarmerService;

    /**
     * 애플리케이션 시작 완료 후 캐시 워밍업 실행
     * - MarketCacheWarmerService가 활성화되어 있으면 위임 (중복 방지)
     * - 비동기로 실행하여 서버 시작을 블로킹하지 않음
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupOnStartup() {
        if (marketCacheWarmerService != null) {
            log.info("=== Redis L2 Cache Warmer 활성화됨 - 기존 워밍업 스킵 ===");
            return;
        }

        // 장외 시간에는 섹터 워밍업 스킵 (134개 종목 batch fetch 방지)
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        LocalTime now = LocalTime.now();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                || now.isBefore(LocalTime.of(8, 0)) || now.isAfter(LocalTime.of(20, 5))) {
            log.info("=== 장외 시간 - 캐시 워밍업 스킵 (스케줄러가 장중 자동 워밍) ===");
            return;
        }

        log.info("=== 캐시 워밍업 시작 (Caffeine L1 only) ===");
        long startTime = System.currentTimeMillis();

        try {
            warmupSectorTrading();
        } catch (Exception e) {
            log.error("캐시 워밍업 실패: {}", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== 캐시 워밍업 완료 - 소요: {}ms ===", elapsed);
    }

    /**
     * 장 시작 전 캐시 갱신 (평일 08:50)
     * - MarketCacheWarmerService가 활성화되어 있으면 스킵
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledWarmup() {
        if (marketCacheWarmerService != null) return;

        log.info("장 시작 전 캐시 워밍업 시작");
        warmupSectorTrading();
    }

    private void warmupSectorTrading() {
        try {
            log.info("섹터 거래대금 캐시 로드 중...");
            List<SectorTradingDto> result = sectorTradingService.getAllSectorTrading(TradingPeriod.TODAY);
            log.info("섹터 거래대금 캐시 로드 완료 - 섹터 수: {}", result.size());
        } catch (Exception e) {
            log.warn("섹터 거래대금 캐시 로드 실패: {}", e.getMessage());
        }
    }
}
