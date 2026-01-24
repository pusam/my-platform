package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "수급 급등 종목 정보")
public class SupplySurgeStockDto {

    @Schema(description = "종목코드")
    private String stockCode;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "현재가")
    private BigDecimal currentPrice;

    @Schema(description = "전일대비")
    private BigDecimal changeAmount;

    @Schema(description = "등락률 (%)")
    private BigDecimal changeRate;

    @Schema(description = "거래량")
    private Long volume;

    @Schema(description = "거래량 증가율 (%)")
    private BigDecimal volumeChangeRate;

    @Schema(description = "외국인 순매수량")
    private Long foreignerNetBuy;

    @Schema(description = "기관 순매수량")
    private Long institutionNetBuy;

    @Schema(description = "시가총액 (억원)")
    private BigDecimal marketCap;

    // Constructors
    public SupplySurgeStockDto() {}

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

    public BigDecimal getChangeAmount() {
        return changeAmount;
    }

    public void setChangeAmount(BigDecimal changeAmount) {
        this.changeAmount = changeAmount;
    }

    public BigDecimal getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(BigDecimal changeRate) {
        this.changeRate = changeRate;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public BigDecimal getVolumeChangeRate() {
        return volumeChangeRate;
    }

    public void setVolumeChangeRate(BigDecimal volumeChangeRate) {
        this.volumeChangeRate = volumeChangeRate;
    }

    public Long getForeignerNetBuy() {
        return foreignerNetBuy;
    }

    public void setForeignerNetBuy(Long foreignerNetBuy) {
        this.foreignerNetBuy = foreignerNetBuy;
    }

    public Long getInstitutionNetBuy() {
        return institutionNetBuy;
    }

    public void setInstitutionNetBuy(Long institutionNetBuy) {
        this.institutionNetBuy = institutionNetBuy;
    }

    public BigDecimal getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(BigDecimal marketCap) {
        this.marketCap = marketCap;
    }
}

