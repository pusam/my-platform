package com.myplatform.backend.repository;

import com.myplatform.backend.entity.StockQuarterlyFinancial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 분기 재무 원본 저장소.
 *
 * <p>일별 스냅샷({@code StockFinancialDataRepository})과 <b>역할이 다르다</b> —
 * 여기는 회계 분기 단위이고 주가·밸류에이션이 없다. 실적 변화 판정만 여기를 본다.
 */
public interface StockQuarterlyFinancialRepository extends JpaRepository<StockQuarterlyFinancial, Long> {

    Optional<StockQuarterlyFinancial> findByStockCodeAndFiscalPeriod(String stockCode, String fiscalPeriod);

    /** 한 종목의 분기 행 — 최신이 앞. */
    List<StockQuarterlyFinancial> findByStockCodeOrderByPeriodEndDesc(String stockCode);

    /**
     * 최근 회계기간 행 일괄 조회 (N+1 방지).
     *
     * <p>{@code minPeriodEnd} 하한을 두는 이유: 수집이 멈춘 종목의 수년 전 분기가 매일
     * "최신 실적"으로 붙는 것을 쿼리 단계에서 막는다. 상한 없는 "최신 2건"은 그 함정을
     * 그대로 재현한다(R1 의 원인 중 하나가 정확히 그것이었다).
     */
    @Query("SELECT q FROM StockQuarterlyFinancial q WHERE q.periodEnd >= :minPeriodEnd "
            + "ORDER BY q.stockCode ASC, q.periodEnd ASC")
    List<StockQuarterlyFinancial> findAllSince(@Param("minPeriodEnd") LocalDate minPeriodEnd);

    /** 커버리지 진단용 — 하한 이후 행을 가진 고유 종목 수. */
    @Query("SELECT COUNT(DISTINCT q.stockCode) FROM StockQuarterlyFinancial q WHERE q.periodEnd >= :minPeriodEnd")
    long countDistinctStocksSince(@Param("minPeriodEnd") LocalDate minPeriodEnd);

    /** 커버리지 진단용 — 가장 최근 회계기간 말일(수집 자체가 안 됐으면 null). */
    @Query("SELECT MAX(q.periodEnd) FROM StockQuarterlyFinancial q")
    Optional<LocalDate> findMaxPeriodEnd();

    /** 커버리지 진단용 — 가장 최근 수집 시각(회계 기간이 아니라 배치 생사 판단용). */
    @Query("SELECT MAX(q.collectedAt) FROM StockQuarterlyFinancial q")
    Optional<java.time.LocalDateTime> findMaxCollectedAt();
}
