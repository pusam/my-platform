package com.myplatform.backend.dto;
}
    }
        this.systemStatus = systemStatus;
    public void setSystemStatus(String systemStatus) {

    }
        return systemStatus;
    public String getSystemStatus() {

    }
        this.serverUptime = serverUptime;
    public void setServerUptime(String serverUptime) {

    }
        return serverUptime;
    public String getServerUptime() {

    }
        this.lastSignupDate = lastSignupDate;
    public void setLastSignupDate(LocalDateTime lastSignupDate) {

    }
        return lastSignupDate;
    public LocalDateTime getLastSignupDate() {

    }
        this.todayLogins = todayLogins;
    public void setTodayLogins(Long todayLogins) {

    }
        return todayLogins;
    public Long getTodayLogins() {

    }
        this.totalTransactions = totalTransactions;
    public void setTotalTransactions(Long totalTransactions) {

    }
        return totalTransactions;
    public Long getTotalTransactions() {

    }
        this.totalAssets = totalAssets;
    public void setTotalAssets(Long totalAssets) {

    }
        return totalAssets;
    public Long getTotalAssets() {

    }
        this.totalFileSize = totalFileSize;
    public void setTotalFileSize(Double totalFileSize) {

    }
        return totalFileSize;
    public Double getTotalFileSize() {

    }
        this.totalFiles = totalFiles;
    public void setTotalFiles(Long totalFiles) {

    }
        return totalFiles;
    public Long getTotalFiles() {

    }
        this.todayBoards = todayBoards;
    public void setTodayBoards(Long todayBoards) {

    }
        return todayBoards;
    public Long getTodayBoards() {

    }
        this.totalBoards = totalBoards;
    public void setTotalBoards(Long totalBoards) {

    }
        return totalBoards;
    public Long getTotalBoards() {

    }
        this.adminCount = adminCount;
    public void setAdminCount(Long adminCount) {

    }
        return adminCount;
    public Long getAdminCount() {

    }
        this.pendingUsers = pendingUsers;
    public void setPendingUsers(Long pendingUsers) {

    }
        return pendingUsers;
    public Long getPendingUsers() {

    }
        this.activeUsers = activeUsers;
    public void setActiveUsers(Long activeUsers) {

    }
        return activeUsers;
    public Long getActiveUsers() {

    }
        this.totalUsers = totalUsers;
    public void setTotalUsers(Long totalUsers) {

    }
        return totalUsers;
    public Long getTotalUsers() {
    // Getters and Setters

    public SystemStatsDto() {}
    // Constructors

    private String systemStatus;
    @Schema(description = "시스템 상태")

    private String serverUptime;
    @Schema(description = "서버 가동 시간")

    private LocalDateTime lastSignupDate;
    @Schema(description = "최근 가입일")

    private Long todayLogins;
    @Schema(description = "오늘 로그인 수")

    private Long totalTransactions;
    @Schema(description = "전체 거래 내역 수")

    private Long totalAssets;
    @Schema(description = "전체 자산 기록 수")

    private Double totalFileSize;
    @Schema(description = "전체 파일 용량 (MB)")

    private Long totalFiles;
    @Schema(description = "전체 파일 수")

    private Long todayBoards;
    @Schema(description = "오늘 작성된 게시글 수")

    private Long totalBoards;
    @Schema(description = "전체 게시글 수")

    private Long adminCount;
    @Schema(description = "관리자 수")

    private Long pendingUsers;
    @Schema(description = "승인 대기 사용자 수")

    private Long activeUsers;
    @Schema(description = "활성 사용자 수 (APPROVED)")

    private Long totalUsers;
    @Schema(description = "전체 사용자 수")

public class SystemStatsDto {
@Schema(description = "시스템 통계 정보")

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;


