package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "시스템 통계 정보")
public class SystemStatsDto {

    @Schema(description = "전체 사용자 수")
    private Long totalUsers;

    @Schema(description = "활성 사용자 수 (APPROVED)")
    private Long activeUsers;

    @Schema(description = "승인 대기 사용자 수")
    private Long pendingUsers;

    @Schema(description = "관리자 수")
    private Long adminCount;

    @Schema(description = "전체 게시글 수")
    private Long totalBoards;

    @Schema(description = "오늘 작성된 게시글 수")
    private Long todayBoards;

    @Schema(description = "전체 파일 수")
    private Long totalFiles;

    @Schema(description = "전체 파일 용량 (MB)")
    private Double totalFileSize;

    @Schema(description = "전체 자산 기록 수")
    private Long totalAssets;

    @Schema(description = "전체 거래 내역 수")
    private Long totalTransactions;

    @Schema(description = "오늘 로그인 수")
    private Long todayLogins;

    @Schema(description = "최근 가입일")
    private LocalDateTime lastSignupDate;

    @Schema(description = "서버 가동 시간")
    private String serverUptime;

    @Schema(description = "시스템 상태")
    private String systemStatus;

    // Constructors
    public SystemStatsDto() {}

    // Getters and Setters
    public Long getTotalUsers() {
        return totalUsers;
    }

    public void setTotalUsers(Long totalUsers) {
        this.totalUsers = totalUsers;
    }

    public Long getActiveUsers() {
        return activeUsers;
    }

    public void setActiveUsers(Long activeUsers) {
        this.activeUsers = activeUsers;
    }

    public Long getPendingUsers() {
        return pendingUsers;
    }

    public void setPendingUsers(Long pendingUsers) {
        this.pendingUsers = pendingUsers;
    }

    public Long getAdminCount() {
        return adminCount;
    }

    public void setAdminCount(Long adminCount) {
        this.adminCount = adminCount;
    }

    public Long getTotalBoards() {
        return totalBoards;
    }

    public void setTotalBoards(Long totalBoards) {
        this.totalBoards = totalBoards;
    }

    public Long getTodayBoards() {
        return todayBoards;
    }

    public void setTodayBoards(Long todayBoards) {
        this.todayBoards = todayBoards;
    }

    public Long getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(Long totalFiles) {
        this.totalFiles = totalFiles;
    }

    public Double getTotalFileSize() {
        return totalFileSize;
    }

    public void setTotalFileSize(Double totalFileSize) {
        this.totalFileSize = totalFileSize;
    }

    public Long getTotalAssets() {
        return totalAssets;
    }

    public void setTotalAssets(Long totalAssets) {
        this.totalAssets = totalAssets;
    }

    public Long getTotalTransactions() {
        return totalTransactions;
    }

    public void setTotalTransactions(Long totalTransactions) {
        this.totalTransactions = totalTransactions;
    }

    public Long getTodayLogins() {
        return todayLogins;
    }

    public void setTodayLogins(Long todayLogins) {
        this.todayLogins = todayLogins;
    }

    public LocalDateTime getLastSignupDate() {
        return lastSignupDate;
    }

    public void setLastSignupDate(LocalDateTime lastSignupDate) {
        this.lastSignupDate = lastSignupDate;
    }

    public String getServerUptime() {
        return serverUptime;
    }

    public void setServerUptime(String serverUptime) {
        this.serverUptime = serverUptime;
    }

    public String getSystemStatus() {
        return systemStatus;
    }

    public void setSystemStatus(String systemStatus) {
        this.systemStatus = systemStatus;
    }
}

