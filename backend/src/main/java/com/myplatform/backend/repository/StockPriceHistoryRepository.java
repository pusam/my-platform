package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockPriceHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 일정 기간 이상 데이터를 가진 종목 코드 목록 (TA 스크리너 universe)
     */
    @Query("SELECT h.stockCode FROM StockPriceHistory h " +
           "GROUP BY h.stockCode HAVING COUNT(h) >= :minDays")
    List<String> findStockCodesWithMinHistory(@Param("minDays") long minDays);

    /**
     * 여러 종목의 최근 일봉 일괄 조회 (스크리너/상관관계 벌크 로딩용)
     */
    @Query("SELECT h FROM StockPriceHistory h " +
           "WHERE h.stockCode IN :codes AND h.tradeDate >= :startDate " +
           "ORDER BY h.stockCode ASC, h.tradeDate DESC")
    List<StockPriceHistory> findByStockCodesSince(
            @Param("codes") List<String> codes,
            @Param("startDate") LocalDate startDate);

    /**
     * 특정 거래일 봉을 가진 종목 코드 목록 — 마감 후 일봉 갱신 배치의 대상(P2-19 ①).
     * <p>장중에 수집된 종목만 당일 행이 있으므로, 이 목록이 곧 "미확정 봉을 가진 종목"이다.
     * 전체 유니버스를 훑지 않아 KIS 호출 예산이 장중 활동량에 비례해 bound 된다.
     */
    @Query("SELECT DISTINCT h.stockCode FROM StockPriceHistory h WHERE h.tradeDate = :tradeDate")
    List<String> findStockCodesByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /**
     * stockName 이 비어있는(NULL/빈문자열) 종목 코드 목록
     */
    @Query("SELECT DISTINCT h.stockCode FROM StockPriceHistory h " +
           "WHERE h.stockName IS NULL OR h.stockName = ''")
    List<String> findStockCodesWithMissingName();

    /**
     * 특정 종목의 모든 history 행에 stockName 일괄 업데이트
     */
    @Modifying
    @Query("UPDATE StockPriceHistory h SET h.stockName = :name WHERE h.stockCode = :code")
    int updateStockNameByCode(@Param("code") String code, @Param("name") String name);
}
