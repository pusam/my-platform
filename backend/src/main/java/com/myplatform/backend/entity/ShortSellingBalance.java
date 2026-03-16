package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "short_selling_balance",
    indexes = {
        @Index(name = "idx_ssb_stock_date", columnList = "stock_code, trade_date"),
        @Index(name = "idx_ssb_ratio", columnList = "short_selling_ratio")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_ssb_stock_date", columnNames = {"stock_code", "trade_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortSellingBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", length = 20, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 공매도 잔고 수량 */
    @Column(name = "short_selling_volume", precision = 15, scale = 0)
    private BigDecimal shortSellingVolume;

    /** 공매도 잔고 금액 (억원) */
    @Column(name = "short_selling_amount", precision = 15, scale = 2)
    private BigDecimal shortSellingAmount;

    /** 공매도 비율 (%) */
    @Column(name = "short_selling_ratio", precision = 8, scale = 2)
    private BigDecimal shortSellingRatio;

    /** 상장주식수 */
    @Column(name = "listed_shares", precision = 15, scale = 0)
    private BigDecimal listedShares;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
