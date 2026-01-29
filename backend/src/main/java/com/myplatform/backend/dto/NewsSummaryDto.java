package com.myplatform.backend.dto;

import com.myplatform.backend.entity.NewsSummary;

import java.time.LocalDateTime;

public class NewsSummaryDto {
    private Long id;
    private String title;
    private String summary;
    private String sourceName;
    private String sourceUrl;
    private LocalDateTime publishedAt;
    private LocalDateTime summarizedAt;
    private String sentiment;
    private String sentimentLabel; // 한글 라벨

    public static NewsSummaryDto fromEntity(NewsSummary entity) {
        NewsSummaryDto dto = new NewsSummaryDto();
        dto.setId(entity.getId());
        dto.setTitle(entity.getTitle());
        dto.setSummary(entity.getSummary());
        dto.setSourceName(entity.getSourceName());
        dto.setSourceUrl(entity.getSourceUrl());
        dto.setPublishedAt(entity.getPublishedAt());
        dto.setSummarizedAt(entity.getSummarizedAt());
        dto.setSentiment(entity.getSentiment());
        dto.setSentimentLabel(getSentimentLabel(entity.getSentiment()));
        return dto;
    }

    private static String getSentimentLabel(String sentiment) {
        if (sentiment == null) return null;
        return switch (sentiment) {
            case "POSITIVE" -> "긍정";
            case "NEGATIVE" -> "부정";
            case "NEUTRAL" -> "중립";
            default -> sentiment;
        };
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getSummarizedAt() {
        return summarizedAt;
    }

    public void setSummarizedAt(LocalDateTime summarizedAt) {
        this.summarizedAt = summarizedAt;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public String getSentimentLabel() {
        return sentimentLabel;
    }

    public void setSentimentLabel(String sentimentLabel) {
        this.sentimentLabel = sentimentLabel;
    }
}
