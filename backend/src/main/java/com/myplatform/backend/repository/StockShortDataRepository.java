package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockShortData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockShortDataRepository extends JpaRepository<StockShortData, Long> {

    // ========== 기본 조회 ==========

    /**
     * 특정 종목의 최근 공매도/대차잔고 데이터 조회
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.stockCode = :stockCode " +
           "ORDER BY s.tradeDate DESC")
    List<StockShortData> findByStockCodeOrderByTradeDateDesc(
            @Param("stockCode") String stockCode,
            Pageable pageable);

    /**
     * 특정 종목의 기간별 데이터 조회
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.stockCode = :stockCode " +
           "AND s.tradeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY s.tradeDate DESC")
    List<StockShortData> findByStockCodeAndDateRange(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 특정 종목의 특정 날짜 데이터
     */
    Optional<StockShortData> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    /**
     * 가장 최근 거래일 조회
     */
    @Query("SELECT MAX(s.tradeDate) FROM StockShortData s")
    LocalDate findLatestTradeDate();

    // ========== 숏스퀴즈 분석용 쿼리 ==========

    /**
     * 최근 N일간 대차잔고가 높은 종목 조회 (숏스퀴즈 후보)
     * - 대차잔고 비율이 일정 수준 이상인 종목
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = :tradeDate " +
           "AND s.loanBalanceRatio >= :minLoanRatio " +
           "ORDER BY s.loanBalanceRatio DESC")
    List<StockShortData> findHighLoanBalanceStocks(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("minLoanRatio") BigDecimal minLoanRatio);

    /**
     * 최근 거래일의 모든 공매도 데이터 조회
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = :tradeDate " +
           "ORDER BY s.loanBalanceQuantity DESC")
    List<StockShortData> findByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /**
     * 숏커버링 분석용 - 최근 기간 데이터 Bulk 조회 (N+1 방지)
     * - 모든 종목의 최근 N일 데이터를 한 번에 조회
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate >= :startDate " +
           "ORDER BY s.stockCode ASC, s.tradeDate DESC")
    List<StockShortData> findAllRecentData(@Param("startDate") LocalDate startDate);

    /**
     * 대차잔고가 있는 종목들의 최근 데이터
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate >= :startDate " +
           "AND s.loanBalanceQuantity IS NOT NULL " +
           "AND s.loanBalanceQuantity > 0 " +
           "ORDER BY s.stockCode ASC, s.tradeDate DESC")
    List<StockShortData> findAllWithLoanBalance(@Param("startDate") LocalDate startDate);

    /**
     * 특정 종목의 평균 대차잔고 계산 (기간 내)
     */
    @Query("SELECT AVG(s.loanBalanceQuantity) FROM StockShortData s " +
           "WHERE s.stockCode = :stockCode " +
           "AND s.tradeDate BETWEEN :startDate AND :endDate")
    BigDecimal calculateAvgLoanBalance(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 대차잔고 상위 종목 (최근 거래일 기준)
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = (SELECT MAX(s2.tradeDate) FROM StockShortData s2) " +
           "AND s.loanBalanceQuantity > 0 " +
           "ORDER BY s.loanBalanceRatio DESC")
    List<StockShortData> findTopLoanBalanceStocks(Pageable pageable);

    /**
     * 공매도 비중 상위 종목
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = (SELECT MAX(s2.tradeDate) FROM StockShortData s2) " +
           "AND s.shortRatio > 0 " +
           "ORDER BY s.shortRatio DESC")
    List<StockShortData> findTopShortRatioStocks(Pageable pageable);

    // ========== 데이터 수집용 ==========

    /**
     * 특정 날짜 데이터 삭제 (재수집용)
     */
    void deleteByTradeDate(LocalDate tradeDate);

    /**
     * 종목별 데이터 존재 여부
     */
    boolean existsByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);
}
