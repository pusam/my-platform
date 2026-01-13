package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * GoldAPI.io 응답 DTO
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoldApiResponse {

    private Long timestamp;

    @JsonProperty("open_price")
    private BigDecimal openPrice;

    @JsonProperty("low_price")
    private BigDecimal lowPrice;

    @JsonProperty("high_price")
    private BigDecimal highPrice;

    private BigDecimal chp;  // 변동률 (%)

    @JsonProperty("price_gram_24k")
    private BigDecimal priceGram24k;

    private String error;

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }

    public BigDecimal getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(BigDecimal lowPrice) {
        this.lowPrice = lowPrice;
    }

    public BigDecimal getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(BigDecimal highPrice) {
        this.highPrice = highPrice;
    }

    public BigDecimal getChp() {
        return chp;
    }

    public void setChp(BigDecimal chp) {
        this.chp = chp;
    }

    public BigDecimal getPriceGram24k() {
        return priceGram24k;
    }

    public void setPriceGram24k(BigDecimal priceGram24k) {
        this.priceGram24k = priceGram24k;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
