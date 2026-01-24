package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "시장 지표별 주식 정보")
public class MarketIndicatorStockDto {

    @Schema(description = "종목 코드")
    private String stockCode;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "현재가")
    private BigDecimal currentPrice;

    @Schema(description = "전일 대비 금액")
    private BigDecimal changeAmount;

    @Schema(description = "전일 대비율 (%)")
    private BigDecimal changeRate;

    @Schema(description = "시가")
    private BigDecimal openPrice;

    @Schema(description = "고가")
    private BigDecimal highPrice;

    @Schema(description = "저가")
    private BigDecimal lowPrice;

    @Schema(description = "거래량")
    private Long volume;

    @Schema(description = "거래대금")
    private BigDecimal tradingValue;

    @Schema(description = "시가총액")
    private BigDecimal marketCap;

    @Schema(description = "52주 최고가")
    private BigDecimal week52High;

    @Schema(description = "52주 최저가")
    private BigDecimal week52Low;

    @Schema(description = "52주 최고가 대비율 (%)")
    private BigDecimal week52HighRate;

    @Schema(description = "52주 최저가 대비율 (%)")
    private BigDecimal week52LowRate;

    @Schema(description = "PER (주가수익비율)")
    private BigDecimal per;

    @Schema(description = "PBR (주가순자산비율)")
    private BigDecimal pbr;

    @Schema(description = "배당수익률 (%)")
    private BigDecimal dividendYield;

    @Schema(description = "순위")
    private Integer rank;

    @Schema(description = "지표 타입 (예: HIGH_DIVIDEND, LOW_PBR 등)")
    private String indicatorType;

    // Constructors
    public MarketIndicatorStockDto() {}

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

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public BigDecimal getTradingValue() {
        return tradingValue;
    }

    public void setTradingValue(BigDecimal tradingValue) {
        this.tradingValue = tradingValue;
    }

    public BigDecimal getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(BigDecimal marketCap) {
        this.marketCap = marketCap;
    }

    public BigDecimal getWeek52High() {
        return week52High;
    }

    public void setWeek52High(BigDecimal week52High) {
        this.week52High = week52High;
    }

    public BigDecimal getWeek52Low() {
        return week52Low;
    }

    public void setWeek52Low(BigDecimal week52Low) {
        this.week52Low = week52Low;
    }

    public BigDecimal getWeek52HighRate() {
        return week52HighRate;
    }

    public void setWeek52HighRate(BigDecimal week52HighRate) {
        this.week52HighRate = week52HighRate;
    }

    public BigDecimal getWeek52LowRate() {
        return week52LowRate;
    }

    public void setWeek52LowRate(BigDecimal week52LowRate) {
        this.week52LowRate = week52LowRate;
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

    public BigDecimal getDividendYield() {
        return dividendYield;
    }

    public void setDividendYield(BigDecimal dividendYield) {
        this.dividendYield = dividendYield;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public String getIndicatorType() {
        return indicatorType;
    }

    public void setIndicatorType(String indicatorType) {
        this.indicatorType = indicatorType;
    }
}

