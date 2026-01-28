package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일자별 공매도 및 대차잔고 정보
 * - 공매도 거래량/잔고
 * - 대차잔고 (기관이 주식을 빌린 잔고)
 * - 숏커버링/숏스퀴즈 분석의 기초 데이터
 */
@Entity
@Table(name = "stock_short_data",
       indexes = {
           @Index(name = "idx_short_stock_date", columnList = "stockCode, tradeDate"),
           @Index(name = "idx_short_trade_date", columnList = "tradeDate")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_short_stock_date", columnNames = {"stockCode", "tradeDate"})
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockShortData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(length = 100)
    private String stockName;

    @Column(nullable = false)
    private LocalDate tradeDate;

    // ========== 공매도 거래 정보 ==========

    /** 공매도 거래량 (당일) */
    @Column(precision = 20, scale = 0)
    private BigDecimal shortVolume;

    /** 공매도 거래대금 (당일, 원) */
    @Column(precision = 20, scale = 0)
    private BigDecimal shortTradingValue;

    /** 전체 거래량 대비 공매도 비중 (%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal shortRatio;

    // ========== 공매도 잔고 정보 ==========

    /** 공매도 잔고 수량 (누적) */
    @Column(precision = 20, scale = 0)
    private BigDecimal shortBalanceQuantity;

    /** 공매도 잔고 금액 (누적, 원) */
    @Column(precision = 20, scale = 0)
    private BigDecimal shortBalanceValue;

    /** 상장주식수 대비 공매도 잔고 비율 (%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal shortBalanceRatio;

    // ========== 대차잔고 정보 (기관 대차) ==========

    /** 대차잔고 수량 (기관이 빌린 주식 수) */
    @Column(precision = 20, scale = 0)
    private BigDecimal loanBalanceQuantity;

    /** 대차잔고 금액 (원) */
    @Column(precision = 20, scale = 0)
    private BigDecimal loanBalanceValue;

    /** 상장주식수 대비 대차잔고 비율 (%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal loanBalanceRatio;

    // ========== 주가 정보 (분석용) ==========

    /** 종가 */
    @Column(precision = 15, scale = 2)
    private BigDecimal closePrice;

    /** 등락률 (%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal changeRate;

    /** 거래량 */
    @Column(precision = 20, scale = 0)
    private BigDecimal volume;

    // ========== 메타 정보 ==========

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
