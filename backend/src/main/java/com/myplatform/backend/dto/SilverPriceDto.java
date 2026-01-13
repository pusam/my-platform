package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SilverPriceDto {
    private BigDecimal pricePerGram;       // 1g 가격
    private BigDecimal pricePerDon;        // 1돈 가격 (3.75g)
    private BigDecimal openPrice;          // 시가
    private BigDecimal highPrice;          // 고가
    private BigDecimal lowPrice;           // 저가
    private BigDecimal closePrice;         // 종가
    private BigDecimal changeRate;         // 등락률
    private String baseDate;               // 기준일
    private LocalDateTime baseDateTime;    // 은 시세 기준 시간
    private LocalDateTime fetchedAt;       // 데이터 조회 시간

    public BigDecimal getPricePerGram() {
        return pricePerGram;
    }

    public void setPricePerGram(BigDecimal pricePerGram) {
        this.pricePerGram = pricePerGram;
    }

    public BigDecimal getPricePerDon() {
        return pricePerDon;
    }

    public void setPricePerDon(BigDecimal pricePerDon) {
        this.pricePerDon = pricePerDon;
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

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(BigDecimal changeRate) {
        this.changeRate = changeRate;
    }

    public String getBaseDate() {
        return baseDate;
    }

    public void setBaseDate(String baseDate) {
        this.baseDate = baseDate;
    }

    public LocalDateTime getBaseDateTime() {
        return baseDateTime;
    }

    public void setBaseDateTime(LocalDateTime baseDateTime) {
        this.baseDateTime = baseDateTime;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
