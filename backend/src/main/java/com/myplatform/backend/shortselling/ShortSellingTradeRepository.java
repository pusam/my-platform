package com.myplatform.backend.shortselling;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShortSellingTradeRepository extends JpaRepository<ShortSellingTrade, Long> {

    /** 가장 최근 기준일 — 한 번도 수집된 적 없으면 비어 있다. */
    @Query("SELECT MAX(t.tradeDate) FROM ShortSellingTrade t")
    Optional<LocalDate> findLatestTradeDate();

    /** 그 기준일의 순위표 — 응답 순서대로. */
    List<ShortSellingTrade> findByTradeDateOrderByRankNoAsc(LocalDate tradeDate, Pageable pageable);

    /** 같은 기준일을 다시 수집할 때 그날 행을 갈아 끼운다(순위표 스냅샷 — 빠진 종목이 남지 않게). */
    @Modifying
    @Query("DELETE FROM ShortSellingTrade t WHERE t.tradeDate = :tradeDate")
    int deleteByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}
