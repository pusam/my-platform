package com.myplatform.backend.repository;

import com.myplatform.backend.entity.VirtualTradeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 가상 거래 내역 Repository
 */
@Repository
public interface VirtualTradeHistoryRepository extends JpaRepository<VirtualTradeHistory, Long> {

    /**
     * 계좌별 거래 내역 조회 (최신순)
     */
    List<VirtualTradeHistory> findByAccountIdOrderByTradeDateDesc(Long accountId);

    /**
     * 계좌별 거래 내역 조회 (페이징)
     */
    Page<VirtualTradeHistory> findByAccountIdOrderByTradeDateDesc(Long accountId, Pageable pageable);

    /**
     * 계좌별 기간 내 거래 내역 조회
     */
    List<VirtualTradeHistory> findByAccountIdAndTradeDateBetween(
            Long accountId, LocalDateTime start, LocalDateTime end);

    /** 가상(모의) 매매 — REAL_ACCOUNT_ID(999999L) 제외한 모든 가상 계좌 매매 */
    @Query("""
        SELECT v FROM VirtualTradeHistory v
         WHERE v.accountId <> :realAccountId
           AND v.tradeDate >= :start
           AND v.tradeDate <  :end
         ORDER BY v.tradeDate
        """)
    List<VirtualTradeHistory> findVirtualBetween(@Param("realAccountId") Long realAccountId,
                                                  @Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * 계좌별 거래 유형별 조회 (페이징)
     */
    Page<VirtualTradeHistory> findByAccountIdAndTradeTypeOrderByTradeDateDesc(
            Long accountId, String tradeType, Pageable pageable);

    /**
     * 수익 거래 수 조회 (profitLoss > 0)
     */
    @Query("SELECT COUNT(t) FROM VirtualTradeHistory t WHERE t.accountId = :accountId AND t.profitLoss > 0")
    long countWinningTrades(@Param("accountId") Long accountId);

    /**
     * 손실 거래 수 조회 (profitLoss < 0)
     */
    @Query("SELECT COUNT(t) FROM VirtualTradeHistory t WHERE t.accountId = :accountId AND t.profitLoss < 0")
    long countLosingTrades(@Param("accountId") Long accountId);

    /**
     * 총 매도 거래 수 조회
     */
    @Query("SELECT COUNT(t) FROM VirtualTradeHistory t WHERE t.accountId = :accountId AND t.tradeType = 'SELL'")
    long countSellTrades(@Param("accountId") Long accountId);

    /**
     * 총 매수 거래 수 조회
     */
    @Query("SELECT COUNT(t) FROM VirtualTradeHistory t WHERE t.accountId = :accountId AND t.tradeType = 'BUY'")
    long countBuyTrades(@Param("accountId") Long accountId);

    /**
     * 총 실현손익 합계
     */
    @Query("SELECT COALESCE(SUM(t.profitLoss), 0) FROM VirtualTradeHistory t WHERE t.accountId = :accountId AND t.profitLoss IS NOT NULL")
    java.math.BigDecimal sumRealizedProfitLoss(@Param("accountId") Long accountId);

    /**
     * 오늘 거래 수 조회
     */
    @Query("SELECT COUNT(t) FROM VirtualTradeHistory t WHERE t.accountId = :accountId AND t.tradeDate >= :todayStart")
    long countTodayTrades(@Param("accountId") Long accountId, @Param("todayStart") LocalDateTime todayStart);

    /**
     * 계좌별 전체 거래 수 조회
     */
    long countByAccountId(Long accountId);

    /**
     * 거래 통계 통합 조회 (6개 쿼리 → 1개 쿼리로 최적화)
     * 반환값: [buyCount, sellCount, winCount, loseCount, realizedProfitLoss, todayTradeCount]
     */
    @Query("""
        SELECT
            COUNT(CASE WHEN t.tradeType = 'BUY' THEN 1 END),
            COUNT(CASE WHEN t.tradeType = 'SELL' THEN 1 END),
            COUNT(CASE WHEN t.profitLoss > 0 THEN 1 END),
            COUNT(CASE WHEN t.profitLoss < 0 THEN 1 END),
            COALESCE(SUM(t.profitLoss), 0),
            COUNT(CASE WHEN t.tradeDate >= :todayStart THEN 1 END)
        FROM VirtualTradeHistory t
        WHERE t.accountId = :accountId
        """)
    List<Object[]> getTradeStatistics(@Param("accountId") Long accountId, @Param("todayStart") LocalDateTime todayStart);

    /**
     * 봇 거래만 조회 (tradeReason으로 필터)
     */
    @Query("SELECT v FROM VirtualTradeHistory v WHERE v.accountId = :accountId AND v.tradeReason IN :reasons ORDER BY v.tradeDate DESC")
    List<VirtualTradeHistory> findBotTrades(@Param("accountId") Long accountId, @Param("reasons") List<String> reasons);

    /**
     * 봇 거래 - 페이징
     */
    @Query("SELECT v FROM VirtualTradeHistory v WHERE v.accountId = :accountId AND v.tradeReason IN :reasons ORDER BY v.tradeDate DESC")
    Page<VirtualTradeHistory> findBotTrades(@Param("accountId") Long accountId, @Param("reasons") List<String> reasons, Pageable pageable);

    /**
     * 봇 거래 - 기간별
     */
    @Query("SELECT v FROM VirtualTradeHistory v WHERE v.accountId = :accountId AND v.tradeReason IN :reasons AND v.tradeDate BETWEEN :start AND :end ORDER BY v.tradeDate DESC")
    List<VirtualTradeHistory> findBotTradesBetween(@Param("accountId") Long accountId, @Param("reasons") List<String> reasons, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 봇 일별 손익 집계
     */
    @Query("SELECT CAST(v.tradeDate AS date) as tradeDay, SUM(v.profitLoss) as dailyPnl, COUNT(v) as tradeCount FROM VirtualTradeHistory v WHERE v.accountId = :accountId AND v.tradeReason IN :reasons AND v.tradeType = 'SELL' GROUP BY CAST(v.tradeDate AS date) ORDER BY tradeDay DESC")
    List<Object[]> findDailyBotPnl(@Param("accountId") Long accountId, @Param("reasons") List<String> reasons);

    /**
     * 봇 종목별 손익 집계
     */
    @Query("SELECT v.stockCode, v.stockName, SUM(v.profitLoss) as totalPnl, COUNT(v) as tradeCount, SUM(CASE WHEN v.profitLoss > 0 THEN 1 ELSE 0 END) as winCount FROM VirtualTradeHistory v WHERE v.accountId = :accountId AND v.tradeReason IN :reasons AND v.tradeType = 'SELL' GROUP BY v.stockCode, v.stockName ORDER BY totalPnl DESC")
    List<Object[]> findBotPnlByStock(@Param("accountId") Long accountId, @Param("reasons") List<String> reasons);
}
