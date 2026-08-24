package com.myplatform.backend.service;

import com.myplatform.backend.repository.BatchJobExecutionRepository;
import com.myplatform.backend.repository.InvestorIntradaySnapshotRepository;
import com.myplatform.backend.repository.MarketIndicatorSnapshotRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class BatchJobCleanupService {

    private static final Logger log = LoggerFactory.getLogger(BatchJobCleanupService.class);

    private final BatchJobExecutionRepository batchJobRepo;
    private final InvestorIntradaySnapshotRepository investorSnapshotRepo;
    private final MarketIndicatorSnapshotRepository marketIndicatorSnapshotRepo;
    private final RecommendationSnapshotRepository recommendationSnapshotRepo;

    public BatchJobCleanupService(BatchJobExecutionRepository batchJobRepo,
                                  InvestorIntradaySnapshotRepository investorSnapshotRepo,
                                  MarketIndicatorSnapshotRepository marketIndicatorSnapshotRepo,
                                  RecommendationSnapshotRepository recommendationSnapshotRepo) {
        this.batchJobRepo = batchJobRepo;
        this.investorSnapshotRepo = investorSnapshotRepo;
        this.marketIndicatorSnapshotRepo = marketIndicatorSnapshotRepo;
        this.recommendationSnapshotRepo = recommendationSnapshotRepo;
    }

    /**
     * 배치 실행 이력 retention — 04:30 KST 평일.
     *
     * 부하 분산(2026-08-24): 03:00 -> 04:30. 같은 호스트의 jewelry-leads 백업 컨테이너가
     * 03:00 정각에 pg_dump + 사진 수백MB tar + B2 업로드를 돌린다(디스크가 가장 바쁜 창).
     * 04:30 은 그 창과 jewelry 발굴 배치(03:20~) 를 모두 지나고, 정각(매시 StockPriceService
     * 캐시 정리·DART 새벽 모니터) 과도 겹치지 않는다. 매월 1일 04:00 jewelry CSV 임포트와는
     * 겹칠 수 있으나 이쪽은 가벼운 DELETE 라 무해.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 4 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void cleanOldExecutions() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = batchJobRepo.deleteByCreatedAtBefore(cutoff);
        log.info("배치 실행 이력 정리: {}건 삭제 (기준: 7일 이전)", deleted);
    }

    /**
     * 스냅샷 테이블 retention — 04:40 KST 평일(2026-08-24, 03:30 에서 이동 — 위 주석 참조).
     * 무한 누적 → 쿼리 성능 저하 방지.
     * 기간 정책:
     * - 투자자 분단위 스냅샷: 30일 (장중 분석은 당일/주간만 사용)
     * - 시장 지표 스냅샷: 90일 (월간 트렌드 비교)
     * - 추천 스냅샷: 30일 (전일/전주 delta 비교)
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 40 4 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void cleanOldSnapshots() {
        LocalDate today = LocalDate.now();
        try {
            investorSnapshotRepo.deleteBySnapshotDateBefore(today.minusDays(30));
            log.info("투자자 분봉 스냅샷 정리 완료 (기준: 30일 이전)");
        } catch (Exception e) {
            log.warn("투자자 스냅샷 정리 실패: {}", e.getMessage());
        }
        try {
            marketIndicatorSnapshotRepo.deleteBySnapshotDateBefore(today.minusDays(90));
            log.info("시장 지표 스냅샷 정리 완료 (기준: 90일 이전)");
        } catch (Exception e) {
            log.warn("시장 지표 스냅샷 정리 실패: {}", e.getMessage());
        }
        try {
            recommendationSnapshotRepo.deleteOlderThan(LocalDateTime.now().minusDays(30));
            log.info("추천 스냅샷 정리 완료 (기준: 30일 이전)");
        } catch (Exception e) {
            log.warn("추천 스냅샷 정리 실패: {}", e.getMessage());
        }
    }
}
