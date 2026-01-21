package com.myplatform.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

public class RedditPostDto {
    private String id;
    private String title;
    private String author;
    private String subreddit;
    private String selftext;
    private String url;
    private String permalink;
    private int score;
    private int upvoteRatio;
    private int numComments;
    private LocalDateTime createdAt;
    private List<String> mentionedTickers;
    private String flair;
    private boolean isStickied;
    private String thumbnail;

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getSubreddit() {
        return subreddit;
    }

    public void setSubreddit(String subreddit) {
        this.subreddit = subreddit;
    }

    public String getSelftext() {
        return selftext;
    }

    public void setSelftext(String selftext) {
        this.selftext = selftext;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPermalink() {
        return permalink;
    }

    public void setPermalink(String permalink) {
        this.permalink = permalink;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getUpvoteRatio() {
        return upvoteRatio;
    }

    public void setUpvoteRatio(int upvoteRatio) {
        this.upvoteRatio = upvoteRatio;
    }

    public int getNumComments() {
        return numComments;
    }

    public void setNumComments(int numComments) {
        this.numComments = numComments;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getMentionedTickers() {
        return mentionedTickers;
    }

    public void setMentionedTickers(List<String> mentionedTickers) {
        this.mentionedTickers = mentionedTickers;
    }

    public String getFlair() {
        return flair;
    }

    public void setFlair(String flair) {
        this.flair = flair;
    }

    public boolean isStickied() {
        return isStickied;
    }

    public void setStickied(boolean stickied) {
        isStickied = stickied;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(String thumbnail) {
        this.thumbnail = thumbnail;
    }

    // Inner class for trending ticker stats
    public static class TrendingTicker {
        private String ticker;
        private int mentionCount;
        private String sentiment;
        private double sentimentScore;

        public TrendingTicker() {}

        public TrendingTicker(String ticker, int mentionCount) {
            this.ticker = ticker;
            this.mentionCount = mentionCount;
        }

        public String getTicker() {
            return ticker;
        }

        public void setTicker(String ticker) {
            this.ticker = ticker;
        }

        public int getMentionCount() {
            return mentionCount;
        }

        public void setMentionCount(int mentionCount) {
            this.mentionCount = mentionCount;
        }

        public String getSentiment() {
            return sentiment;
        }

        public void setSentiment(String sentiment) {
            this.sentiment = sentiment;
        }

        public double getSentimentScore() {
            return sentimentScore;
        }

        public void setSentimentScore(double sentimentScore) {
            this.sentimentScore = sentimentScore;
        }
    }
}
