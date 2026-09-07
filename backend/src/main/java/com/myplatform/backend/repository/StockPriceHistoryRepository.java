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
     * 위 쿼리 + <b>최근성 조건</b> — 마지막 봉이 {@code recentSince} 이후인 종목만 (AUDIT R2, 2026-08-31).
     * <p>대조군 유니버스 전용. 최근성 없는 버전은 수집이 끊긴 종목(사실상 죽은 축)까지 포함해
     * 대조군 base rate 를 끌어내렸다 — 시그널은 살아있는 종목에서만 나오므로 비대칭(edge 과대).
     */
    @Query("SELECT h.stockCode FROM StockPriceHistory h " +
           "GROUP BY h.stockCode HAVING COUNT(h) >= :minDays AND MAX(h.tradeDate) >= :recentSince")
    List<String> findActiveStockCodesWithMinHistory(@Param("minDays") long minDays,
                                                    @Param("recentSince") LocalDate recentSince);

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
    /**
     * 최근 창에서 봉이 {@code minBars}개 이상인데 거래량이 전부 0 인 종목 — 거래정지 감지(2026-09-07, 이오플로우).
     * 거래정지 종목은 KIS 마스터에 남고(상장 유지) KIS 가 동결가를 계속 줘서 volume=0 봉이 매일 쌓인다 —
     * 이 패턴이 "며칠째 체결 0건"의 실측 신호다. volume NULL 은 미수집이라 판정에서 뺀다(§4c).
     */
    @Query("SELECT h.stockCode FROM StockPriceHistory h " +
           "WHERE h.tradeDate >= :from AND h.volume IS NOT NULL " +
           "GROUP BY h.stockCode " +
           "HAVING COUNT(h) >= :minBars AND MAX(h.volume) = 0")
    List<String> findCodesWithAllZeroVolumeSince(@Param("from") LocalDate from, @Param("minBars") long minBars);

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
