package com.myplatform.backend.repository;

import com.myplatform.backend.entity.TechnicalSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TechnicalSignalRepository extends JpaRepository<TechnicalSignal, Long> {

    List<TechnicalSignal> findByStockCodeOrderByCreatedAtDesc(String stockCode);

    List<TechnicalSignal> findBySignalTypeOrderByCreatedAtDesc(TechnicalSignal.SignalType signalType);

    List<TechnicalSignal> findBySignalDirectionOrderByCreatedAtDesc(TechnicalSignal.SignalDirection direction);

    @Query("SELECT t FROM TechnicalSignal t WHERE t.isActive = true AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<TechnicalSignal> findActiveSignalsSince(@Param("since") LocalDateTime since);

    @Query("SELECT t FROM TechnicalSignal t WHERE " +
           "t.signalDirection = :direction " +
           "AND t.signalStrength >= :minStrength " +
           "AND t.createdAt >= :since " +
           "ORDER BY t.signalStrength DESC, t.createdAt DESC")
    List<TechnicalSignal> findStrongSignals(
        @Param("direction") TechnicalSignal.SignalDirection direction,
        @Param("minStrength") java.math.BigDecimal minStrength,
        @Param("since") LocalDateTime since
    );

    // 최근 24시간 매수 신호
    @Query("SELECT t FROM TechnicalSignal t WHERE " +
           "t.signalDirection = 'BUY' " +
           "AND t.createdAt >= :since " +
           "ORDER BY t.signalStrength DESC, t.createdAt DESC")
    List<TechnicalSignal> findRecentBuySignals(@Param("since") LocalDateTime since);

    // 종목별 최신 신호
    @Query("SELECT t FROM TechnicalSignal t WHERE " +
           "t.stockCode = :stockCode " +
           "AND t.signalType = :signalType " +
           "AND t.createdAt >= :since " +
           "ORDER BY t.createdAt DESC")
    List<TechnicalSignal> findRecentSignalsByStockAndType(
        @Param("stockCode") String stockCode,
        @Param("signalType") TechnicalSignal.SignalType signalType,
        @Param("since") LocalDateTime since
    );

    // 통계 - 신호 타입별 카운트
    @Query("SELECT t.signalType, COUNT(t) FROM TechnicalSignal t " +
           "WHERE t.createdAt >= :since " +
           "GROUP BY t.signalType")
    List<Object[]> countSignalsByType(@Param("since") LocalDateTime since);
}

