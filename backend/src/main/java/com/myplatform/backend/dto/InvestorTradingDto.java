package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 투자자별 매매동향 DTO
 * - 외국인, 기관, 개인 순매수 현황
 * - 프로그램 매매 동향
 */
public class InvestorTradingDto {

    private String stockCode;
    private String stockName;
    private BigDecimal currentPrice;
    private BigDecimal changeRate;

    // 순매수 금액 (억원)
    private BigDecimal foreignNetBuy;      // 외국인 순매수
    private BigDecimal institutionNetBuy;  // 기관 순매수
    private BigDecimal individualNetBuy;   // 개인 순매수
    private BigDecimal programNetBuy;      // 프로그램 순매수

    // 순매수 수량
    private Long foreignNetVolume;
    private Long institutionNetVolume;
    private Long individualNetVolume;
    private Long programNetVolume;

    // 누적 데이터 (시간대별)
    private List<TimeSeriesData> timeSeries;

    // 수급 신호
    private String signal;         // BUY_SIGNAL, SELL_SIGNAL, NEUTRAL
    private String signalReason;   // 신호 설명

    private LocalDateTime fetchedAt;

    /**
     * 시계열 데이터 (시간대별 누적)
     */
    public static class TimeSeriesData {
        private String time;
        private BigDecimal foreignAccum;      // 외국인 누적
        private BigDecimal institutionAccum;  // 기관 누적
        private BigDecimal programAccum;      // 프로그램 누적
        private BigDecimal price;             // 해당 시점 가격

        public TimeSeriesData() {}

        public TimeSeriesData(String time, BigDecimal foreignAccum, BigDecimal institutionAccum,
                              BigDecimal programAccum, BigDecimal price) {
            this.time = time;
            this.foreignAccum = foreignAccum;
            this.institutionAccum = institutionAccum;
            this.programAccum = programAccum;
            this.price = price;
        }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }

        public BigDecimal getForeignAccum() { return foreignAccum; }
        public void setForeignAccum(BigDecimal foreignAccum) { this.foreignAccum = foreignAccum; }

        public BigDecimal getInstitutionAccum() { return institutionAccum; }
        public void setInstitutionAccum(BigDecimal institutionAccum) { this.institutionAccum = institutionAccum; }

        public BigDecimal getProgramAccum() { return programAccum; }
        public void setProgramAccum(BigDecimal programAccum) { this.programAccum = programAccum; }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
    }

    // Getters and Setters
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public BigDecimal getChangeRate() { return changeRate; }
    public void setChangeRate(BigDecimal changeRate) { this.changeRate = changeRate; }

    public BigDecimal getForeignNetBuy() { return foreignNetBuy; }
    public void setForeignNetBuy(BigDecimal foreignNetBuy) { this.foreignNetBuy = foreignNetBuy; }

    public BigDecimal getInstitutionNetBuy() { return institutionNetBuy; }
    public void setInstitutionNetBuy(BigDecimal institutionNetBuy) { this.institutionNetBuy = institutionNetBuy; }

    public BigDecimal getIndividualNetBuy() { return individualNetBuy; }
    public void setIndividualNetBuy(BigDecimal individualNetBuy) { this.individualNetBuy = individualNetBuy; }

    public BigDecimal getProgramNetBuy() { return programNetBuy; }
    public void setProgramNetBuy(BigDecimal programNetBuy) { this.programNetBuy = programNetBuy; }

    public Long getForeignNetVolume() { return foreignNetVolume; }
    public void setForeignNetVolume(Long foreignNetVolume) { this.foreignNetVolume = foreignNetVolume; }

    public Long getInstitutionNetVolume() { return institutionNetVolume; }
    public void setInstitutionNetVolume(Long institutionNetVolume) { this.institutionNetVolume = institutionNetVolume; }

    public Long getIndividualNetVolume() { return individualNetVolume; }
    public void setIndividualNetVolume(Long individualNetVolume) { this.individualNetVolume = individualNetVolume; }

    public Long getProgramNetVolume() { return programNetVolume; }
    public void setProgramNetVolume(Long programNetVolume) { this.programNetVolume = programNetVolume; }

    public List<TimeSeriesData> getTimeSeries() { return timeSeries; }
    public void setTimeSeries(List<TimeSeriesData> timeSeries) { this.timeSeries = timeSeries; }

    public String getSignal() { return signal; }
    public void setSignal(String signal) { this.signal = signal; }

    public String getSignalReason() { return signalReason; }
    public void setSignalReason(String signalReason) { this.signalReason = signalReason; }

    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
}
