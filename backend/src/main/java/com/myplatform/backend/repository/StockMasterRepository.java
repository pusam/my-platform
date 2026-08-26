package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockMasterRepository extends JpaRepository<StockMaster, String> {

    List<StockMaster> findByMarket(String market);

    List<StockMaster> findByIsActive(Boolean isActive);

    /**
     * 재무 수집 유니버스 후보 — 활성 KOSPI/KOSDAQ 종목코드 (AUDIT 2026-08-21 R5).
     *
     * <p>기존 유니버스는 {@code stock_financial_data} 자기참조("이미 재무가 있는 종목")라
     * <b>신규 상장이 영구 배제</b>되고, 테이블이 비면 부트스트랩 자체가 불가능했다
     * ("수집할 종목이 없습니다. 먼저 기본 재무 데이터를 수집하세요.").
     *
     * <p>ETF·KONEX 는 뺀다 — 재무제표 비교 대상이 아니고 수집 시간만 먹는다.
     * 6자리 숫자 코드만 통과시켜 크롤 잔재 같은 비정형 코드를 거른다.
     */
    @Query(value = "SELECT stock_code FROM stock_master "
            + "WHERE is_active = 1 "
            + "  AND market IN ('KOSPI', 'KOSDAQ') "
            + "  AND stock_code REGEXP '^[0-9]{6}$' "
            + "ORDER BY stock_code", nativeQuery = true)
    List<String> findActiveEquityCodes();

    /**
     * 종목명/코드로 검색 — 자동완성용.
     * 우선순위:
     *   1) 종목코드 prefix 매치 (정확)
     *   2) 종목명 prefix 매치 (정확)
     *   3) 종목명 부분 매치 (퍼지)
     * 활성 종목만, 종목명 길이 짧은 순 (관련도 우선).
     */
    @Query(value = """
        SELECT * FROM stock_master
        WHERE is_active = 1
          AND (stock_code LIKE CONCAT(:kw, '%')
               OR stock_name LIKE CONCAT(:kw, '%')
               OR stock_name LIKE CONCAT('%', :kw, '%'))
        ORDER BY
          CASE
            WHEN stock_code LIKE CONCAT(:kw, '%') THEN 0
            WHEN stock_name LIKE CONCAT(:kw, '%') THEN 1
            ELSE 2
          END,
          CHAR_LENGTH(stock_name) ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<StockMaster> search(@Param("kw") String keyword, @Param("limit") int limit);

    @Modifying
    @Query(value = """
        INSERT INTO stock_master (stock_code, stock_name, market, sector, listed_date, is_active, source, updated_at)
        VALUES (:stockCode, :stockName, :market, :sector, :listedDate, 1, :source, NOW())
        ON DUPLICATE KEY UPDATE
            stock_name  = CASE WHEN source = 'MANUAL' THEN stock_name ELSE VALUES(stock_name) END,
            market      = COALESCE(VALUES(market), market),
            sector      = COALESCE(VALUES(sector), sector),
            listed_date = COALESCE(VALUES(listed_date), listed_date),
            is_active   = 1,
            source      = CASE WHEN source = 'MANUAL' THEN source ELSE VALUES(source) END,
            updated_at  = NOW()
        """, nativeQuery = true)
    void upsert(@Param("stockCode") String stockCode,
                @Param("stockName") String stockName,
                @Param("market") String market,
                @Param("sector") String sector,
                @Param("listedDate") java.time.LocalDate listedDate,
                @Param("source") String source);

    @Modifying
    @Query(value = """
        INSERT INTO stock_master (stock_code, stock_name, source, updated_at)
        VALUES (:stockCode, :stockName, :source, NOW())
        ON DUPLICATE KEY UPDATE
            stock_name = VALUES(stock_name),
            source     = CASE WHEN source = 'MANUAL' THEN source ELSE VALUES(source) END,
            updated_at = NOW()
        """, nativeQuery = true)
    void upsertNameOnly(@Param("stockCode") String stockCode,
                        @Param("stockName") String stockName,
                        @Param("source") String source);
}
