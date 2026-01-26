package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 투자자별 일별 상위 매수/매도 종목 기록
 * - 외국인, 기관, 연기금 등 투자자별 상위 20개 종목 저장
 */
@Entity
@Table(name = "investor_daily_trade",
       indexes = {
           @Index(name = "idx_investor_trade_date", columnList = "trade_date"),
           @Index(name = "idx_investor_trade_market", columnList = "market_type, trade_date"),
           @Index(name = "idx_investor_trade_type", columnList = "investor_type, trade_date")
       },
       uniqueConstraints = {
           @UniqueConstraint(
               name = "uk_investor_daily_trade",
               columnNames = {"trade_date", "market_type", "investor_type", "trade_type", "rank_num"}
           )
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorDailyTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType;  // KOSPI, KOSDAQ

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "investor_type", nullable = false, length = 20)
    private String investorType;  // FOREIGN, INSTITUTION, PENSION, INDIVIDUAL, INVEST_TRUST, etc.

    @Column(name = "trade_type", nullable = false, length = 10)
    private String tradeType;  // BUY, SELL

    @Column(name = "rank_num", nullable = false)
    private Integer rankNum;  // 순위 (1-20)

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "net_buy_amount", precision = 15, scale = 2)
    private BigDecimal netBuyAmount;  // 순매수 금액 (억원)

    @Column(name = "buy_amount", precision = 15, scale = 2)
    private BigDecimal buyAmount;  // 매수 금액 (억원)

    @Column(name = "sell_amount", precision = 15, scale = 2)
    private BigDecimal sellAmount;  // 매도 금액 (억원)

    @Column(name = "current_price", precision = 15, scale = 0)
    private BigDecimal currentPrice;  // 현재가

    @Column(name = "change_rate", precision = 8, scale = 2)
    private BigDecimal changeRate;  // 등락률 (%)

    @Column(name = "trade_volume")
    private Long tradeVolume;  // 거래량

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
