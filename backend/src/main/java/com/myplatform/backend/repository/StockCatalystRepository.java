package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockCatalyst;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface StockCatalystRepository extends JpaRepository<StockCatalyst, Long> {

    /** 종목·일자 캐시 lookup — 같은 날 Gemini 재호출 방지 + 시그널 record 시 스냅샷용. */
    Optional<StockCatalyst> findByStockCodeAndCatalystDate(String stockCode, LocalDate catalystDate);
}
