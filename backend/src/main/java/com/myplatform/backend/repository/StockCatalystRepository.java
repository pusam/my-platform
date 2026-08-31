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

    /** 한 종목의 재료 이력(표시 전용) — 최근 N일(minDate 이상) 최신순. read-only, 신규 classify 없음(§4b). */
    List<StockCatalyst> findByStockCodeAndCatalystDateGreaterThanEqualOrderByCatalystDateDesc(
            String stockCode, LocalDate minDate);

    /** 일별 분류 집계 프로젝션 — 관제실 재료 파이프라인 정지 규칙(⑨) 전용. */
    interface DailyClassificationStat {
        LocalDate getDate();
        long getTotal();
        long getNoneCount();
    }

    /** 최근 일별 총 분류 수·NONE 수 (날짜 내림차순) — 전-NONE 정지/유입 정지 감지용(read-only). */
    @org.springframework.data.jpa.repository.Query(
            """
            SELECT c.catalystDate AS date, COUNT(c) AS total,
                   SUM(CASE WHEN c.catalystType = com.myplatform.backend.entity.StockCatalyst.CatalystType.NONE
                            THEN 1 ELSE 0 END) AS noneCount
            FROM StockCatalyst c WHERE c.catalystDate >= :minDate
            GROUP BY c.catalystDate ORDER BY c.catalystDate DESC
            """)
    List<DailyClassificationStat> findDailyStatsSince(LocalDate minDate);
}
