package com.myplatform.backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시장 투자자별 매매동향 일별 기록
 */
@Entity
@Table(name = "market_investor_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"market_type", "trade_date"}))
public class MarketInvestorHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType;  // KOSPI, KOSDAQ

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    // 순매수 금액 (억원)
    @Column(name = "foreign_net_buy", precision = 15, scale = 2)
    private BigDecimal foreignNetBuy;

    @Column(name = "institution_net_buy", precision = 15, scale = 2)
    private BigDecimal institutionNetBuy;

    @Column(name = "individual_net_buy", precision = 15, scale = 2)
    private BigDecimal individualNetBuy;

    @Column(name = "pension_net_buy", precision = 15, scale = 2)
    private BigDecimal pensionNetBuy;

    @Column(name = "invest_trust_net_buy", precision = 15, scale = 2)
    private BigDecimal investTrustNetBuy;

    // 매수/매도 금액 (억원)
    @Column(name = "foreign_buy", precision = 15, scale = 2)
    private BigDecimal foreignBuy;

    @Column(name = "foreign_sell", precision = 15, scale = 2)
    private BigDecimal foreignSell;

    @Column(name = "institution_buy", precision = 15, scale = 2)
    private BigDecimal institutionBuy;

    @Column(name = "institution_sell", precision = 15, scale = 2)
    private BigDecimal institutionSell;

    @Column(name = "individual_buy", precision = 15, scale = 2)
    private BigDecimal individualBuy;

    @Column(name = "individual_sell", precision = 15, scale = 2)
    private BigDecimal individualSell;

    // 지수 정보
    @Column(name = "index_value", precision = 10, scale = 2)
    private BigDecimal indexValue;

    @Column(name = "index_change", precision = 10, scale = 2)
    private BigDecimal indexChange;

    @Column(name = "index_change_rate", precision = 6, scale = 2)
    private BigDecimal indexChangeRate;

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

    public BigDecimal getForeignNetBuy() { return foreignNetBuy; }
    public void setForeignNetBuy(BigDecimal foreignNetBuy) { this.foreignNetBuy = foreignNetBuy; }

    public BigDecimal getInstitutionNetBuy() { return institutionNetBuy; }
    public void setInstitutionNetBuy(BigDecimal institutionNetBuy) { this.institutionNetBuy = institutionNetBuy; }

    public BigDecimal getIndividualNetBuy() { return individualNetBuy; }
    public void setIndividualNetBuy(BigDecimal individualNetBuy) { this.individualNetBuy = individualNetBuy; }

    public BigDecimal getPensionNetBuy() { return pensionNetBuy; }
    public void setPensionNetBuy(BigDecimal pensionNetBuy) { this.pensionNetBuy = pensionNetBuy; }

    public BigDecimal getInvestTrustNetBuy() { return investTrustNetBuy; }
    public void setInvestTrustNetBuy(BigDecimal investTrustNetBuy) { this.investTrustNetBuy = investTrustNetBuy; }

    public BigDecimal getForeignBuy() { return foreignBuy; }
    public void setForeignBuy(BigDecimal foreignBuy) { this.foreignBuy = foreignBuy; }

    public BigDecimal getForeignSell() { return foreignSell; }
    public void setForeignSell(BigDecimal foreignSell) { this.foreignSell = foreignSell; }

    public BigDecimal getInstitutionBuy() { return institutionBuy; }
    public void setInstitutionBuy(BigDecimal institutionBuy) { this.institutionBuy = institutionBuy; }

    public BigDecimal getInstitutionSell() { return institutionSell; }
    public void setInstitutionSell(BigDecimal institutionSell) { this.institutionSell = institutionSell; }

    public BigDecimal getIndividualBuy() { return individualBuy; }
    public void setIndividualBuy(BigDecimal individualBuy) { this.individualBuy = individualBuy; }

    public BigDecimal getIndividualSell() { return individualSell; }
    public void setIndividualSell(BigDecimal individualSell) { this.individualSell = individualSell; }

    public BigDecimal getIndexValue() { return indexValue; }
    public void setIndexValue(BigDecimal indexValue) { this.indexValue = indexValue; }

    public BigDecimal getIndexChange() { return indexChange; }
    public void setIndexChange(BigDecimal indexChange) { this.indexChange = indexChange; }

    public BigDecimal getIndexChangeRate() { return indexChangeRate; }
    public void setIndexChangeRate(BigDecimal indexChangeRate) { this.indexChangeRate = indexChangeRate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
