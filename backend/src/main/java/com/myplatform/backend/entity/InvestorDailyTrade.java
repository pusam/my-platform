package com.myplatform.backend.entity;

import jakarta.persistence.*;
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
           @Index(name = "idx_investor_trade_date", columnNames = {"trade_date"}),
           @Index(name = "idx_investor_trade_market", columnNames = {"market_type", "trade_date"}),
           @Index(name = "idx_investor_trade_type", columnNames = {"investor_type", "trade_date"})
       })
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

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public String getInvestorType() { return investorType; }
    public void setInvestorType(String investorType) { this.investorType = investorType; }

    public String getTradeType() { return tradeType; }
    public void setTradeType(String tradeType) { this.tradeType = tradeType; }

    public Integer getRankNum() { return rankNum; }
    public void setRankNum(Integer rankNum) { this.rankNum = rankNum; }

    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public BigDecimal getNetBuyAmount() { return netBuyAmount; }
    public void setNetBuyAmount(BigDecimal netBuyAmount) { this.netBuyAmount = netBuyAmount; }

    public BigDecimal getBuyAmount() { return buyAmount; }
    public void setBuyAmount(BigDecimal buyAmount) { this.buyAmount = buyAmount; }

    public BigDecimal getSellAmount() { return sellAmount; }
    public void setSellAmount(BigDecimal sellAmount) { this.sellAmount = sellAmount; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public BigDecimal getChangeRate() { return changeRate; }
    public void setChangeRate(BigDecimal changeRate) { this.changeRate = changeRate; }

    public Long getTradeVolume() { return tradeVolume; }
    public void setTradeVolume(Long tradeVolume) { this.tradeVolume = tradeVolume; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
