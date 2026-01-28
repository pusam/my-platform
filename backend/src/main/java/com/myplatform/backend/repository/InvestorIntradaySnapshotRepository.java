package com.myplatform.backend.repository;

import com.myplatform.backend.entity.InvestorIntradaySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvestorIntradaySnapshotRepository extends JpaRepository<InvestorIntradaySnapshot, Long> {

    /**
     * 특정 시점의 스냅샷 조회
     */
    List<InvestorIntradaySnapshot> findBySnapshotDateAndSnapshotTimeAndInvestorTypeOrderByRankNumAsc(
            LocalDate snapshotDate, LocalTime snapshotTime, String investorType);

    /**
     * 특정 종목의 당일 스냅샷 조회 (시간순)
     */
    List<InvestorIntradaySnapshot> findByStockCodeAndSnapshotDateAndInvestorTypeOrderBySnapshotTimeAsc(
            String stockCode, LocalDate snapshotDate, String investorType);

    /**
     * 직전 스냅샷 시간 조회
     */
    @Query("SELECT MAX(s.snapshotTime) FROM InvestorIntradaySnapshot s " +
           "WHERE s.snapshotDate = :date AND s.investorType = :investorType " +
           "AND s.snapshotTime < :currentTime")
    Optional<LocalTime> findPreviousSnapshotTime(
            @Param("date") LocalDate date,
            @Param("investorType") String investorType,
            @Param("currentTime") LocalTime currentTime);

    /**
     * 직전 스냅샷의 특정 종목 데이터 조회
     */
    Optional<InvestorIntradaySnapshot> findByStockCodeAndSnapshotDateAndSnapshotTimeAndInvestorType(
            String stockCode, LocalDate snapshotDate, LocalTime snapshotTime, String investorType);

    /**
     * 금일 급증 종목 조회 (순매수 금액 기준 정렬)
     */
    @Query("SELECT s FROM InvestorIntradaySnapshot s " +
           "WHERE s.snapshotDate = :date AND s.snapshotTime = :time " +
           "AND s.investorType = :investorType " +
           "AND (s.amountChange >= :minChange OR s.netBuyAmount >= :minChange) " +
           "ORDER BY s.netBuyAmount DESC")
    List<InvestorIntradaySnapshot> findSurgeStocks(
            @Param("date") LocalDate date,
            @Param("time") LocalTime time,
            @Param("investorType") String investorType,
            @Param("minChange") java.math.BigDecimal minChange);

    /**
     * 최신 스냅샷 전체 조회 (순매수 금액 기준 정렬)
     */
    @Query("SELECT s FROM InvestorIntradaySnapshot s " +
           "WHERE s.snapshotDate = :date AND s.snapshotTime = :time " +
           "AND s.investorType = :investorType " +
           "ORDER BY s.netBuyAmount DESC")
    List<InvestorIntradaySnapshot> findLatestSnapshots(
            @Param("date") LocalDate date,
            @Param("time") LocalTime time,
            @Param("investorType") String investorType);

    /**
     * 당일 최신 스냅샷 시간 조회
     */
    @Query("SELECT MAX(s.snapshotTime) FROM InvestorIntradaySnapshot s " +
           "WHERE s.snapshotDate = :date AND s.investorType = :investorType")
    Optional<LocalTime> findLatestSnapshotTime(
            @Param("date") LocalDate date,
            @Param("investorType") String investorType);

    /**
     * 특정 시점의 상위 종목 조회
     */
    @Query("SELECT s FROM InvestorIntradaySnapshot s " +
           "WHERE s.snapshotDate = :date AND s.snapshotTime = :time " +
           "AND s.investorType = :investorType " +
           "ORDER BY s.netBuyAmount DESC")
    List<InvestorIntradaySnapshot> findTopByNetBuyAmount(
            @Param("date") LocalDate date,
            @Param("time") LocalTime time,
            @Param("investorType") String investorType);

    /**
     * 오래된 스냅샷 삭제 (N일 이전)
     */
    void deleteBySnapshotDateBefore(LocalDate date);

    /**
     * 특정 시점의 스냅샷 삭제 (중복 방지용)
     */
    void deleteBySnapshotDateAndSnapshotTimeAndInvestorType(
            LocalDate snapshotDate, LocalTime snapshotTime, String investorType);

    /**
     * 당일 스냅샷 시간 목록 조회
     */
    @Query("SELECT DISTINCT s.snapshotTime FROM InvestorIntradaySnapshot s " +
           "WHERE s.snapshotDate = :date AND s.investorType = :investorType " +
           "ORDER BY s.snapshotTime ASC")
    List<LocalTime> findSnapshotTimes(
            @Param("date") LocalDate date,
            @Param("investorType") String investorType);
}
