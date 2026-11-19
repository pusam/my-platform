package com.myplatform.backend.leaders;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** 주도주 목록 저장소 — {@link LeaderStockService} 만 쓴다. */
public interface LeaderStockDailyRepository extends JpaRepository<LeaderStockDaily, Long> {

    /** 가장 새 기준일. 행이 없으면 null. */
    @Query("SELECT MAX(l.basisDate) FROM LeaderStockDaily l")
    LocalDate findLatestBasisDate();

    boolean existsByBasisDate(LocalDate basisDate);

    long countByBasisDate(LocalDate basisDate);

    List<LeaderStockDaily> findByBasisDateOrderByRankOrderAsc(LocalDate basisDate, Pageable pageable);

    /** 그 기준일 행을 통째로 지운다 — 곧바로 SQL 로 나간다(같은 트랜잭션의 새 행이 유니크 키에 걸리지 않게). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LeaderStockDaily l WHERE l.basisDate = :basisDate")
    int deleteAllOfBasisDate(@Param("basisDate") LocalDate basisDate);
}
