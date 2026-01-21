package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 지수 vs 종목 오버레이 차트 DTO
 */
public class ChartCompareDto {

    // 종목 정보
    private String stockCode;
    private String stockName;
    private BigDecimal stockPrice;
    private BigDecimal stockChangeRate;

    // 지수 정보
    private String indexCode;
    private String indexName;
    private BigDecimal indexPrice;
    private BigDecimal indexChangeRate;

    // 차트 데이터 (시초가 대비 등락률 기준)
    private List<ChartPoint> chartData;

    // 분석 결과
    private String analysis;           // STRONG, WEAK, NEUTRAL
    private String analysisReason;
    private BigDecimal relativeStrength; // 상대강도 (종목등락률 - 지수등락률)

    private LocalDateTime fetchedAt;

    /**
     * 차트 포인트 (분봉 기준)
     */
    public static class ChartPoint {
        private String time;           // HH:mm
        private BigDecimal indexRate;  // 지수 시초가 대비 등락률
        private BigDecimal stockRate;  // 종목 시초가 대비 등락률
        private BigDecimal gap;        // 차이 (종목 - 지수)

        public ChartPoint() {}

        public ChartPoint(String time, BigDecimal indexRate, BigDecimal stockRate) {
            this.time = time;
            this.indexRate = indexRate;
            this.stockRate = stockRate;
            this.gap = stockRate != null && indexRate != null
                    ? stockRate.subtract(indexRate)
                    : BigDecimal.ZERO;
        }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }

        public BigDecimal getIndexRate() { return indexRate; }
        public void setIndexRate(BigDecimal indexRate) { this.indexRate = indexRate; }

        public BigDecimal getStockRate() { return stockRate; }
        public void setStockRate(BigDecimal stockRate) { this.stockRate = stockRate; }

        public BigDecimal getGap() { return gap; }
        public void setGap(BigDecimal gap) { this.gap = gap; }
    }

    // Getters and Setters
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public BigDecimal getStockPrice() { return stockPrice; }
    public void setStockPrice(BigDecimal stockPrice) { this.stockPrice = stockPrice; }

    public BigDecimal getStockChangeRate() { return stockChangeRate; }
    public void setStockChangeRate(BigDecimal stockChangeRate) { this.stockChangeRate = stockChangeRate; }

    public String getIndexCode() { return indexCode; }
    public void setIndexCode(String indexCode) { this.indexCode = indexCode; }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public BigDecimal getIndexPrice() { return indexPrice; }
    public void setIndexPrice(BigDecimal indexPrice) { this.indexPrice = indexPrice; }

    public BigDecimal getIndexChangeRate() { return indexChangeRate; }
    public void setIndexChangeRate(BigDecimal indexChangeRate) { this.indexChangeRate = indexChangeRate; }

    public List<ChartPoint> getChartData() { return chartData; }
    public void setChartData(List<ChartPoint> chartData) { this.chartData = chartData; }

    public String getAnalysis() { return analysis; }
    public void setAnalysis(String analysis) { this.analysis = analysis; }

    public String getAnalysisReason() { return analysisReason; }
    public void setAnalysisReason(String analysisReason) { this.analysisReason = analysisReason; }

    public BigDecimal getRelativeStrength() { return relativeStrength; }
    public void setRelativeStrength(BigDecimal relativeStrength) { this.relativeStrength = relativeStrength; }

    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
}
