package com.myplatform.backend.service;

import com.myplatform.backend.dto.SectorTradingDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 캐시 워밍업 서비스
 * - 서버 시작 시 주요 캐시 데이터 미리 로드
 * - 첫 사용자 요청의 긴 응답 시간 방지
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheWarmupService {

    private final SectorTradingService sectorTradingService;

    /**
     * 애플리케이션 시작 완료 후 캐시 워밍업 실행
     * - 비동기로 실행하여 서버 시작을 블로킹하지 않음
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupOnStartup() {
        log.info("=== 캐시 워밍업 시작 ===");
        long startTime = System.currentTimeMillis();

        try {
            // 섹터 거래대금 캐시 (TODAY만 우선 로드)
            warmupSectorTrading();
        } catch (Exception e) {
            log.error("캐시 워밍업 실패: {}", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== 캐시 워밍업 완료 - 소요: {}ms ===", elapsed);
    }

    /**
     * 장 시작 전 캐시 갱신 (평일 08:50)
     */
    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledWarmup() {
        log.info("장 시작 전 캐시 워밍업 시작");
        warmupSectorTrading();
    }

    /**
     * 섹터 거래대금 캐시 워밍업
     */
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
