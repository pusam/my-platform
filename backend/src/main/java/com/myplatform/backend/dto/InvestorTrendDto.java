package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "투자자 매매동향 종목 정보")
public class InvestorTrendDto {

    @Schema(description = "조회 일자")
    private LocalDate date;

    @Schema(description = "종목 코드")
    private String stockCode;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "개인 순매수량")
    private Long individualVolume;

    @Schema(description = "외국인 순매수량")
    private Long foreignerVolume;

    @Schema(description = "기관 순매수량")
    private Long institutionVolume;

    @Schema(description = "현재가")
    private BigDecimal currentPrice;

    @Schema(description = "전일 대비 금액")
    private BigDecimal changeAmount;

    @Schema(description = "전일 대비율 (%)")
    private BigDecimal changeRate;

    // Constructors
    public InvestorTrendDto() {}

    // Getters and Setters
    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

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

    public Long getIndividualVolume() {
        return individualVolume;
    }

    public void setIndividualVolume(Long individualVolume) {
        this.individualVolume = individualVolume;
    }

    public Long getForeignerVolume() {
        return foreignerVolume;
    }

    public void setForeignerVolume(Long foreignerVolume) {
        this.foreignerVolume = foreignerVolume;
    }

    public Long getInstitutionVolume() {
        return institutionVolume;
    }

    public void setInstitutionVolume(Long institutionVolume) {
        this.institutionVolume = institutionVolume;
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
}

