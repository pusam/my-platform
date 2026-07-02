package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockCatalyst;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockCatalystRepository extends JpaRepository<StockCatalyst, Long> {

    /** 종목·일자 캐시 lookup — 같은 날 Gemini 재호출 방지 + 시그널 record 시 스냅샷용. */
    Optional<StockCatalyst> findByStockCodeAndCatalystDate(String stockCode, LocalDate catalystDate);

    /** 여러 종목의 일캐시 배치 조회(N쿼리 방지) — 종합판단 보드 재료 배지(표시 전용, 신규 분류 안 함). */
    List<StockCatalyst> findByCatalystDateAndStockCodeIn(LocalDate catalystDate, Collection<String> stockCodes);

    /** 최근 N일(minDate 이상) 재료 배치 조회 — 보드가 코드별 '최신'을 골라 표시(오늘 없으면 어제까지).
     *  §4b 일캐시(분류)는 불변, 표시 날짜창만 확장. 낡음 방지는 minDate 필터 + 경과일 표기(호출측). */
    List<StockCatalyst> findByCatalystDateGreaterThanEqualAndStockCodeIn(LocalDate minDate, Collection<String> stockCodes);
}
