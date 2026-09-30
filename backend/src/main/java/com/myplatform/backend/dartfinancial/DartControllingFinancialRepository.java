package com.myplatform.backend.dartfinancial;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DartControllingFinancialRepository extends JpaRepository<DartControllingFinancial, Long> {

    /** 한 종목의 저장된 보고서 전부 — TTM 계산(OK 만 골라 쓴다)과 재조회 판단용. */
    List<DartControllingFinancial> findByStockCode(String stockCode);

    /** 한 종목의 OK 보고서 — 수집기가 PER·PBR 을 만들 때 읽는다. */
    List<DartControllingFinancial> findByStockCodeAndStatus(String stockCode, String status);
}
