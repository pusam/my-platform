package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "자산 요약 정보")
public class AssetSummaryDto {

    @Schema(description = "금 자산 정보")
    private AssetTypeInfo gold;

    @Schema(description = "은 자산 정보")
    private AssetTypeInfo silver;

    @Schema(description = "전체 자산 목록")
    private List<AssetDto> assets;

    public static class AssetTypeInfo {
        @Schema(description = "총 보유량 (그램)")
        private BigDecimal totalQuantity;

        @Schema(description = "평균 구매가 (그램당)")
        private BigDecimal averagePurchasePrice;

        @Schema(description = "총 투자금액")
        private BigDecimal totalInvestment;

        @Schema(description = "현재 그램당 시세")
        private BigDecimal currentPrice;

        @Schema(description = "현재 총 평가금액")
        private BigDecimal currentValue;

        @Schema(description = "손익금액")
        private BigDecimal profitLoss;

        @Schema(description = "수익률 (%)")
        private BigDecimal profitRate;

        // Getters and Setters
        public BigDecimal getTotalQuantity() {
            return totalQuantity;
        }

        public void setTotalQuantity(BigDecimal totalQuantity) {
            this.totalQuantity = totalQuantity;
        }

        public BigDecimal getAveragePurchasePrice() {
            return averagePurchasePrice;
        }

        public void setAveragePurchasePrice(BigDecimal averagePurchasePrice) {
            this.averagePurchasePrice = averagePurchasePrice;
        }

        public BigDecimal getTotalInvestment() {
            return totalInvestment;
        }

        public void setTotalInvestment(BigDecimal totalInvestment) {
            this.totalInvestment = totalInvestment;
        }

        public BigDecimal getCurrentPrice() {
            return currentPrice;
        }

        public void setCurrentPrice(BigDecimal currentPrice) {
            this.currentPrice = currentPrice;
        }

        public BigDecimal getCurrentValue() {
            return currentValue;
        }

        public void setCurrentValue(BigDecimal currentValue) {
            this.currentValue = currentValue;
        }

        public BigDecimal getProfitLoss() {
            return profitLoss;
        }

        public void setProfitLoss(BigDecimal profitLoss) {
            this.profitLoss = profitLoss;
        }

        public BigDecimal getProfitRate() {
            return profitRate;
        }

        public void setProfitRate(BigDecimal profitRate) {
            this.profitRate = profitRate;
        }
    }

    public AssetTypeInfo getGold() {
        return gold;
    }

    public void setGold(AssetTypeInfo gold) {
        this.gold = gold;
    }

    public AssetTypeInfo getSilver() {
        return silver;
    }

    public void setSilver(AssetTypeInfo silver) {
        this.silver = silver;
    }

    public List<AssetDto> getAssets() {
        return assets;
    }

    public void setAssets(List<AssetDto> assets) {
        this.assets = assets;
    }
}

