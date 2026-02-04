package com.myplatform.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 알림 발송 이력 Entity
 * - 텔레그램 알림 쿨다운 관리용
 * - 서버 재시작 후에도 쿨다운 유지
 */
@Entity
@Table(name = "alert_history", indexes = {
    @Index(name = "idx_alert_key", columnList = "alertKey"),
    @Index(name = "idx_sent_at", columnList = "sentAt")
})
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String alertKey;  // stockCode_investorType 형태

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(length = 50)
    private String stockName;

    @Column(nullable = false, length = 20)
    private String investorType;  // FOREIGN, INSTITUTION, COMMON

    @Column(nullable = false, length = 20)
    private String alertType;  // SURGE, HOT, COMMON_BUY 등

    @Column(nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlertKey() {
        return alertKey;
    }

    public void setAlertKey(String alertKey) {
        this.alertKey = alertKey;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public String getInvestorType() {
        return investorType;
    }

    public void setInvestorType(String investorType) {
        this.investorType = investorType;
    }

    public String getAlertType() {
        return alertType;
    }

    public void setAlertType(String alertType) {
        this.alertType = alertType;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
