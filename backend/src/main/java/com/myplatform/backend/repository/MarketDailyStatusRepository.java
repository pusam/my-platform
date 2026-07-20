package com.myplatform.backend.repository;

import com.myplatform.backend.entity.MarketDailyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketDailyStatusRepository extends JpaRepository<MarketDailyStatus, Long> {

    /**
     * 특정 시장의 최신 데이터 조회
     */
    Optional<MarketDailyStatus> findTopByMarketTypeOrderByTradeDateDesc(String marketType);

    /**
     * 특정 날짜, 특정 시장의 데이터 조회
     */
    Optional<MarketDailyStatus> findByMarketTypeAndTradeDate(String marketType, LocalDate tradeDate);

    /**
     * 특정 시장의 최근 N일 데이터 조회 (ADR 계산용)
     */
    @Query("SELECT m FROM MarketDailyStatus m WHERE m.marketType = :marketType " +
           "ORDER BY m.tradeDate DESC LIMIT :days")
    List<MarketDailyStatus> findRecentByMarketType(@Param("marketType") String marketType,
                                                    @Param("days") int days);

    /**
     * 날짜 범위로 조회
     */
    List<MarketDailyStatus> findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(
            String marketType, LocalDate startDate, LocalDate endDate);

    /**
     * 특정 날짜의 모든 시장 데이터 조회
     */
    List<MarketDailyStatus> findByTradeDate(LocalDate tradeDate);

    /**
     * 최신 날짜의 모든 시장 데이터 조회
     */
    @Query("SELECT m FROM MarketDailyStatus m WHERE m.tradeDate = " +
           "(SELECT MAX(m2.tradeDate) FROM MarketDailyStatus m2)")
    List<MarketDailyStatus> findLatestAll();

    /**
     * 특정 시장의 ADR 계산용 합계 — startDate 이후 <b>존재하는 거래일 전부</b>의 합.
     * (거래일 수 자체는 호출측이 startDate 로 정한다 — 휴장일 때문에 달력일≠거래일인 점 유의.)
     * GROUP BY 없는 단일행 집계라 ORDER BY 를 붙이지 않는다 — MariaDB 는 무시하지만
     * ONLY_FULL_GROUP_BY(MySQL8 기본)에선 문법 오류로 실패한다.
     */
    @Query("SELECT SUM(m.advancingCount) as advSum, SUM(m.decliningCount) as decSum " +
           "FROM MarketDailyStatus m WHERE m.marketType = :marketType " +
           "AND m.tradeDate >= :startDate")
    Object[] calculateAdrSums(@Param("marketType") String marketType,
                               @Param("startDate") LocalDate startDate);
}
