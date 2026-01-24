package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "사용자 활동 로그")
public class UserActivityLogDto {

    @Schema(description = "로그 ID")
    private Long id;

    @Schema(description = "사용자 ID")
    private Long userId;

    @Schema(description = "사용자명")
    private String username;

    @Schema(description = "활동 유형 (LOGIN, LOGOUT, CREATE_BOARD, etc)")
    private String activityType;

    @Schema(description = "활동 설명")
    private String description;

    @Schema(description = "IP 주소")
    private String ipAddress;

    @Schema(description = "User-Agent")
    private String userAgent;

    @Schema(description = "발생 시간")
    private LocalDateTime createdAt;

    // Constructors
    public UserActivityLogDto() {}

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

