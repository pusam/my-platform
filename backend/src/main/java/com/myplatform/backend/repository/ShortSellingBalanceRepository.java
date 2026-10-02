package com.myplatform.backend.repository;

import com.myplatform.backend.entity.ShortSellingBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 공매도 <b>잔고</b> — 2026-10-02 기준 출처가 없어(KRX·네이버 死, KIS 잔고 API 없음) 표가 비어 있다.
 * 남은 조회는 봇 고공매도 차단·19시 경보뿐이고 빈 표면 둘 다 작동하지 않는다. 화면의 공매도는 거래 비중
 * ({@code shortselling/ShortSellingTradeRepository})이다.
 */
public interface ShortSellingBalanceRepository extends JpaRepository<ShortSellingBalance, Long> {

    Optional<ShortSellingBalance> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    /** 최근 거래일 조회 */
    @Query("SELECT MAX(s.tradeDate) FROM ShortSellingBalance s")
    Optional<LocalDate> findLatestTradeDate();

    /** 공매도 비율 높은 종목 조회 */
    @Query("SELECT s FROM ShortSellingBalance s WHERE s.tradeDate = :tradeDate AND s.shortSellingRatio >= :minRatio ORDER BY s.shortSellingRatio DESC")
    List<ShortSellingBalance> findHighShortSellingStocks(@Param("tradeDate") LocalDate tradeDate, @Param("minRatio") BigDecimal minRatio);
}
