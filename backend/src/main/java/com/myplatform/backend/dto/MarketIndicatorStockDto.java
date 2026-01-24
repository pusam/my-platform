package com.myplatform.backend.dto;
}
    }
        this.indicatorType = indicatorType;
    public void setIndicatorType(String indicatorType) {

    }
        return indicatorType;
    public String getIndicatorType() {

    }
        this.rank = rank;
    public void setRank(Integer rank) {

    }
        return rank;
    public Integer getRank() {

    }
        this.dividendYield = dividendYield;
    public void setDividendYield(BigDecimal dividendYield) {

    }
        return dividendYield;
    public BigDecimal getDividendYield() {

    }
        this.pbr = pbr;
    public void setPbr(BigDecimal pbr) {

    }
        return pbr;
    public BigDecimal getPbr() {

    }
        this.per = per;
    public void setPer(BigDecimal per) {

    }
        return per;
    public BigDecimal getPer() {

    }
        this.week52LowRate = week52LowRate;
    public void setWeek52LowRate(BigDecimal week52LowRate) {

    }
        return week52LowRate;
    public BigDecimal getWeek52LowRate() {

    }
        this.week52HighRate = week52HighRate;
    public void setWeek52HighRate(BigDecimal week52HighRate) {

    }
        return week52HighRate;
    public BigDecimal getWeek52HighRate() {

    }
        this.week52Low = week52Low;
    public void setWeek52Low(BigDecimal week52Low) {

    }
        return week52Low;
    public BigDecimal getWeek52Low() {

    }
        this.week52High = week52High;
    public void setWeek52High(BigDecimal week52High) {

    }
        return week52High;
    public BigDecimal getWeek52High() {

    }
        this.marketCap = marketCap;
    public void setMarketCap(BigDecimal marketCap) {

    }
        return marketCap;
    public BigDecimal getMarketCap() {

    }
        this.tradingValue = tradingValue;
    public void setTradingValue(BigDecimal tradingValue) {

    }
        return tradingValue;
    public BigDecimal getTradingValue() {

    }
        this.volume = volume;
    public void setVolume(Long volume) {

    }
        return volume;
    public Long getVolume() {

    }
        this.lowPrice = lowPrice;
    public void setLowPrice(BigDecimal lowPrice) {

    }
        return lowPrice;
    public BigDecimal getLowPrice() {

    }
        this.highPrice = highPrice;
    public void setHighPrice(BigDecimal highPrice) {

    }
        return highPrice;
    public BigDecimal getHighPrice() {

    }
        this.openPrice = openPrice;
    public void setOpenPrice(BigDecimal openPrice) {

    }
        return openPrice;
    public BigDecimal getOpenPrice() {

    }
        this.changeRate = changeRate;
    public void setChangeRate(BigDecimal changeRate) {

    }
        return changeRate;
    public BigDecimal getChangeRate() {

    }
        this.changeAmount = changeAmount;
    public void setChangeAmount(BigDecimal changeAmount) {

    }
        return changeAmount;
    public BigDecimal getChangeAmount() {

    }
        this.currentPrice = currentPrice;
    public void setCurrentPrice(BigDecimal currentPrice) {

    }
        return currentPrice;
    public BigDecimal getCurrentPrice() {

    }
        this.stockName = stockName;
    public void setStockName(String stockName) {

    }
        return stockName;
    public String getStockName() {

    }
        this.stockCode = stockCode;
    public void setStockCode(String stockCode) {

    }
        return stockCode;
    public String getStockCode() {
    // Getters and Setters

    public MarketIndicatorStockDto() {}
    // Constructors

    private String indicatorType;
    @Schema(description = "지표 타입 (52W_HIGH, 52W_LOW, MARKET_CAP_HIGH, TRADING_VALUE, etc)")

    private Integer rank;
    @Schema(description = "순위")

    private BigDecimal dividendYield;
    @Schema(description = "배당수익률 (%)")

    private BigDecimal pbr;
    @Schema(description = "PBR (주가순자산비율)")

    private BigDecimal per;
    @Schema(description = "PER (주가수익비율)")

    private BigDecimal week52LowRate;
    @Schema(description = "52주 최저가 대비율 (%)")

    private BigDecimal week52HighRate;
    @Schema(description = "52주 최고가 대비율 (%)")

    private BigDecimal week52Low;
    @Schema(description = "52주 최저가")

    private BigDecimal week52High;
    @Schema(description = "52주 최고가")

    private BigDecimal marketCap;
    @Schema(description = "시가총액 (억원)")

    private BigDecimal tradingValue;
    @Schema(description = "거래대금 (백만원)")

    private Long volume;
    @Schema(description = "거래량")

    private BigDecimal lowPrice;
    @Schema(description = "저가")

    private BigDecimal highPrice;
    @Schema(description = "고가")

    private BigDecimal openPrice;
    @Schema(description = "시가")

    private BigDecimal changeRate;
    @Schema(description = "등락률 (%)")

    private BigDecimal changeAmount;
    @Schema(description = "전일대비")

    private BigDecimal currentPrice;
    @Schema(description = "현재가")

    private String stockName;
    @Schema(description = "종목명")

    private String stockCode;
    @Schema(description = "종목코드")

public class MarketIndicatorStockDto {
@Schema(description = "시장 지표 종목 정보")

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;


