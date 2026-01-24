package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "연속 매수 종목 정보")
public class ContinuousBuyStockDto {

    @Schema(description = "종목코드")
    private String stockCode;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "연속 매수일수")
    private Integer continuousDays;

    @Schema(description = "현재가")
    private BigDecimal currentPrice;

    @Schema(description = "전일대비")
    private BigDecimal changeAmount;

    @Schema(description = "등락률 (%)")
    private BigDecimal changeRate;

    @Schema(description = "누적 매수량")
    private Long accumulatedVolume;

    @Schema(description = "거래량")
    private Long volume;

    // Constructors
    public ContinuousBuyStockDto() {}

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

    public Integer getContinuousDays() {
        return continuousDays;
    }

    public void setContinuousDays(Integer continuousDays) {
        this.continuousDays = continuousDays;
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

    public Long getAccumulatedVolume() {
        return accumulatedVolume;
    }

    public void setAccumulatedVolume(Long accumulatedVolume) {
        this.accumulatedVolume = accumulatedVolume;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }
}

