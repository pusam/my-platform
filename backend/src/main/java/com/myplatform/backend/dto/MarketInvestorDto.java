package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 시장 전체 투자자별 매매동향 DTO
 */
public class MarketInvestorDto {

    private String marketType;          // KOSPI, KOSDAQ
    private String marketName;          // 코스피, 코스닥
    private LocalDateTime fetchedAt;    // 조회 시간

    // 당일 순매수 금액 (억원)
    private BigDecimal foreignNetBuy;       // 외국인
    private BigDecimal institutionNetBuy;   // 기관
    private BigDecimal individualNetBuy;    // 개인
    private BigDecimal pensionNetBuy;       // 연기금
    private BigDecimal investTrustNetBuy;   // 투자신탁
    private BigDecimal bankNetBuy;          // 은행
    private BigDecimal insuranceNetBuy;     // 보험
    private BigDecimal otherFinanceNetBuy;  // 기타금융
    private BigDecimal otherCorpNetBuy;     // 기타법인

    // 매수/매도 금액 (억원)
    private BigDecimal foreignBuy;
    private BigDecimal foreignSell;
    private BigDecimal institutionBuy;
    private BigDecimal institutionSell;
    private BigDecimal individualBuy;
    private BigDecimal individualSell;

    // 비율 (%)
    private BigDecimal foreignRatio;
    private BigDecimal institutionRatio;
    private BigDecimal individualRatio;

    // 지수 정보
    private BigDecimal indexValue;      // 현재 지수
    private BigDecimal indexChange;     // 전일 대비
    private BigDecimal indexChangeRate; // 등락률

    // 최근 N일 추이 데이터
    private List<DailyTrend> dailyTrends;

    /**
     * 일별 추이 데이터
     */
    public static class DailyTrend {
        private LocalDate date;
        private BigDecimal foreignNetBuy;
        private BigDecimal institutionNetBuy;
        private BigDecimal individualNetBuy;
        private BigDecimal indexValue;
        private BigDecimal indexChange;

        public DailyTrend() {}

        public DailyTrend(LocalDate date, BigDecimal foreignNetBuy, BigDecimal institutionNetBuy,
                          BigDecimal individualNetBuy, BigDecimal indexValue, BigDecimal indexChange) {
            this.date = date;
            this.foreignNetBuy = foreignNetBuy;
            this.institutionNetBuy = institutionNetBuy;
            this.individualNetBuy = individualNetBuy;
            this.indexValue = indexValue;
            this.indexChange = indexChange;
        }

        // Getters and Setters
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public BigDecimal getForeignNetBuy() { return foreignNetBuy; }
        public void setForeignNetBuy(BigDecimal foreignNetBuy) { this.foreignNetBuy = foreignNetBuy; }
        public BigDecimal getInstitutionNetBuy() { return institutionNetBuy; }
        public void setInstitutionNetBuy(BigDecimal institutionNetBuy) { this.institutionNetBuy = institutionNetBuy; }
        public BigDecimal getIndividualNetBuy() { return individualNetBuy; }
        public void setIndividualNetBuy(BigDecimal individualNetBuy) { this.individualNetBuy = individualNetBuy; }
        public BigDecimal getIndexValue() { return indexValue; }
        public void setIndexValue(BigDecimal indexValue) { this.indexValue = indexValue; }
        public BigDecimal getIndexChange() { return indexChange; }
        public void setIndexChange(BigDecimal indexChange) { this.indexChange = indexChange; }
    }

    // Getters and Setters
    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public String getMarketName() { return marketName; }
    public void setMarketName(String marketName) { this.marketName = marketName; }

    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }

    public BigDecimal getForeignNetBuy() { return foreignNetBuy; }
    public void setForeignNetBuy(BigDecimal foreignNetBuy) { this.foreignNetBuy = foreignNetBuy; }

    public BigDecimal getInstitutionNetBuy() { return institutionNetBuy; }
    public void setInstitutionNetBuy(BigDecimal institutionNetBuy) { this.institutionNetBuy = institutionNetBuy; }

    public BigDecimal getIndividualNetBuy() { return individualNetBuy; }
    public void setIndividualNetBuy(BigDecimal individualNetBuy) { this.individualNetBuy = individualNetBuy; }

    public BigDecimal getPensionNetBuy() { return pensionNetBuy; }
    public void setPensionNetBuy(BigDecimal pensionNetBuy) { this.pensionNetBuy = pensionNetBuy; }

    public BigDecimal getInvestTrustNetBuy() { return investTrustNetBuy; }
    public void setInvestTrustNetBuy(BigDecimal investTrustNetBuy) { this.investTrustNetBuy = investTrustNetBuy; }

    public BigDecimal getBankNetBuy() { return bankNetBuy; }
    public void setBankNetBuy(BigDecimal bankNetBuy) { this.bankNetBuy = bankNetBuy; }

    public BigDecimal getInsuranceNetBuy() { return insuranceNetBuy; }
    public void setInsuranceNetBuy(BigDecimal insuranceNetBuy) { this.insuranceNetBuy = insuranceNetBuy; }

    public BigDecimal getOtherFinanceNetBuy() { return otherFinanceNetBuy; }
    public void setOtherFinanceNetBuy(BigDecimal otherFinanceNetBuy) { this.otherFinanceNetBuy = otherFinanceNetBuy; }

    public BigDecimal getOtherCorpNetBuy() { return otherCorpNetBuy; }
    public void setOtherCorpNetBuy(BigDecimal otherCorpNetBuy) { this.otherCorpNetBuy = otherCorpNetBuy; }

    public BigDecimal getForeignBuy() { return foreignBuy; }
    public void setForeignBuy(BigDecimal foreignBuy) { this.foreignBuy = foreignBuy; }

    public BigDecimal getForeignSell() { return foreignSell; }
    public void setForeignSell(BigDecimal foreignSell) { this.foreignSell = foreignSell; }

    public BigDecimal getInstitutionBuy() { return institutionBuy; }
    public void setInstitutionBuy(BigDecimal institutionBuy) { this.institutionBuy = institutionBuy; }

    public BigDecimal getInstitutionSell() { return institutionSell; }
    public void setInstitutionSell(BigDecimal institutionSell) { this.institutionSell = institutionSell; }

    public BigDecimal getIndividualBuy() { return individualBuy; }
    public void setIndividualBuy(BigDecimal individualBuy) { this.individualBuy = individualBuy; }

    public BigDecimal getIndividualSell() { return individualSell; }
    public void setIndividualSell(BigDecimal individualSell) { this.individualSell = individualSell; }

    public BigDecimal getForeignRatio() { return foreignRatio; }
    public void setForeignRatio(BigDecimal foreignRatio) { this.foreignRatio = foreignRatio; }

    public BigDecimal getInstitutionRatio() { return institutionRatio; }
    public void setInstitutionRatio(BigDecimal institutionRatio) { this.institutionRatio = institutionRatio; }

    public BigDecimal getIndividualRatio() { return individualRatio; }
    public void setIndividualRatio(BigDecimal individualRatio) { this.individualRatio = individualRatio; }

    public BigDecimal getIndexValue() { return indexValue; }
    public void setIndexValue(BigDecimal indexValue) { this.indexValue = indexValue; }

    public BigDecimal getIndexChange() { return indexChange; }
    public void setIndexChange(BigDecimal indexChange) { this.indexChange = indexChange; }

    public BigDecimal getIndexChangeRate() { return indexChangeRate; }
    public void setIndexChangeRate(BigDecimal indexChangeRate) { this.indexChangeRate = indexChangeRate; }

    public List<DailyTrend> getDailyTrends() { return dailyTrends; }
    public void setDailyTrends(List<DailyTrend> dailyTrends) { this.dailyTrends = dailyTrends; }
}
