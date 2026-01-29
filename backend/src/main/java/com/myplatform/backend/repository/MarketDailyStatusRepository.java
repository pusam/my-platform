package com.myplatform.backend.repository;

import com.myplatform.backend.entity.MarketDailyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketDailyStatusRepository extends JpaRepository<MarketDailyStatus, Long> {

    /**
     * 특정 시장의 최신 데이터 조회
     */
    Optional<MarketDailyStatus> findTopByMarketTypeOrderByTradeDateDesc(String marketType);

    /**
     * 특정 날짜, 특정 시장의 데이터 조회
     */
    Optional<MarketDailyStatus> findByMarketTypeAndTradeDate(String marketType, LocalDate tradeDate);

    /**
     * 특정 시장의 최근 N일 데이터 조회 (ADR 계산용)
     */
    @Query("SELECT m FROM MarketDailyStatus m WHERE m.marketType = :marketType " +
           "ORDER BY m.tradeDate DESC LIMIT :days")
    List<MarketDailyStatus> findRecentByMarketType(@Param("marketType") String marketType,
                                                    @Param("days") int days);

    /**
     * 날짜 범위로 조회
     */
    List<MarketDailyStatus> findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(
            String marketType, LocalDate startDate, LocalDate endDate);

    /**
     * 특정 날짜의 모든 시장 데이터 조회
     */
    List<MarketDailyStatus> findByTradeDate(LocalDate tradeDate);

    /**
     * 최신 날짜의 모든 시장 데이터 조회
     */
    @Query("SELECT m FROM MarketDailyStatus m WHERE m.tradeDate = " +
           "(SELECT MAX(m2.tradeDate) FROM MarketDailyStatus m2)")
    List<MarketDailyStatus> findLatestAll();

    /**
     * 특정 시장의 ADR 계산을 위한 20일 합계
     */
    @Query("SELECT SUM(m.advancingCount) as advSum, SUM(m.decliningCount) as decSum " +
           "FROM MarketDailyStatus m WHERE m.marketType = :marketType " +
           "AND m.tradeDate >= :startDate ORDER BY m.tradeDate DESC")
    Object[] calculateAdrSums(@Param("marketType") String marketType,
                               @Param("startDate") LocalDate startDate);
}
