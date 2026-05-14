package com.myplatform.backend.repository;

import com.myplatform.backend.entity.RecommendationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RecommendationSnapshotRepository extends JpaRepository<RecommendationSnapshot, Long> {

    /** 최신 스냅샷 시점의 TOP 5 조회 */
    @Query(value = "SELECT r FROM RecommendationSnapshot r " +
            "WHERE r.snapshotAt = (SELECT MAX(r2.snapshotAt) FROM RecommendationSnapshot r2) " +
            "ORDER BY r.rankOrder ASC")
    List<RecommendationSnapshot> findLatestSnapshot();

    /** 특정 시점 이전의 가장 최근 스냅샷 (전일 대비 delta 계산용) */
    @Query(value = "SELECT r FROM RecommendationSnapshot r " +
            "WHERE r.snapshotAt = (SELECT MAX(r2.snapshotAt) FROM RecommendationSnapshot r2 WHERE r2.snapshotAt < :before) " +
            "ORDER BY r.rankOrder ASC")
    List<RecommendationSnapshot> findPreviousSnapshot(@Param("before") LocalDateTime before);

    /** 오래된 스냅샷 정리 (7일 이전) */
    @Modifying
    @Query("DELETE FROM RecommendationSnapshot r WHERE r.snapshotAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 종목코드로 가장 최근 스냅샷 1건 조회 — StockConclusionService 가 종목별 결론 산출 시 사용.
     * 종목이 어떤 스냅샷에도 포함된 적 없으면 empty.
     */
    @Query(value = "SELECT r FROM RecommendationSnapshot r " +
            "WHERE r.stockCode = :stockCode " +
            "ORDER BY r.snapshotAt DESC LIMIT 1")
    java.util.Optional<RecommendationSnapshot> findLatestByStockCode(@Param("stockCode") String stockCode);

    /**
     * phase 35: STRONG_BUY (총점 ≥ threshold) + 강한 가치 (valueStability ≥ valueThreshold) 동시 충족
     * 종목을 since 이후 스냅샷에서 모두 조회. 일자별 빈도 + 종목명 샘플 노출용.
     * <p>같은 종목이 여러 스냅샷에 나오면 모두 반환 — 호출하는 쪽에서 일자별 집계/중복 제거.
     */
    @Query("""
        SELECT r FROM RecommendationSnapshot r
         WHERE r.snapshotAt >= :since
           AND r.totalScore >= :scoreMin
           AND r.valueStability >= :valueMin
         ORDER BY r.snapshotAt DESC, r.rankOrder ASC
        """)
    List<RecommendationSnapshot> findStrongValueSince(
            @Param("since") LocalDateTime since,
            @Param("scoreMin") int scoreMin,
            @Param("valueMin") int valueMin);

    /** phase 35b 진단 — 마지막 스냅샷 시점. */
    @Query("SELECT MAX(r.snapshotAt) FROM RecommendationSnapshot r")
    java.util.Optional<LocalDateTime> findMaxSnapshotAt();

    /** phase 35b 진단 — 최근 24시간 스냅샷 건수 (cron 정상 작동 확인). */
    @Query("SELECT COUNT(r) FROM RecommendationSnapshot r WHERE r.snapshotAt >= :since")
    long countSince(@Param("since") LocalDateTime since);

    /** phase 35b 진단 — 최근 24시간 STRONG_BUY (≥75) 종목 수. */
    @Query("SELECT COUNT(r) FROM RecommendationSnapshot r WHERE r.snapshotAt >= :since AND r.totalScore >= 75")
    long countStrongBuySince(@Param("since") LocalDateTime since);

    /** phase 35c 진단 — 최근 24시간 BUY+ (≥55) 종목 수. */
    @Query("SELECT COUNT(r) FROM RecommendationSnapshot r WHERE r.snapshotAt >= :since AND r.totalScore >= 55")
    long countBuyPlusSince(@Param("since") LocalDateTime since);

    /** phase 35c 진단 — 최근 24시간 점수 분포 [min, max, avg]. */
    @Query("SELECT MIN(r.totalScore), MAX(r.totalScore), AVG(r.totalScore) " +
           "FROM RecommendationSnapshot r WHERE r.snapshotAt >= :since")
    Object[] scoreStatsSince(@Param("since") LocalDateTime since);
}
