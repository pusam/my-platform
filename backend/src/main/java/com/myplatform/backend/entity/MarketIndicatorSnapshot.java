package com.myplatform.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시장 지표 스냅샷 Entity
 * - 52주 신고가/신저가, 시총 순위, 거래대금 순위 등을 일별로 저장
 */
@Entity
@Table(name = "market_indicator_snapshot", indexes = {
    @Index(name = "idx_indicator_date", columnList = "indicatorType, snapshotDate"),
    @Index(name = "idx_snapshot_date", columnList = "snapshotDate")
})
public class MarketIndicatorSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String indicatorType;  // 52W_HIGH, 52W_LOW, MARKET_CAP_HIGH, TRADING_VALUE, PRICE_RISE, PRICE_FALL

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String dataJson;  // JSON 형태로 List<MarketIndicatorStockDto> 저장

    private int stockCount;  // 저장된 종목 수

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIndicatorType() {
        return indicatorType;
    }

    public void setIndicatorType(String indicatorType) {
        this.indicatorType = indicatorType;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public void setSnapshotDate(LocalDate snapshotDate) {
        this.snapshotDate = snapshotDate;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String dataJson) {
        this.dataJson = dataJson;
    }

    public int getStockCount() {
        return stockCount;
    }

    public void setStockCount(int stockCount) {
        this.stockCount = stockCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
