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

    /** 오래된 스냅샷 정리 (7일 이전) */
    @Modifying
    @Query("DELETE FROM RecommendationSnapshot r WHERE r.snapshotAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
