package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Reddit 인기 게시글 정보")
public class RedditPostDto {

    @Schema(description = "게시글 ID")
    private String postId;

    @Schema(description = "제목")
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
    private String url;

    @Schema(description = "작성 시간")
    private LocalDateTime createdAt;

    @Schema(description = "언급된 티커 목록")
    private String tickers;

    @Schema(description = "감성 (POSITIVE, NEGATIVE, NEUTRAL)")
    private String sentiment;

    // Constructors
    public RedditPostDto() {}

    // Getters and Setters
    public String getPostId() {
        return postId;
    }

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

    public Integer getUpvotes() {
        return upvotes;
    }

    public void setUpvotes(Integer upvotes) {
        this.upvotes = upvotes;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getTickers() {
        return tickers;
    }

    public void setTickers(String tickers) {
        this.tickers = tickers;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }
}

