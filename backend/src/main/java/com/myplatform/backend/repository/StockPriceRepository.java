package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    // 특정 종목의 가장 최근 시세 조회
    Optional<StockPrice> findTopByStockCodeOrderByFetchedAtDesc(String stockCode);

    /**
     * 여러 종목의 가장 최근 시세를 한 쿼리로 조회.
     * 이전: WHERE id IN (subquery GROUP BY) 패턴 — MariaDB optimizer 가 semi-join 으로
     *      최적화 못해 connection 120s+ 점유 → leak 경고.
     * 변경: INNER JOIN (subquery) 패턴 + native — 인덱스 (stock_code, fetched_at) 의 PK
     *      포함 leaf 활용. 1초 이내 종료 기대.
     * read-only + timeout 명시로 안전망.
     */
    @Query(value = "SELECT sp.* FROM stock_price sp " +
                   "INNER JOIN (" +
                   "  SELECT stock_code, MAX(id) AS max_id " +
                   "  FROM stock_price " +
                   "  WHERE stock_code IN (:codes) " +
                   "  GROUP BY stock_code" +
                   ") latest ON sp.id = latest.max_id",
           nativeQuery = true)
    @Transactional(readOnly = true, timeout = 10)
    @QueryHints({
        @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<StockPrice> findLatestByStockCodes(@org.springframework.data.repository.query.Param("codes") List<String> codes);

    /**
     * 최근에 거래량이 많았던 종목 코드 (중복 제거, 최대 거래량 기준 내림차순).
     * 일봉 일괄 수집 universe 추출용.
     * <p>fetchedAt 윈도우 필수 — 시세 캐시 테이블은 무한 증가라, 필터 없이 GROUP BY 하면
     * 전 이력 filesort 풀스캔이 된다(LIMIT 은 집계 후 적용이라 무력). "최근" 의미상으로도
     * 상장폐지/휴면 종목이 옛 거래량으로 universe 에 남는 것을 막는다.
     */
    @Query("SELECT s.stockCode FROM StockPrice s WHERE s.fetchedAt >= :since "
            + "GROUP BY s.stockCode ORDER BY MAX(s.volume) DESC")
    List<String> findTopVolumeStockCodes(@org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since,
                                         Pageable pageable);

    /**
     * P0-2 진단: 일정 기간 시세 이력을 종목코드·시각 순으로 조회 (×10 스케일 이상치 스캔용).
     * read-only + timeout 으로 본 흐름에 영향 없도록 보호. 진단 온디맨드 호출 전용.
     */
    @Query(value = "SELECT sp.* FROM stock_price sp " +
                   "WHERE sp.fetched_at >= :since " +
                   "ORDER BY sp.stock_code, sp.fetched_at",
           nativeQuery = true)
    @Transactional(readOnly = true, timeout = 30)
    @QueryHints({
        @QueryHint(name = "org.hibernate.readOnly", value = "true")
    })
    List<StockPrice> findAllSince(@org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);
}
