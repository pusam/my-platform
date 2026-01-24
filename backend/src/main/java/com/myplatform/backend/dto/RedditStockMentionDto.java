package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Reddit 주식 언급 정보")
public class RedditStockMentionDto {

    @Schema(description = "종목 티커")
    private String ticker;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "언급 횟수")
    private Integer mentionCount;

    @Schema(description = "긍정적 언급")
    private Integer positiveCount;

    @Schema(description = "부정적 언급")
    private Integer negativeCount;

    @Schema(description = "중립적 언급")
    private Integer neutralCount;

    @Schema(description = "감성 점수 (-100 ~ 100)")
    private Double sentimentScore;

    @Schema(description = "시장 구분 (US, KR)")
    private String market;

    @Schema(description = "인기도 순위")
    private Integer rank;

    @Schema(description = "최근 업데이트 시간")
    private LocalDateTime lastUpdated;

    // Constructors
    public RedditStockMentionDto() {}

    // Getters and Setters
    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public Integer getMentionCount() {
        return mentionCount;
    }

    public void setMentionCount(Integer mentionCount) {
        this.mentionCount = mentionCount;
    }

    public Integer getPositiveCount() {
        return positiveCount;
    }

    public void setPositiveCount(Integer positiveCount) {
        this.positiveCount = positiveCount;
    }

    public Integer getNegativeCount() {
        return negativeCount;
    }

    public void setNegativeCount(Integer negativeCount) {
        this.negativeCount = negativeCount;
    }

    public Integer getNeutralCount() {
        return neutralCount;
    }

    public void setNeutralCount(Integer neutralCount) {
        this.neutralCount = neutralCount;
    }

    public Double getSentimentScore() {
        return sentimentScore;
    }

    public void setSentimentScore(Double sentimentScore) {
        this.sentimentScore = sentimentScore;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}


