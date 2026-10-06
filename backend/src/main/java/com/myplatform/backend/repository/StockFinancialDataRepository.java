package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockFinancialData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockFinancialDataRepository extends JpaRepository<StockFinancialData, Long>,
                                                      JpaSpecificationExecutor<StockFinancialData> {

    /**
     * 쓸 수 있는 EPS 성장률 — 종목당 최신 1건(2026-09-21).
     *
     * <p>⚠ <b>미래 날짜를 거르지 않고 최신 1건을 집으면 안 된다</b>(예전 {@code findTopByStockCodeOrderByReportDateDesc} —
     * 2026-10-07 삭제, {@code StockFinancialLatestRowGuardTest}) — report_date 최댓값이 <b>미래(12-31 추정치 행)</b> 일 수
     * 있다(§4c 미래 날짜 항목). 실제로 그 함정에 빠져 삼성전자 EPS 성장률이 <b>315.39%</b> 로 잡히고 Forward PER 이
     * 41.8 → 10 으로 뒤집혔다.
     *
     * <p>그래서 ① {@code report_date <= CURDATE()} ② {@code eps_growth <> 0} 두 조건을 건다.
     * 0 을 빼는 이유는 <b>무성장과 미산출을 구분할 수 없어서</b>다 — 당일 수집분 2,587행이 전부 0 이었다
     * (성장률 배치가 채우지 못한 것으로 보인다). 0 을 성장률로 믿고 Forward 지표를 만들면 또 위장이 된다.
     */
    @Query(value = "SELECT * FROM stock_financial_data s "
            + " WHERE s.stock_code = :code AND s.report_date <= CURDATE() "
            + "   AND s.eps_growth IS NOT NULL AND s.eps_growth <> 0 "
            + " ORDER BY s.report_date DESC LIMIT 1", nativeQuery = true)
    Optional<StockFinancialData> findLatestUsableEpsGrowth(@Param("code") String code);

    /**
     * 가치 점수 산정용 — 최신 10건.
     * 단일 row 가 일부 컬럼만 채워진 케이스(예: 미래 일자 annual row 는 영업이익만, 일별 row 는 PBR/ROE 만)
     * 대비해 호출측에서 first non-null 로 합성.
     */
    List<StockFinancialData> findTop10ByStockCodeOrderByReportDateDesc(String stockCode);

    List<StockFinancialData> findByStockCode(String stockCode);

    List<StockFinancialData> findByReportDate(LocalDate reportDate);

    // ⚠ 종목별 최신 행을 미래 날짜 가드 없이 집던 조회 8개(퀀트 조건·저PER·저PBR·고배당·업종 통계·순이익 최신·종목별 최신 1행·
    //   최신 1건)는 호출처가 하나도 없어 지웠다(2026-10-07). 최신 행 조회를 새로 만들면 report_date <= CURDATE() 를 걸 것 —
    //   StockFinancialLatestRowGuardTest 가 이 저장소 전체를 본다.

    // ⚠ 세 스크리너 쿼리(마법의 공식·PEG·성장)의 "종목별 최신 행"은 미래 날짜를 뺀다(2026-10-01) — 네이버 연말 추정치 행
    //   (report_date=12-31, PER 없음 342개)을 최신으로 집으면 조건을 못 넘어 종목이 통째로 빠졌다(오늘 행 기준 251종목).
    //   12-31 당일부터는 그 행이 '미래'가 아니게 된다 — writer 구분(market_cap)은 백로그(AUDIT_2026-10-01).

    // 마법의 공식용 - 영업이익률과 ROE가 있는 최신 데이터 조회
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode " +
           "    AND s2.reportDate <= CURRENT_DATE) " +
           "AND s.operatingMargin IS NOT NULL AND s.operatingMargin > 0 " +
           "AND s.roe IS NOT NULL AND s.roe > 0 " +
           "AND s.per IS NOT NULL AND s.per > 0 " +
           "AND (:minMarketCap IS NULL OR s.marketCap >= :minMarketCap) " +
           "ORDER BY s.operatingMargin DESC, s.roe DESC")
    List<StockFinancialData> findForMagicFormula(@Param("minMarketCap") BigDecimal minMarketCap);

    // PEG 기준 저평가 종목 조회 (epsGrowth 있는 경우)
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode " +
           "    AND s2.reportDate <= CURRENT_DATE) " +
           "AND s.peg IS NOT NULL AND s.peg > 0 " +
           "AND (:maxPeg IS NULL OR s.peg <= :maxPeg) " +
           "AND s.epsGrowth IS NOT NULL " +
           "AND (:minEpsGrowth IS NULL OR s.epsGrowth >= :minEpsGrowth) " +
           "ORDER BY s.peg ASC")
    List<StockFinancialData> findLowPegStocks(
        @Param("maxPeg") BigDecimal maxPeg,
        @Param("minEpsGrowth") BigDecimal minEpsGrowth
    );

    // PEG 계산용 - PER과 성장률(epsGrowth 또는 profitGrowth)이 있는 최신 데이터 조회
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.reportDate = (SELECT MAX(s2.reportDate) FROM StockFinancialData s2 WHERE s2.stockCode = s.stockCode " +
           "    AND s2.reportDate <= CURRENT_DATE) " +
           "AND s.per IS NOT NULL AND s.per > 0 " +
           "AND (s.epsGrowth IS NOT NULL AND s.epsGrowth > 0 OR s.profitGrowth IS NOT NULL AND s.profitGrowth > 0)")
    List<StockFinancialData> findStocksWithGrowthData();

    // 턴어라운드 종목용 - 순이익이 있는 모든 데이터 (분기별 비교를 위해)
    @Query("SELECT s FROM StockFinancialData s WHERE " +
           "s.stockCode = :stockCode " +
           "ORDER BY s.reportDate DESC")
    List<StockFinancialData> findByStockCodeOrderByReportDateDesc(@Param("stockCode") String stockCode);

    // 최신 데이터가 있는 모든 종목 조회
    @Query("SELECT DISTINCT s.stockCode FROM StockFinancialData s")
    List<String> findAllStockCodes();

    // 종목코드와 날짜로 조회
    Optional<StockFinancialData> findByStockCodeAndReportDate(String stockCode, LocalDate reportDate);

    // 종목코드로 시장 구분 조회 (KOSPI/KOSDAQ/KONEX)
    @Query("SELECT DISTINCT s.market FROM StockFinancialData s WHERE s.stockCode = :stockCode AND s.market IS NOT NULL")
    List<String> findMarketsByStockCode(@Param("stockCode") String stockCode);

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
           "AND s.reportDate <= CURRENT_DATE " +   // 미래 날짜(추정치 잔여) 행 제외 — 성장률 배치가 이 결과의 첫 행에 쓴다(2026-09-03)
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
           "  WHERE s.net_income IS NOT NULL AND s.report_date <= CURDATE() " +   // 미래 날짜(추정치 잔여) 행 제외(2026-09-02)
           ") ranked WHERE rn <= 2",
           nativeQuery = true)
    List<StockFinancialData> findLatestTwoQuartersPerStock();

    /**
     * 종목별 최근 N행 일괄 조회 — 필드별 합성용 (AUDIT 2026-08-21 R4).
     *
     * <p>예전 {@code findLatestPerStock()}(2026-10-07 삭제)은 종목당 <b>1행</b>이라, 그 행이 0 placeholder 투성이면
     * 그대로 채점됐다(실측: 005930 debt_ratio=0.00 → 18점이어야 할 종목이 5점).
     * 여기서 여러 행을 가져와 {@code FinancialRowSynthesizer} 가 필드별로 메운다.
     *
     * <p>종목당 per-stock 쿼리(N+1)를 피하려고 윈도우 함수로 한 방에 뽑는다 —
     * 트랙은 전 종목을 훑기 때문에 N+1 이면 수천 쿼리가 된다.
     * 힙: 약 3,000종목 × 10행 ≈ 30k 행. 트랙은 lazy 로드 + 30분 캐시라 상시 부하가 아니다.
     */
    @Query(value = "SELECT * FROM ("
            + "  SELECT s.*, ROW_NUMBER() OVER (PARTITION BY s.stock_code ORDER BY s.report_date DESC) as rn"
            + "  FROM stock_financial_data s"
            // 미래 날짜(추정치 잔여 — 2026-07-21 이전 크롤의 "yyyy.12(E)") 행 제외: N행 창을 실적 행으로 채운다(2026-09-02).
            // 소비처는 FinancialRowSynthesizer.excludeFutureDated 로 한 번 더 거른다(같은 규칙, 두 겹).
            + "  WHERE s.report_date <= CURDATE()"
            + ") ranked WHERE rn <= :limitPerStock",
            nativeQuery = true)
    List<StockFinancialData> findRecentPerStock(@Param("limitPerStock") int limitPerStock);

    /**
     * 최신 일별 스냅샷 날짜의 <b>필드 충전율</b> — 입력층 건강 진단 (2026-08-26).
     *
     * <p><b>왜 필요한가</b>: KIS 손익계산서 응답의 금액 필드명이 틀려 434종목 전 기간의
     * revenue/operating_profit/net_income/total_equity 가 <b>몇 달 동안 통째로 NULL</b> 이었는데
     * 어느 화면에도 안 보였다. 종목별 WARN 로그는 있었지만 아무도 434줄을 세지 않는다.
     * "몇 개 중 몇 개가 채워졌나"는 집계로 봐야 보인다.
     *
     * <p>세 묶음은 <b>서로 다른 KIS 호출</b>에서 온다 — 어느 호출이 죽었는지 바로 갈라진다:
     * <ul>
     *   <li>{@code with_ratios}    ← 재무비율 FHKST66430300 (per/pbr/roe)</li>
     *   <li>{@code with_statement} ← 손익계산서 FHKST66430200 (revenue/영업이익/순이익)</li>
     *   <li>{@code with_balance}   ← 대차대조표 FHKST66430100 (자본총계)</li>
     * </ul>
     *
     * <p>분기 행(네이버 크롤)이 섞이지 않도록 <b>오늘 이하의 최신 날짜</b> 하나만 본다 —
     * report_date 최댓값은 미래(12-31 annual row)일 수 있다.
     */
    @Query(value = "SELECT s.report_date AS asOf, COUNT(*) AS total, "
            + " SUM(s.per IS NOT NULL AND s.pbr IS NOT NULL AND s.roe IS NOT NULL) AS withRatios, "
            + " SUM(s.revenue IS NOT NULL OR s.operating_profit IS NOT NULL OR s.net_income IS NOT NULL) AS withStatement, "
            + " SUM(s.total_equity IS NOT NULL) AS withBalance "
            + "FROM stock_financial_data s "
            + "WHERE s.report_date = ("
            + "  SELECT MAX(x.report_date) FROM stock_financial_data x WHERE x.report_date <= CURRENT_DATE"
            + ") GROUP BY s.report_date",
            nativeQuery = true)
    List<Object[]> findLatestSnapshotFieldCoverage();

    /** 이상 점검용 — 기준일이 오늘 이후인 행 수(미래 날짜 행). */
    @Query(value = "SELECT COUNT(*) FROM stock_financial_data WHERE report_date > CURRENT_DATE",
            nativeQuery = true)
    long countFutureDatedRows();

    /**
     * 단위 점검용 — 최신 스냅샷의 <b>매출/시총 비율 중앙값</b>.
     *
     * <p>시총은 다른 API 에서 와 단위가 확실하므로, 이 비율이 비정상적으로 작으면
     * 재무 금액이 배수만큼 작게 저장된 것이다(2026-08-28 100배 사고). 업종 분산에 흔들리지
     * 않게 평균이 아니라 <b>중앙값</b>을 쓴다.
     *
     * <p>둘 다 양수인 행만 본다 — 적자/결측 행이 비율을 오염시키지 않게.
     */
    @Query(value = "SELECT AVG(r) FROM ("
            + "  SELECT s.revenue / s.market_cap AS r,"
            + "         ROW_NUMBER() OVER (ORDER BY s.revenue / s.market_cap) AS rn,"
            + "         COUNT(*) OVER () AS cnt"
            + "  FROM stock_financial_data s"
            + "  WHERE s.report_date = (SELECT MAX(x.report_date) FROM stock_financial_data x"
            + "                         WHERE x.report_date <= CURRENT_DATE)"
            + "    AND s.revenue > 0 AND s.market_cap > 0"
            + ") t WHERE t.rn IN (FLOOR((t.cnt + 1) / 2), FLOOR((t.cnt + 2) / 2))",
            nativeQuery = true)
    Double findMedianRevenueToMarketCap();

    /** 위 중앙값의 표본 수 — 적으면 판정하지 않는다. */
    @Query(value = "SELECT COUNT(*) FROM stock_financial_data s "
            + "WHERE s.report_date = (SELECT MAX(x.report_date) FROM stock_financial_data x "
            + "                       WHERE x.report_date <= CURRENT_DATE) "
            + "  AND s.revenue > 0 AND s.market_cap > 0",
            nativeQuery = true)
    long countUnitSamples();

    /**
     * 마지막 업데이트 시간 조회
     */
    @Query("SELECT MAX(s.updatedAt) FROM StockFinancialData s")
    Optional<LocalDateTime> findLastUpdatedAt();

    // 진행률 표시용 카운트 — findAll() 전건 로딩 대신 COUNT 집계(메모리/커넥션 보호).
    @Query("SELECT COUNT(s) FROM StockFinancialData s WHERE s.operatingMargin IS NULL OR s.operatingMargin = 0")
    long countMissingOperatingMargin();

    @Query("SELECT COUNT(s) FROM StockFinancialData s WHERE s.operatingMargin IS NOT NULL AND s.operatingMargin <> 0")
    long countWithOperatingMargin();

    @Query("SELECT COUNT(s) FROM StockFinancialData s WHERE s.epsGrowth > 0 OR s.profitGrowth > 0")
    long countWithGrowthData();

    /**
     * 가장 최근 일별 스냅샷(KIS 행) 날짜에 성장률이 채워진 행 수 — 기동 시 성장률 따라잡기 판정(2026-09-29).
     *
     * <p>성장률은 2단계 배치만 쓴다. 0 이면 그날 2단계가 안 돌았거나(배포·재시작이 올인원 배치를 끊음 — 9/17·9/21 실측)
     * V62 가 옛 값을 비운 직후다. 네이버 분기 행({@code market_cap IS NULL})은 세지 않는다(§4c writer 구분).
     */
    @Query(value = "SELECT COUNT(*) FROM stock_financial_data s "
            + " WHERE s.market_cap IS NOT NULL "
            + "   AND s.report_date = (SELECT MAX(d.report_date) FROM stock_financial_data d "
            + "                         WHERE d.report_date <= CURDATE() AND d.market_cap IS NOT NULL) "
            + "   AND (s.revenue_growth IS NOT NULL OR s.profit_growth IS NOT NULL)", nativeQuery = true)
    long countGrowthMeasuredAtLatestDate();
}

