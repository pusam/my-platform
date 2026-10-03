package com.myplatform.backend.repository;

import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 투자 전략 스냅샷 Repository
 *
 * <p>읽기 쿼리는 <b>은퇴한 대체 목록 행</b>(사유 '시총 상위 대형주…'·'시가총액 상위 대표주')을 읽지 않는다(2026-10-02).
 * 전략 스크리너가 비면 그 목록을 '추천'처럼 저장하던 코드는 지웠지만, 운영엔 SCALPING 146회분(30일 전부)이 남아 있어
 * 그대로 두면 '최신 스냅샷'·백테스트·수익률이 계속 그걸 읽는다(7일 보존이라 곧 사라지지만 그 사이 10/6 표본 첫날이 낀다).
 * 회귀 {@code AiStrategySnapshotRepositoryFallbackTest}.
 */
@Repository
public interface AiStrategySnapshotRepository extends JpaRepository<AiStrategySnapshot, Long> {

    /**
     * 특정 전략의 최신 스냅샷 N개 조회 (순위순)
     * - 가장 최신 createdAt 기준으로 해당 전략의 상위 N개 종목 반환
     */
    @Query("""
        SELECT s FROM AiStrategySnapshot s
        WHERE s.strategyType = :strategyType
          AND (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
          AND s.createdAt = (
              SELECT MAX(s2.createdAt) FROM AiStrategySnapshot s2
              WHERE s2.strategyType = :strategyType
                AND (s2.reason IS NULL OR (s2.reason NOT LIKE '시총 상위 대형주%' AND s2.reason <> '시가총액 상위 대표주'))
          )
        ORDER BY s.rankNum ASC
        """)
    List<AiStrategySnapshot> findLatestByStrategyType(@Param("strategyType") StrategyType strategyType);

    /**
     * 특정 전략의 최신 스냅샷 시각 조회
     */
    @Query("""
        SELECT MAX(s.createdAt) FROM AiStrategySnapshot s
        WHERE s.strategyType = :strategyType
          AND (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
        """)
    Optional<LocalDateTime> findLatestCreatedAt(@Param("strategyType") StrategyType strategyType);

    /**
     * 특정 시각의 특정 전략 스냅샷 조회
     */
    List<AiStrategySnapshot> findByStrategyTypeAndCreatedAtOrderByRankNumAsc(
            StrategyType strategyType, LocalDateTime createdAt);

    /**
     * 특정 전략의 특정 종목 최신 스냅샷 조회
     */
    @Query("""
        SELECT s FROM AiStrategySnapshot s
        WHERE s.strategyType = :strategyType
          AND s.stockCode = :stockCode
          AND (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
        ORDER BY s.createdAt DESC
        LIMIT 1
        """)
    Optional<AiStrategySnapshot> findLatestByStrategyTypeAndStockCode(
            @Param("strategyType") StrategyType strategyType,
            @Param("stockCode") String stockCode);

    /**
     * 모든 전략의 최신 스냅샷 조회 (전략별 가장 최신 데이터)
     * - 각 전략별로 최신 createdAt의 스냅샷만 조회
     */
    @Query(value = """
        SELECT s.* FROM ai_strategy_snapshot s
        INNER JOIN (
            SELECT strategy_type, MAX(created_at) as max_created_at
            FROM ai_strategy_snapshot s
            WHERE (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
            GROUP BY strategy_type
        ) latest ON s.strategy_type = latest.strategy_type
                AND s.created_at = latest.max_created_at
        WHERE (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
        ORDER BY s.strategy_type, s.rank_num
        """, nativeQuery = true)
    List<AiStrategySnapshot> findAllLatestSnapshots();

    /**
     * 특정 시각 이전의 오래된 스냅샷 삭제
     * - 데이터 정리용 (7일 이상 된 데이터 삭제)
     */
    /**
     * 전략 구분 없이 가장 최근 스냅샷 시각 — 정리 잡의 '생성 파이프라인 생존' 판정용(2026-10-02).
     * 예전엔 SCALPING 만 봤는데, 대체 목록이 매 회차를 채워 주던 동안만 맞는 기준이었다 — 이제 후보가 없으면
     * 그 회차는 저장하지 않으므로 한 전략이 비는 날 정리가 멈추고 ERROR 가 쌓인다.
     */
    @Query("SELECT MAX(s.createdAt) FROM AiStrategySnapshot s")
    Optional<LocalDateTime> findLatestCreatedAtAnyStrategy();

    @Modifying
    @Query("DELETE FROM AiStrategySnapshot s WHERE s.createdAt < :cutoffTime")
    int deleteOldSnapshots(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 특정 전략의 스냅샷 존재 여부 확인
     */
    boolean existsByStrategyType(StrategyType strategyType);

    /**
     * 특정 전략의 특정 시각 이후 스냅샷 존재 여부 확인
     */
    boolean existsByStrategyTypeAndCreatedAtAfter(StrategyType strategyType, LocalDateTime afterTime);

    /**
     * 전략별 스냅샷 개수 조회
     */
    @Query("""
        SELECT s.strategyType, COUNT(s) FROM AiStrategySnapshot s
        GROUP BY s.strategyType
        """)
    List<Object[]> countByStrategyType();

    /**
     * 특정 전략의 최신 N개 스냅샷 시각 조회 (디버깅용)
     */
    @Query("""
        SELECT DISTINCT s.createdAt FROM AiStrategySnapshot s
        WHERE s.strategyType = :strategyType
        ORDER BY s.createdAt DESC
        LIMIT :limit
        """)
    List<LocalDateTime> findRecentSnapshotTimes(
            @Param("strategyType") StrategyType strategyType,
            @Param("limit") int limit);

    /**
     * 특정 전략의 특정 시각 이후 스냅샷 조회 (백테스트용 - 시간순)
     */
    @Query("""
        SELECT s FROM AiStrategySnapshot s
        WHERE s.strategyType = :strategyType
          AND s.createdAt > :afterTime
          AND (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
        ORDER BY s.createdAt ASC
        """)
    List<AiStrategySnapshot> findByStrategyTypeAndCreatedAtAfterOrderByCreatedAtAsc(
            @Param("strategyType") StrategyType strategyType, @Param("afterTime") LocalDateTime afterTime);

    /**
     * 특정 종목의 특정 시점 근처 스냅샷 조회 (기간별 수익률 계산용)
     * - targetDate에 가장 가까운 과거 스냅샷 1개 반환
     * - 모든 전략 타입에서 검색 (동일 종목이 여러 전략에 있을 수 있음)
     */
    @Query(value = """
        SELECT * FROM ai_strategy_snapshot s
        WHERE s.stock_code = :stockCode
          AND s.created_at <= :targetDate
          AND (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
        ORDER BY s.created_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<AiStrategySnapshot> findNearestSnapshotBefore(
            @Param("stockCode") String stockCode,
            @Param("targetDate") LocalDateTime targetDate);

    /**
     * 특정 종목의 특정 기간 내 스냅샷 조회 (수익률 계산용)
     * - startDate ~ endDate 사이의 가장 최신 스냅샷 반환
     */
    @Query(value = """
        SELECT * FROM ai_strategy_snapshot s
        WHERE s.stock_code = :stockCode
          AND s.created_at BETWEEN :startDate AND :endDate
          AND (s.reason IS NULL OR (s.reason NOT LIKE '시총 상위 대형주%' AND s.reason <> '시가총액 상위 대표주'))
        ORDER BY s.created_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<AiStrategySnapshot> findNearestSnapshotInRange(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}
