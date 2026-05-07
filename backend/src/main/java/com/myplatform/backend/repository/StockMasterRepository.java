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

    @Modifying
    @Query(value = """
        INSERT INTO stock_master (stock_code, stock_name, market, sector, listed_date, is_active, source, updated_at)
        VALUES (:stockCode, :stockName, :market, :sector, :listedDate, 1, :source, NOW())
        ON DUPLICATE KEY UPDATE
            stock_name  = VALUES(stock_name),
            market      = COALESCE(VALUES(market), market),
            sector      = COALESCE(VALUES(sector), sector),
            listed_date = COALESCE(VALUES(listed_date), listed_date),
            is_active   = 1,
            source      = VALUES(source),
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
