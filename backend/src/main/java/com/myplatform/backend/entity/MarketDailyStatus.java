package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시장 일일 현황 Entity
 * - 코스피/코스닥 시장별 상승/하락/보합 종목 수
 * - ADR (등락비율) 저장
 */
@Entity
@Table(name = "market_daily_status",
       indexes = {
           @Index(name = "idx_market_status_date", columnList = "tradeDate"),
           @Index(name = "idx_market_status_market_date", columnList = "marketType, tradeDate")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_market_status_date", columnNames = {"marketType", "tradeDate"})
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketDailyStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 시장 구분: KOSPI, KOSDAQ */
    @Column(nullable = false, length = 10)
    private String marketType;

    /** 거래일 */
    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 상승 종목 수 */
    @Column(nullable = false)
    private Integer advancingCount;

    /** 하락 종목 수 */
    @Column(nullable = false)
    private Integer decliningCount;

    /** 보합 종목 수 */
    @Column(nullable = false)
    private Integer unchangedCount;

    /** 상한가 종목 수 */
    private Integer upperLimitCount;

    /** 하한가 종목 수 */
    private Integer lowerLimitCount;

    /** 거래 종목 총 수 */
    private Integer totalCount;

    /** 거래대금 (억원) */
    @Column(precision = 20, scale = 0)
    private BigDecimal tradingValue;

    /** ADR 값 (20일 기준) */
    @Column(precision = 10, scale = 2)
    private BigDecimal adr20;

    /** 당일 등락비 (상승/하락) */
    @Column(precision = 10, scale = 2)
    private BigDecimal dailyRatio;

    /** 지수 종가 */
    @Column(precision = 15, scale = 2)
    private BigDecimal indexClose;

    /** 지수 등락률 (%) */
    @Column(precision = 10, scale = 2)
    private BigDecimal indexChangeRate;

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
