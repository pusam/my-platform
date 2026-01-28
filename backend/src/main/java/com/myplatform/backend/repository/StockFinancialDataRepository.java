package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockFinancialData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockFinancialDataRepository extends JpaRepository<StockFinancialData, Long>,
                                                      JpaSpecificationExecutor<StockFinancialData> {

    Optional<StockFinancialData> findTopByStockCodeOrderByReportDateDesc(String stockCode);

    List<StockFinancialData> findByStockCode(String stockCode);

    List<StockFinancialData> findByReportDate(LocalDate reportDate);

    // 퀀트 스크리닝 쿼리
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND (:minPer IS NULL OR s.per >= :minPer) " +
           "AND (:maxPer IS NULL OR s.per <= :maxPer) " +
           "AND (:minRoe IS NULL OR s.roe >= :minRoe) " +
           "AND (:maxRoe IS NULL OR s.roe <= :maxRoe) " +
           "AND (:minPbr IS NULL OR s.pbr >= :minPbr) " +
           "AND (:maxPbr IS NULL OR s.pbr <= :maxPbr) " +
           "AND (:minMarketCap IS NULL OR s.marketCap >= :minMarketCap) " +
           "AND (:maxMarketCap IS NULL OR s.marketCap <= :maxMarketCap) " +
           "AND (:minDividendYield IS NULL OR s.dividendYield >= :minDividendYield) " +
           "AND (:market IS NULL OR s.market = :market) " +
           "AND (:sector IS NULL OR s.sector = :sector)")
    List<StockFinancialData> findByQuantCriteria(
        @Param("minPer") BigDecimal minPer,
        @Param("maxPer") BigDecimal maxPer,
        @Param("minRoe") BigDecimal minRoe,
        @Param("maxRoe") BigDecimal maxRoe,
        @Param("minPbr") BigDecimal minPbr,
        @Param("maxPbr") BigDecimal maxPbr,
        @Param("minMarketCap") BigDecimal minMarketCap,
        @Param("maxMarketCap") BigDecimal maxMarketCap,
        @Param("minDividendYield") BigDecimal minDividendYield,
        @Param("market") String market,
        @Param("sector") String sector
    );

    // 저PER 우량주
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND s.per > 0 AND s.per <= :maxPer " +
           "AND s.roe >= :minRoe " +
           "AND s.marketCap >= :minMarketCap " +
           "ORDER BY s.per ASC")
    List<StockFinancialData> findLowPerHighRoeStocks(
        @Param("maxPer") BigDecimal maxPer,
        @Param("minRoe") BigDecimal minRoe,
        @Param("minMarketCap") BigDecimal minMarketCap
    );

    // 저PBR 우량주
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND s.pbr > 0 AND s.pbr <= :maxPbr " +
           "AND s.roe >= :minRoe " +
           "AND s.marketCap >= :minMarketCap " +
           "ORDER BY s.pbr ASC")
    List<StockFinancialData> findLowPbrHighRoeStocks(
        @Param("maxPbr") BigDecimal maxPbr,
        @Param("minRoe") BigDecimal minRoe,
        @Param("minMarketCap") BigDecimal minMarketCap
    );

    // 고배당주
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND s.dividendYield >= :minDividendYield " +
           "AND s.marketCap >= :minMarketCap " +
           "ORDER BY s.dividendYield DESC")
    List<StockFinancialData> findHighDividendStocks(
        @Param("minDividendYield") BigDecimal minDividendYield,
        @Param("minMarketCap") BigDecimal minMarketCap
    );

    // 업종별 통계
    @Query("SELECT s.sector, COUNT(s), AVG(s.per), AVG(s.roe), AVG(s.pbr) " +
           "FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "GROUP BY s.sector")
    List<Object[]> getSectorStatistics();

    // 마법의 공식용 - 영업이익률과 ROE가 있는 최신 데이터 조회
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND s.operatingMargin IS NOT NULL AND s.operatingMargin > 0 " +
           "AND s.roe IS NOT NULL AND s.roe > 0 " +
           "AND s.per IS NOT NULL AND s.per > 0 " +
           "AND (:minMarketCap IS NULL OR s.marketCap >= :minMarketCap) " +
           "ORDER BY s.operatingMargin DESC, s.roe DESC")
    List<StockFinancialData> findForMagicFormula(@Param("minMarketCap") BigDecimal minMarketCap);

    // PEG 기준 저평가 종목 조회
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND s.peg IS NOT NULL AND s.peg > 0 " +
           "AND (:maxPeg IS NULL OR s.peg <= :maxPeg) " +
           "AND s.epsGrowth IS NOT NULL " +
           "AND (:minEpsGrowth IS NULL OR s.epsGrowth >= :minEpsGrowth) " +
           "ORDER BY s.peg ASC")
    List<StockFinancialData> findLowPegStocks(
        @Param("maxPeg") BigDecimal maxPeg,
        @Param("minEpsGrowth") BigDecimal minEpsGrowth
    );

    // 턴어라운드 종목용 - 순이익이 있는 모든 데이터 (분기별 비교를 위해)
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.stockCode = :stockCode " +
           "ORDER BY s.reportDate DESC")
    List<StockFinancialData> findByStockCodeOrderByReportDateDesc(@Param("stockCode") String stockCode);

    // 최신 데이터가 있는 모든 종목 조회
    @Query("SELECT DISTINCT s.stockCode FROM StockFinancialData s")
    List<String> findAllStockCodes();

    // 최신 재무 데이터만 조회
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode) " +
           "AND s.netIncome IS NOT NULL")
    List<StockFinancialData> findLatestDataWithNetIncome();

    // 종목코드와 날짜로 조회
    Optional<StockFinancialData> findByStockCodeAndReportDate(String stockCode, LocalDate reportDate);

    // ========== [성능 최적화] 턴어라운드 스크리너용 Bulk 조회 ==========

    /**
     * 최근 N개월 내 데이터를 한 번에 조회 (N+1 방지)
     * - 모든 종목의 최근 데이터를 한 번의 쿼리로 가져옴
     * - 서비스에서 groupingBy로 종목별 처리
     *
     * @param minDate 최소 날짜 (이 날짜 이후 데이터만 조회)
     * @return 모든 종목의 최근 데이터 목록
     */
    @Query("SELECT s FROM StockFinancialData s " +
           "WHERE s.reportDate >= :minDate " +
           "AND s.netIncome IS NOT NULL " +
           "ORDER BY s.stockCode ASC, s.reportDate DESC")
    List<StockFinancialData> findAllRecentData(@Param("minDate") LocalDate minDate);

    /**
     * 최근 2개 분기 데이터만 조회 (턴어라운드 분석 최적화)
     * - 각 종목별 최신 2개 레코드만 필요
     */
    @Query(value = "SELECT * FROM (" +
           "  SELECT s.*, ROW_NUMBER() OVER (PARTITION BY s.stock_code ORDER BY s.report_date DESC) as rn " +
           "  FROM stock_financial_data s " +
           "  WHERE s.net_income IS NOT NULL " +
           ") ranked WHERE rn <= 2",
           nativeQuery = true)
    List<StockFinancialData> findLatestTwoQuartersPerStock();
}

