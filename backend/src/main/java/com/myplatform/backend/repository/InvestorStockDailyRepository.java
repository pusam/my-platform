package com.myplatform.backend.repository;

import com.myplatform.backend.entity.InvestorStockDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 종목별 투자자 확정치 저장소 — {@code InvestorFinalDataService}. */
public interface InvestorStockDailyRepository extends JpaRepository<InvestorStockDaily, Long> {

    List<InvestorStockDaily> findByTradeDateGreaterThanEqual(LocalDate from);

    List<InvestorStockDaily> findByTradeDate(LocalDate tradeDate);
}
