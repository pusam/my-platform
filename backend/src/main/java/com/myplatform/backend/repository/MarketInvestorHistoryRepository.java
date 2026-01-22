package com.myplatform.backend.repository;

import com.myplatform.backend.entity.MarketInvestorHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketInvestorHistoryRepository extends JpaRepository<MarketInvestorHistory, Long> {

    /**
     * 특정 시장의 특정 날짜 데이터 조회
     */
    Optional<MarketInvestorHistory> findByMarketTypeAndTradeDate(String marketType, LocalDate tradeDate);

    /**
     * 특정 시장의 최근 N일 데이터 조회
     */
    List<MarketInvestorHistory> findByMarketTypeOrderByTradeDateDesc(String marketType);

    /**
     * 특정 시장의 기간별 데이터 조회
     */
    @Query("SELECT h FROM MarketInvestorHistory h WHERE h.marketType = :marketType " +
           "AND h.tradeDate BETWEEN :startDate AND :endDate ORDER BY h.tradeDate DESC")
    List<MarketInvestorHistory> findByMarketTypeAndDateRange(
            @Param("marketType") String marketType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 특정 시장의 최근 N개 데이터
     */
    @Query("SELECT h FROM MarketInvestorHistory h WHERE h.marketType = :marketType " +
           "ORDER BY h.tradeDate DESC LIMIT :limit")
    List<MarketInvestorHistory> findRecentByMarketType(
            @Param("marketType") String marketType,
            @Param("limit") int limit);

    /**
     * 가장 최근 기록 날짜 조회
     */
    @Query("SELECT MAX(h.tradeDate) FROM MarketInvestorHistory h WHERE h.marketType = :marketType")
    Optional<LocalDate> findLatestTradeDateByMarketType(@Param("marketType") String marketType);
}
