package com.myplatform.backend.repository;

import com.myplatform.backend.entity.InvestorDailyTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface InvestorDailyTradeRepository extends JpaRepository<InvestorDailyTrade, Long> {

    /**
     * 특정 일자의 모든 거래 조회
     */
    List<InvestorDailyTrade> findByTradeDateOrderByInvestorTypeAscTradeTypeAscRankNumAsc(LocalDate tradeDate);

    /**
     * 특정 일자, 시장의 거래 조회
     */
    List<InvestorDailyTrade> findByMarketTypeAndTradeDateOrderByInvestorTypeAscTradeTypeAscRankNumAsc(
            String marketType, LocalDate tradeDate);

    /**
     * 특정 일자, 투자자 유형의 거래 조회
     */
    List<InvestorDailyTrade> findByInvestorTypeAndTradeDateOrderByTradeTypeAscRankNumAsc(
            String investorType, LocalDate tradeDate);

    /**
     * 특정 일자, 시장, 투자자 유형의 거래 조회
     */
    List<InvestorDailyTrade> findByMarketTypeAndInvestorTypeAndTradeDateOrderByTradeTypeAscRankNumAsc(
            String marketType, String investorType, LocalDate tradeDate);

    /**
     * 특정 일자, 시장, 투자자 유형, 거래 유형의 거래 조회
     */
    List<InvestorDailyTrade> findByMarketTypeAndInvestorTypeAndTradeTypeAndTradeDateOrderByRankNumAsc(
            String marketType, String investorType, String tradeType, LocalDate tradeDate);

    /**
     * 기간별 특정 투자자의 거래 조회
     */
    @Query("SELECT t FROM InvestorDailyTrade t WHERE t.investorType = :investorType " +
           "AND t.tradeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY t.tradeDate DESC, t.tradeType ASC, t.rankNum ASC")
    List<InvestorDailyTrade> findByInvestorTypeAndDateRange(
            @Param("investorType") String investorType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 기간별 특정 종목의 투자자별 거래 조회
     */
    @Query("SELECT t FROM InvestorDailyTrade t WHERE t.stockCode = :stockCode " +
           "AND t.tradeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY t.tradeDate DESC, t.investorType ASC")
    List<InvestorDailyTrade> findByStockCodeAndDateRange(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 특정 일자의 데이터 존재 여부 확인
     */
    boolean existsByMarketTypeAndInvestorTypeAndTradeDate(
            String marketType, String investorType, LocalDate tradeDate);

    /**
     * 특정 일자의 데이터 삭제 (재수집용)
     */
    void deleteByMarketTypeAndInvestorTypeAndTradeDate(
            String marketType, String investorType, LocalDate tradeDate);

    /**
     * 최근 N일 데이터가 있는 날짜 목록 조회
     */
    @Query("SELECT DISTINCT t.tradeDate FROM InvestorDailyTrade t " +
           "WHERE t.marketType = :marketType AND t.investorType = :investorType " +
           "ORDER BY t.tradeDate DESC")
    List<LocalDate> findDistinctTradeDatesByMarketTypeAndInvestorType(
            @Param("marketType") String marketType,
            @Param("investorType") String investorType);

    /**
     * 특정 투자자의 특정 종목 누적 매수/매도 조회 (기간 내)
     */
    @Query("SELECT t.stockCode, t.stockName, t.tradeType, SUM(t.netBuyAmount) as totalAmount, COUNT(t) as tradeDays " +
           "FROM InvestorDailyTrade t " +
           "WHERE t.investorType = :investorType AND t.marketType = :marketType " +
           "AND t.tradeDate BETWEEN :startDate AND :endDate " +
           "GROUP BY t.stockCode, t.stockName, t.tradeType " +
           "ORDER BY totalAmount DESC")
    List<Object[]> findAccumulatedTradesByInvestor(
            @Param("investorType") String investorType,
            @Param("marketType") String marketType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 특정 투자자의 매수 상위 종목 조회 (연속 매수 분석용)
     */
    @Query("SELECT t FROM InvestorDailyTrade t " +
           "WHERE t.investorType = :investorType AND t.tradeType = 'BUY' " +
           "AND t.tradeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY t.tradeDate DESC, t.rankNum ASC")
    List<InvestorDailyTrade> findBuyTradesForConsecutiveAnalysis(
            @Param("investorType") String investorType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 거래일 목록 조회 (최근 N일)
     */
    @Query("SELECT DISTINCT t.tradeDate FROM InvestorDailyTrade t " +
           "WHERE t.investorType = :investorType AND t.tradeType = 'BUY' " +
           "ORDER BY t.tradeDate DESC")
    List<LocalDate> findDistinctTradeDates(@Param("investorType") String investorType);

    /**
     * 가장 최근 거래일 조회
     */
    @Query("SELECT MAX(t.tradeDate) FROM InvestorDailyTrade t")
    LocalDate findLatestTradeDate();
}
