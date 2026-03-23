package com.myplatform.backend.repository;

import com.myplatform.backend.entity.ShortSellingBalance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShortSellingBalanceRepository extends JpaRepository<ShortSellingBalance, Long> {

    List<ShortSellingBalance> findByStockCodeOrderByTradeDateDesc(String stockCode);

    Optional<ShortSellingBalance> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    /** 공매도 비율 상위 종목 (최근 날짜 기준) */
    @Query("SELECT s FROM ShortSellingBalance s WHERE s.tradeDate = :tradeDate ORDER BY s.shortSellingRatio DESC")
    List<ShortSellingBalance> findTopShortSellingByDate(@Param("tradeDate") LocalDate tradeDate, Pageable pageable);

    /** 최근 거래일 조회 */
    @Query("SELECT MAX(s.tradeDate) FROM ShortSellingBalance s")
    Optional<LocalDate> findLatestTradeDate();

    /** 공매도 비율 높은 종목 조회 */
    @Query("SELECT s FROM ShortSellingBalance s WHERE s.tradeDate = :tradeDate AND s.shortSellingRatio >= :minRatio ORDER BY s.shortSellingRatio DESC")
    List<ShortSellingBalance> findHighShortSellingStocks(@Param("tradeDate") LocalDate tradeDate, @Param("minRatio") BigDecimal minRatio);
}
