package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    // 특정 종목의 가장 최근 시세 조회
    Optional<StockPrice> findTopByStockCodeOrderByFetchedAtDesc(String stockCode);
}
