package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class StockPriceDto {

    private String stockCode;
    private String stockName;
    private BigDecimal currentPrice;
    private BigDecimal changePrice;
    private BigDecimal changeRate;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal volume;
    private BigDecimal accumulatedTradingValue;  // 누적 거래대금 (API에서 직접 제공 시)
    private BigDecimal marketCap;
    private BigDecimal per;  // PER (주가수익비율)
    private BigDecimal pbr;  // PBR (주가순자산비율)
    private BigDecimal bps;  // BPS (주당순자산)
    private String baseDate;
    private LocalDateTime fetchedAt;
    private BigDecimal previousDayVolume;  // 전일 거래량
    private String dataSource; // 데이터 출처 (KIS: 한국투자증권, NAVER: 네이버)

    // Getters and Setters
    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public BigDecimal getChangePrice() {
        return changePrice;
    }

    public void setChangePrice(BigDecimal changePrice) {
        this.changePrice = changePrice;
    }

    public BigDecimal getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(BigDecimal changeRate) {
        this.changeRate = changeRate;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }

    public BigDecimal getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(BigDecimal highPrice) {
        this.highPrice = highPrice;
    }

    public BigDecimal getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(BigDecimal lowPrice) {
        this.lowPrice = lowPrice;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public BigDecimal getAccumulatedTradingValue() {
        return accumulatedTradingValue;
    }

    public void setAccumulatedTradingValue(BigDecimal accumulatedTradingValue) {
        this.accumulatedTradingValue = accumulatedTradingValue;
    }

    public BigDecimal getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(BigDecimal marketCap) {
        this.marketCap = marketCap;
    }

    public BigDecimal getPer() {
        return per;
    }

    public void setPer(BigDecimal per) {
        this.per = per;
    }

    public BigDecimal getPbr() {
        return pbr;
    }

    public void setPbr(BigDecimal pbr) {
        this.pbr = pbr;
    }

    public BigDecimal getBps() {
        return bps;
    }

    public void setBps(BigDecimal bps) {
        this.bps = bps;
    }

    public String getBaseDate() {
        return baseDate;
    }

    public void setBaseDate(String baseDate) {
        this.baseDate = baseDate;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public BigDecimal getPreviousDayVolume() {
        return previousDayVolume;
    }

    public void setPreviousDayVolume(BigDecimal previousDayVolume) {
        this.previousDayVolume = previousDayVolume;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
}
