package com.myplatform.backend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "weekly_trading_report")
public class WeeklyTradingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    @Column(nullable = false, length = 16)
    private String mode = "REAL";

    @Column(name = "total_buys", nullable = false)
    private int totalBuys;

    @Column(name = "total_sells", nullable = false)
    private int totalSells;

    @Column(name = "realized_pnl", nullable = false, precision = 15, scale = 2)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "win_count", nullable = false)
    private int winCount;

    @Column(name = "loss_count", nullable = false)
    private int lossCount;

    @Column(name = "total_buy_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalBuyAmount = BigDecimal.ZERO;

    @Column(name = "total_sell_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalSellAmount = BigDecimal.ZERO;

    @Column(name = "blocked_count", nullable = false)
    private int blockedCount;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "ai_report", columnDefinition = "TEXT")
    private String aiReport;

    @Column(name = "generated_by", nullable = false, length = 50)
    private String generatedBy = "system";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }
    public LocalDate getWeekEnd() { return weekEnd; }
    public void setWeekEnd(LocalDate weekEnd) { this.weekEnd = weekEnd; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getTotalBuys() { return totalBuys; }
    public void setTotalBuys(int totalBuys) { this.totalBuys = totalBuys; }
    public int getTotalSells() { return totalSells; }
    public void setTotalSells(int totalSells) { this.totalSells = totalSells; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public int getWinCount() { return winCount; }
    public void setWinCount(int winCount) { this.winCount = winCount; }
    public int getLossCount() { return lossCount; }
    public void setLossCount(int lossCount) { this.lossCount = lossCount; }
    public BigDecimal getTotalBuyAmount() { return totalBuyAmount; }
    public void setTotalBuyAmount(BigDecimal totalBuyAmount) { this.totalBuyAmount = totalBuyAmount; }
    public BigDecimal getTotalSellAmount() { return totalSellAmount; }
    public void setTotalSellAmount(BigDecimal totalSellAmount) { this.totalSellAmount = totalSellAmount; }
    public int getBlockedCount() { return blockedCount; }
    public void setBlockedCount(int blockedCount) { this.blockedCount = blockedCount; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getAiReport() { return aiReport; }
    public void setAiReport(String aiReport) { this.aiReport = aiReport; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
