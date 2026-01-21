package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 섹터별 거래대금 응답 DTO
 */
public class SectorTradingDto {

    private String sectorCode;      // 섹터 코드
    private String sectorName;      // 섹터 이름
    private String color;           // 섹터 색상
    private BigDecimal totalTradingValue;   // 총 거래대금 (원)
    private BigDecimal percentage;  // 전체 대비 비율 (%)
    private int stockCount;         // 종목 수
    private List<StockTradingInfo> topStocks; // 상위 거래 종목

    /**
     * 개별 종목 거래 정보
     */
    public static class StockTradingInfo {
        private String stockCode;
        private String stockName;
        private BigDecimal currentPrice;
        private BigDecimal changeRate;
        private BigDecimal tradingValue;  // 거래대금

        public StockTradingInfo() {}

        public StockTradingInfo(String stockCode, String stockName, BigDecimal currentPrice,
                                BigDecimal changeRate, BigDecimal tradingValue) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.currentPrice = currentPrice;
            this.changeRate = changeRate;
            this.tradingValue = tradingValue;
        }

        public String getStockCode() { return stockCode; }
        public void setStockCode(String stockCode) { this.stockCode = stockCode; }

        public String getStockName() { return stockName; }
        public void setStockName(String stockName) { this.stockName = stockName; }

        public BigDecimal getCurrentPrice() { return currentPrice; }
        public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

        public BigDecimal getChangeRate() { return changeRate; }
        public void setChangeRate(BigDecimal changeRate) { this.changeRate = changeRate; }

        public BigDecimal getTradingValue() { return tradingValue; }
        public void setTradingValue(BigDecimal tradingValue) { this.tradingValue = tradingValue; }
    }

    // Getters and Setters
    public String getSectorCode() { return sectorCode; }
    public void setSectorCode(String sectorCode) { this.sectorCode = sectorCode; }

    public String getSectorName() { return sectorName; }
    public void setSectorName(String sectorName) { this.sectorName = sectorName; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public BigDecimal getTotalTradingValue() { return totalTradingValue; }
    public void setTotalTradingValue(BigDecimal totalTradingValue) { this.totalTradingValue = totalTradingValue; }

    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }

    public int getStockCount() { return stockCount; }
    public void setStockCount(int stockCount) { this.stockCount = stockCount; }

    public List<StockTradingInfo> getTopStocks() { return topStocks; }
    public void setTopStocks(List<StockTradingInfo> topStocks) { this.topStocks = topStocks; }
}
