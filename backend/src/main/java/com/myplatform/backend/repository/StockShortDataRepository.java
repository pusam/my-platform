package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockShortData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 공매도/대차잔고 데이터 Repository
 *
 * 주요 기능:
 * 1. 기본 CRUD - 종목별, 날짜별 조회
 * 2. 숏스퀴즈 분석 - 대차잔고 과열/감소 종목 분석
 * 3. 데이터 수집 - 일별 데이터 저장/삭제
 *
 * 데이터 단위:
 * - loanBalanceQuantity: 주(株) 단위
 * - loanBalanceValue: 원(KRW) 단위
 * - loanBalanceRatio: % 단위 (상장주식수 대비)
 * - shortRatio: % 단위 (거래량 대비 공매도 비중)
 * - closePrice: 원(KRW) 단위
 */
@Repository
public interface StockShortDataRepository extends JpaRepository<StockShortData, Long> {

    // ========== 1. 기본 조회 ==========

    /**
     * 특정 종목의 최근 공매도/대차잔고 데이터 조회
     *
     * @param stockCode 종목코드 (6자리, 예: "005930")
     * @param pageable  조회 건수 제한 (예: PageRequest.of(0, 30))
     * @return 날짜 내림차순 정렬된 데이터
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
     *
     * @return 가장 최근 거래일 (데이터가 없으면 null)
     */
    @Query("SELECT MAX(s.tradeDate) FROM StockShortData s")
    LocalDate findLatestTradeDate();

    /**
     * 최근 거래일의 모든 공매도 데이터 조회
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = :tradeDate " +
           "ORDER BY s.loanBalanceQuantity DESC")
    List<StockShortData> findByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    // ========== 2. 숏스퀴즈 분석용 쿼리 ==========

    /**
     * 대차잔고가 있는 종목들의 최근 데이터 Bulk 조회
     *
     * [성능 최적화] N+1 문제 방지를 위한 Bulk 조회
     * - 모든 종목의 최근 N일 데이터를 한 번에 조회
     * - 메모리에서 종목별로 그룹화하여 분석
     *
     * @param startDate 조회 시작일 (주말/공휴일 고려하여 넉넉하게 설정 필요)
     *                  예: 20 거래일 분석 시 → today.minusDays(40) 권장
     * @return 종목코드 ASC, 거래일 DESC 정렬된 데이터
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate >= :startDate " +
           "AND s.loanBalanceQuantity IS NOT NULL " +
           "AND s.loanBalanceQuantity > 0 " +
           "ORDER BY s.stockCode ASC, s.tradeDate DESC")
    List<StockShortData> findAllWithLoanBalance(@Param("startDate") LocalDate startDate);

    /**
     * 모든 종목의 최근 데이터 Bulk 조회 (대차잔고 유무 무관)
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate >= :startDate " +
           "ORDER BY s.stockCode ASC, s.tradeDate DESC")
    List<StockShortData> findAllRecentData(@Param("startDate") LocalDate startDate);

    /**
     * 대차잔고 상위 종목 조회 (최근 거래일 기준)
     *
     * 공매도 세력이 많이 쌓인 종목 = 숏스퀴즈 잠재 후보
     *
     * @param pageable 조회 건수 (예: PageRequest.of(0, 50))
     * @return 대차잔고 비율(%) 내림차순 정렬
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = (SELECT MAX(s2.tradeDate) FROM StockShortData s2) " +
           "AND s.loanBalanceQuantity > 0 " +
           "ORDER BY s.loanBalanceRatio DESC")
    List<StockShortData> findTopLoanBalanceStocks(Pageable pageable);

    /**
     * 공매도 비중 상위 종목 조회 (최근 거래일 기준)
     *
     * 당일 공매도 거래가 활발한 종목
     *
     * @param pageable 조회 건수 (예: PageRequest.of(0, 50))
     * @return 공매도 비중(%) 내림차순 정렬
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = (SELECT MAX(s2.tradeDate) FROM StockShortData s2) " +
           "AND s.shortRatio > 0 " +
           "ORDER BY s.shortRatio DESC")
    List<StockShortData> findTopShortRatioStocks(Pageable pageable);

    /**
     * 대차잔고 비율이 높은 종목 조회 (특정 날짜 기준)
     *
     * @param tradeDate    기준일
     * @param minLoanRatio 최소 대차잔고 비율 (%, 예: 1.0)
     * @return 대차잔고 비율 내림차순 정렬
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = :tradeDate " +
           "AND s.loanBalanceRatio >= :minLoanRatio " +
           "ORDER BY s.loanBalanceRatio DESC")
    List<StockShortData> findHighLoanBalanceStocks(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("minLoanRatio") BigDecimal minLoanRatio);

    /**
     * 특정 종목의 평균 대차잔고 계산 (기간 내)
     *
     * @param stockCode 종목코드
     * @param startDate 시작일
     * @param endDate   종료일
     * @return 평균 대차잔고 (주 단위)
     */
    @Query("SELECT AVG(s.loanBalanceQuantity) FROM StockShortData s " +
           "WHERE s.stockCode = :stockCode " +
           "AND s.tradeDate BETWEEN :startDate AND :endDate")
    BigDecimal calculateAvgLoanBalance(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 공매도 잔고 비율 상위 종목 조회 (최근 거래일 기준)
     *
     * 누적 공매도 잔고가 많은 종목
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate = (SELECT MAX(s2.tradeDate) FROM StockShortData s2) " +
           "AND s.shortBalanceRatio > 0 " +
           "ORDER BY s.shortBalanceRatio DESC")
    List<StockShortData> findTopShortBalanceStocks(Pageable pageable);

    /**
     * 대차잔고 감소 종목 조회 (숏커버링 후보)
     *
     * 최근 데이터 vs 과거 데이터 비교하여
     * 대차잔고가 감소한 종목 = 숏커버링 진행 중
     *
     * @param recentDate  최근 날짜
     * @param pastDate    과거 날짜 (비교 기준)
     * @return 두 날짜 모두 데이터가 있는 종목들
     */
    @Query("SELECT s FROM StockShortData s " +
           "WHERE s.tradeDate IN (:recentDate, :pastDate) " +
           "AND s.loanBalanceQuantity > 0 " +
           "ORDER BY s.stockCode ASC, s.tradeDate DESC")
    List<StockShortData> findForShortCoveringAnalysis(
            @Param("recentDate") LocalDate recentDate,
            @Param("pastDate") LocalDate pastDate);

    // ========== 3. 통계/집계 쿼리 ==========

    /**
     * 일자별 전체 대차잔고 합계
     */
    @Query("SELECT s.tradeDate, SUM(s.loanBalanceValue) " +
           "FROM StockShortData s " +
           "WHERE s.tradeDate BETWEEN :startDate AND :endDate " +
           "GROUP BY s.tradeDate " +
           "ORDER BY s.tradeDate DESC")
    List<Object[]> findDailyTotalLoanBalance(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 종목별 대차잔고 추이 (기간 내)
     */
    @Query("SELECT s.stockCode, s.tradeDate, s.loanBalanceQuantity, s.loanBalanceRatio " +
           "FROM StockShortData s " +
           "WHERE s.stockCode = :stockCode " +
           "AND s.tradeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY s.tradeDate ASC")
    List<Object[]> findLoanBalanceTrend(
            @Param("stockCode") String stockCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 데이터가 있는 거래일 목록 조회
     */
    @Query("SELECT DISTINCT s.tradeDate FROM StockShortData s " +
           "ORDER BY s.tradeDate DESC")
    List<LocalDate> findDistinctTradeDates();

    /**
     * 특정 기간 내 거래일 수 조회
     */
    @Query("SELECT COUNT(DISTINCT s.tradeDate) FROM StockShortData s " +
           "WHERE s.tradeDate BETWEEN :startDate AND :endDate")
    long countTradingDays(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // ========== 4. 데이터 수집/관리용 ==========

    /**
     * 특정 날짜 데이터 삭제 (재수집용)
     *
     * @param tradeDate 삭제할 날짜
     */
    @Modifying
    void deleteByTradeDate(LocalDate tradeDate);

    /**
     * 종목별 데이터 존재 여부
     */
    boolean existsByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    /**
     * 특정 기간 데이터 삭제 (백필 재수집용)
     */
    @Modifying
    @Query("DELETE FROM StockShortData s " +
           "WHERE s.tradeDate BETWEEN :startDate AND :endDate")
    int deleteByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 특정 종목의 모든 데이터 삭제
     */
    @Modifying
    void deleteByStockCode(String stockCode);

    /**
     * 일자별 데이터 건수 조회
     */
    @Query("SELECT s.tradeDate, COUNT(s) " +
           "FROM StockShortData s " +
           "GROUP BY s.tradeDate " +
           "ORDER BY s.tradeDate DESC")
    List<Object[]> countByTradeDate();

    // ========== 5. 전 종목 조회 ==========

    /**
     * 모든 종목 코드 조회 (중복 제거)
     * - 전 종목 재무 데이터 수집에 사용
     *
     * @return 중복 제거된 종목 코드 목록
     */
    @Query("SELECT DISTINCT s.stockCode FROM StockShortData s ORDER BY s.stockCode ASC")
    List<String> findDistinctStockCodes();

    /**
     * 종목 코드와 이름 조회 (최신 데이터 기준)
     * - 재무 데이터 수집 시 종목명도 함께 필요할 때 사용
     */
    @Query("SELECT s.stockCode, s.stockName FROM StockShortData s " +
           "WHERE s.tradeDate = (SELECT MAX(s2.tradeDate) FROM StockShortData s2) " +
           "ORDER BY s.stockCode ASC")
    List<Object[]> findAllStockCodesWithNames();
}
