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
}
