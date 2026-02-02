package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주식 일봉 가격 히스토리
 * - 기술적 분석(RSI, 이평선 등)에 사용
 * - KIS API에서 조회 후 DB에 캐싱
 */
@Entity
@Table(name = "stock_price_history",
       indexes = {
           @Index(name = "idx_price_history_stock", columnList = "stockCode, tradeDate DESC"),
           @Index(name = "idx_price_history_date", columnList = "tradeDate")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_price_history", columnNames = {"stockCode", "tradeDate"})
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(length = 100)
    private String stockName;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 시가 */
    @Column(precision = 15, scale = 2)
    private BigDecimal openPrice;

    /** 고가 */
    @Column(precision = 15, scale = 2)
    private BigDecimal highPrice;

    /** 저가 */
    @Column(precision = 15, scale = 2)
    private BigDecimal lowPrice;

    /** 종가 */
    @Column(precision = 15, scale = 2)
    private BigDecimal closePrice;

    /** 거래량 */
    @Column(precision = 20, scale = 0)
    private BigDecimal volume;

    /** 등락률 (%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal changeRate;

    /** 데이터 소스 (KIS, NAVER 등) */
    @Column(length = 20)
    private String dataSource;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
