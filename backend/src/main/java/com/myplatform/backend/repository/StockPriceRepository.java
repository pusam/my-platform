package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    // 특정 종목의 가장 최근 시세 조회
    Optional<StockPrice> findTopByStockCodeOrderByFetchedAtDesc(String stockCode);

    /**
     * 여러 종목의 가장 최근 시세를 한 쿼리로 조회 (검색 결과 가격 채우기용).
     * id 가 auto-increment 라 stockCode 별 MAX(id) = 최신 행.
     */
    @Query("SELECT sp FROM StockPrice sp WHERE sp.id IN " +
           "(SELECT MAX(sp2.id) FROM StockPrice sp2 WHERE sp2.stockCode IN :codes GROUP BY sp2.stockCode)")
    List<StockPrice> findLatestByStockCodes(@org.springframework.data.repository.query.Param("codes") List<String> codes);

    /**
     * 최근에 거래량이 많았던 종목 코드 (중복 제거, 최대 거래량 기준 내림차순).
     * 일봉 일괄 수집 universe 추출용.
     */
    @Query("SELECT s.stockCode FROM StockPrice s GROUP BY s.stockCode ORDER BY MAX(s.volume) DESC")
    List<String> findTopVolumeStockCodes(Pageable pageable);
}
