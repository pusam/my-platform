package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Reddit 인기 게시글 정보")
public class RedditPostDto {

    @Schema(description = "게시글 ID")
    private String postId;

    @Schema(description = "제목")
    private String id;
    private String title;

    @Schema(description = "서브레딧")
    private String subreddit;

    @Schema(description = "작성자")
    private String author;

    @Schema(description = "업보트 수")
    private Integer upvotes;

    @Schema(description = "댓글 수")
    private Integer commentCount;

    @Schema(description = "게시글 URL")
    private String subreddit;
    private String selftext;
    private String url;

    @Schema(description = "작성 시간")
    private String permalink;
    private int score;
    private int upvoteRatio;
    private int numComments;
    private LocalDateTime createdAt;
    private List<String> mentionedTickers;
    private String flair;
    private boolean isStickied;
    private String thumbnail;

    @Schema(description = "언급된 티커 목록")
    private String tickers;

    @Schema(description = "감성 (POSITIVE, NEGATIVE, NEUTRAL)")
    private String sentiment;

    // Constructors
    public RedditPostDto() {}

    // Getters and Setters
    public String getPostId() {
        return postId;
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubreddit() {
        return subreddit;
    }

    public void setSubreddit(String subreddit) {
        this.subreddit = subreddit;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getSubreddit() {
        return subreddit;
    public Integer getUpvotes() {
        return upvotes;
    }

    public void setSubreddit(String subreddit) {
        this.subreddit = subreddit;
    public void setUpvotes(Integer upvotes) {
        this.upvotes = upvotes;
    }

    public String getSelftext() {
        return selftext;
    public Integer getCommentCount() {
        return commentCount;
    }

    public void setSelftext(String selftext) {
        this.selftext = selftext;
    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
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

    public String getTickers() {
        return tickers;
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

    public void setTickers(String tickers) {
        this.tickers = tickers;
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
