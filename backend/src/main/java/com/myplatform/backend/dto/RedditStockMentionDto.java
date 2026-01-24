package com.myplatform.backend.dto;
}
    }
        this.lastUpdated = lastUpdated;
    public void setLastUpdated(LocalDateTime lastUpdated) {

    }
        return lastUpdated;
    public LocalDateTime getLastUpdated() {

    }
        this.rank = rank;
    public void setRank(Integer rank) {

    }
        return rank;
    public Integer getRank() {

    }
        this.market = market;
    public void setMarket(String market) {

    }
        return market;
    public String getMarket() {

    }
        this.sentimentScore = sentimentScore;
    public void setSentimentScore(Double sentimentScore) {

    }
        return sentimentScore;
    public Double getSentimentScore() {

    }
        this.neutralCount = neutralCount;
    public void setNeutralCount(Integer neutralCount) {

    }
        return neutralCount;
    public Integer getNeutralCount() {

    }
        this.negativeCount = negativeCount;
    public void setNegativeCount(Integer negativeCount) {

    }
        return negativeCount;
    public Integer getNegativeCount() {

    }
        this.positiveCount = positiveCount;
    public void setPositiveCount(Integer positiveCount) {

    }
        return positiveCount;
    public Integer getPositiveCount() {

    }
        this.mentionCount = mentionCount;
    public void setMentionCount(Integer mentionCount) {

    }
        return mentionCount;
    public Integer getMentionCount() {

    }
        this.stockName = stockName;
    public void setStockName(String stockName) {

    }
        return stockName;
    public String getStockName() {

    }
        this.ticker = ticker;
    public void setTicker(String ticker) {

    }
        return ticker;
    public String getTicker() {
    // Getters and Setters

    public RedditStockMentionDto() {}
    // Constructors

    private LocalDateTime lastUpdated;
    @Schema(description = "최근 업데이트 시간")

    private Integer rank;
    @Schema(description = "인기도 순위")

    private String market;
    @Schema(description = "시장 구분 (US, KR)")

    private Double sentimentScore;
    @Schema(description = "감성 점수 (-100 ~ 100)")

    private Integer neutralCount;
    @Schema(description = "중립적 언급")

    private Integer negativeCount;
    @Schema(description = "부정적 언급")

    private Integer positiveCount;
    @Schema(description = "긍정적 언급")

    private Integer mentionCount;
    @Schema(description = "언급 횟수")

    private String stockName;
    @Schema(description = "종목명")

    private String ticker;
    @Schema(description = "종목 티커")

public class RedditStockMentionDto {
@Schema(description = "Reddit 주식 언급 정보")

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;


