package com.myplatform.backend.leaders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주도주 목록의 하루치 한 줄(V80, 2026-11-19) — 표시 전용. 칸의 뜻은 마이그레이션 주석과 {@link LeaderRules}.
 */
@Entity
@Table(name = "leader_stock_daily",
       uniqueConstraints = @UniqueConstraint(name = "uq_lsd_basis_stock", columnNames = {"basis_date", "stock_code"}),
       indexes = @Index(name = "idx_lsd_basis_rank", columnList = "basis_date, rank_order"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderStockDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 값이 굳은 마지막 거래일 — 그날 종가 기준. */
    @Column(name = "basis_date", nullable = false)
    private LocalDate basisDate;

    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 200)
    private String stockName;

    @Column(name = "market", length = 20)
    private String market;

    /** 시가총액(억 원, 보통주). */
    @Column(name = "market_cap_eok", nullable = false, precision = 20, scale = 2)
    private BigDecimal marketCapEok;

    @Column(name = "close_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal closePrice;

    /** 20거래일 평균 거래대금(억 원). */
    @Column(name = "avg_traded_value_eok", nullable = false, precision = 20, scale = 2)
    private BigDecimal avgTradedValueEok;

    /** 20거래일 등락률(%). null = 그 날짜 봉이 없다. */
    @Column(name = "return_20d", precision = 9, scale = 2)
    private BigDecimal return20d;

    /** 60거래일 등락률(%). null = 그 날짜 봉이 없다. */
    @Column(name = "return_60d", precision = 9, scale = 2)
    private BigDecimal return60d;

    @Column(name = "rank_order", nullable = false)
    private Integer rankOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
