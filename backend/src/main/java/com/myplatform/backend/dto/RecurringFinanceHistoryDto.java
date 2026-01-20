package com.myplatform.backend.dto;

import com.myplatform.backend.entity.RecurringFinanceHistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class RecurringFinanceHistoryDto {
    private Long id;
    private BigDecimal previousAmount;
    private BigDecimal newAmount;
    private LocalDate effectiveDate;
    private String changeReason;
    private LocalDateTime createdAt;

    public static RecurringFinanceHistoryDto fromEntity(RecurringFinanceHistory entity) {
        RecurringFinanceHistoryDto dto = new RecurringFinanceHistoryDto();
        dto.setId(entity.getId());
        dto.setPreviousAmount(entity.getPreviousAmount());
        dto.setNewAmount(entity.getNewAmount());
        dto.setEffectiveDate(entity.getEffectiveDate());
        dto.setChangeReason(entity.getChangeReason());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public BigDecimal getPreviousAmount() { return previousAmount; }
    public void setPreviousAmount(BigDecimal previousAmount) { this.previousAmount = previousAmount; }

    public BigDecimal getNewAmount() { return newAmount; }
    public void setNewAmount(BigDecimal newAmount) { this.newAmount = newAmount; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
