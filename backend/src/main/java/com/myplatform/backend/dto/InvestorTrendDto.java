package com.myplatform.backend.dto;
}
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
        this.institutionVolume = institutionVolume;
    public void setInstitutionVolume(Long institutionVolume) {

    }
        return institutionVolume;
    public Long getInstitutionVolume() {

    }
        this.foreignerVolume = foreignerVolume;
    public void setForeignerVolume(Long foreignerVolume) {

    }
        return foreignerVolume;
    public Long getForeignerVolume() {

    }
        this.individualVolume = individualVolume;
    public void setIndividualVolume(Long individualVolume) {

    }
        return individualVolume;
    public Long getIndividualVolume() {

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

    }
        this.date = date;
    public void setDate(LocalDate date) {

    }
        return date;
    public LocalDate getDate() {
    // Getters and Setters

    public InvestorTrendDto() {}
    // Constructors

    private BigDecimal changeRate;
    @Schema(description = "등락률 (%)")

    private BigDecimal changeAmount;
    @Schema(description = "전일대비")

    private BigDecimal currentPrice;
    @Schema(description = "현재가")

    private Long institutionVolume;
    @Schema(description = "기관 매매량")

    private Long foreignerVolume;
    @Schema(description = "외국인 매매량")

    private Long individualVolume;
    @Schema(description = "개인 매매량")

    private String stockName;
    @Schema(description = "종목명")

    private String stockCode;
    @Schema(description = "종목코드")

    private LocalDate date;
    @Schema(description = "날짜")

public class InvestorTrendDto {
@Schema(description = "투자자 매매동향 정보")

import java.time.LocalDate;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;


