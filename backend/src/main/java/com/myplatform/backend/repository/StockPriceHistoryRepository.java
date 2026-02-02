package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockPriceHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceHistoryRepository extends JpaRepository<StockPriceHistory, Long> {

    /**
     * 특정 종목의 최근 일봉 데이터 조회 (최신순)
     */
    List<StockPriceHistory> findByStockCodeOrderByTradeDateDesc(String stockCode, Pageable pageable);

    /**
     * 특정 종목의 일봉 데이터 개수
     */
    long countByStockCode(String stockCode);

    /**
     * 특정 종목의 가장 최근 데이터 조회
     */
    Optional<StockPriceHistory> findTopByStockCodeOrderByTradeDateDesc(String stockCode);

    /**
     * 특정 종목의 특정 날짜 데이터 조회
     */
    Optional<StockPriceHistory> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    /**
     * 특정 종목의 기간 내 데이터 조회
     */
    @Query("SELECT h FROM StockPriceHistory h WHERE h.stockCode = :stockCode " +
           "AND h.tradeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY h.tradeDate DESC")
    List<StockPriceHistory> findByStockCodeAndDateRange(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 종목 데이터 존재 여부
     */
    boolean existsByStockCode(String stockCode);

    /**
     * 특정 날짜의 데이터 존재 여부
     */
    boolean existsByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    /**
     * 특정 종목의 모든 데이터 삭제
     */
    void deleteByStockCode(String stockCode);
}
