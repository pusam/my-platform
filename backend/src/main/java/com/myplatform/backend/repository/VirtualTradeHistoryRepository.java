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
}
