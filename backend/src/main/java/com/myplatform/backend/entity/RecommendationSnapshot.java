package com.myplatform.backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_snapshot")
public class RecommendationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Column(name = "ai_strategy", nullable = false)
    private int aiStrategy;

    @Column(name = "earnings", nullable = false)
    private int earnings;

    @Column(name = "supply_demand", nullable = false)
    private int supplyDemand;

    @Column(name = "technical", nullable = false)
    private int technical;

    @Column(name = "sector_momentum", nullable = false)
    private int sectorMomentum;

    // ⚠ -1 = NA(데이터 없음) sentinel, 0+ = 산출됨(0~20). valueStability/growth 공통.
    //   주의: `score > 0` / `score >= 0` 식 필터를 짤 때 -1 을 "실제 음수 점수"로 오작동시키지 말 것
    //   (예: verdictFor 가 -1 을 NEGATIVE 로 오판했던 버그 — 작업6에서 score<0 가드로 수정).
    //   nullable(Integer) 전환은 마이그레이션 비용 커 보류 — VERIFICATION_BACKLOG 참조.
    @Column(name = "value_stability", nullable = false)
    private int valueStability;

    // 성장성 점수 (매출/이익 성장률 + PEG). -1 = 데이터 없음(NA), 0+ = 산출됨(0~20). 위 valueStability 주의사항 동일.
    // 기존 행은 V29 마이그레이션 default(-1)로 NA 처리 → UI 에서 "—" 노출.
    @Column(name = "growth", nullable = false)
    private int growth;

    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "change_rate", precision = 10, scale = 2)
    private BigDecimal changeRate;

    @Column(name = "rank_order", nullable = false)
    private int rankOrder;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public int getTotalScore() { return totalScore; }
    public void setTotalScore(int totalScore) { this.totalScore = totalScore; }

    public int getAiStrategy() { return aiStrategy; }
    public void setAiStrategy(int aiStrategy) { this.aiStrategy = aiStrategy; }

    public int getEarnings() { return earnings; }
    public void setEarnings(int earnings) { this.earnings = earnings; }

    public int getSupplyDemand() { return supplyDemand; }
    public void setSupplyDemand(int supplyDemand) { this.supplyDemand = supplyDemand; }

    public int getTechnical() { return technical; }
    public void setTechnical(int technical) { this.technical = technical; }

    public int getSectorMomentum() { return sectorMomentum; }
    public void setSectorMomentum(int sectorMomentum) { this.sectorMomentum = sectorMomentum; }
    public int getValueStability() { return valueStability; }
    public void setValueStability(int valueStability) { this.valueStability = valueStability; }

    public int getGrowth() { return growth; }
    public void setGrowth(int growth) { this.growth = growth; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public BigDecimal getChangeRate() { return changeRate; }
    public void setChangeRate(BigDecimal changeRate) { this.changeRate = changeRate; }

    public int getRankOrder() { return rankOrder; }
    public void setRankOrder(int rankOrder) { this.rankOrder = rankOrder; }

    public LocalDateTime getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(LocalDateTime snapshotAt) { this.snapshotAt = snapshotAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
