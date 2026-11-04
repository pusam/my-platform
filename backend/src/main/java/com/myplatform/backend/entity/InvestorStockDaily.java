package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 종목별 투자자 일별 순매수 확정치(V70, 2026-11-05) — KIS 종목별 투자자매매동향(일별) FHPTJ04160001 원본.
 *
 * <p>{@link InvestorDailyTrade} 의 순위는 이 표로 다시 만든다. 금액은 억원, {@code null} = 응답에 없었다(0 아님).
 */
@Entity
@Table(name = "investor_stock_daily",
       uniqueConstraints = @UniqueConstraint(name = "uq_isd_stock_date", columnNames = {"stock_code", "trade_date"}),
       indexes = @Index(name = "idx_isd_date", columnList = "trade_date"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorStockDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 외국인 순매수(억원). */
    @Column(name = "foreign_net", precision = 15, scale = 2)
    private BigDecimal foreignNet;

    /** 기관계 순매수(억원) — 연기금 포함. */
    @Column(name = "institution_net", precision = 15, scale = 2)
    private BigDecimal institutionNet;

    /** 기금(연기금) 순매수(억원). */
    @Column(name = "pension_net", precision = 15, scale = 2)
    private BigDecimal pensionNet;

    @Column(name = "close_price", precision = 15, scale = 0)
    private BigDecimal closePrice;

    @Column(name = "change_rate", precision = 8, scale = 2)
    private BigDecimal changeRate;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
