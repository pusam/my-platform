package com.myplatform.backend.dto;

import com.myplatform.backend.entity.RecurringFinance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class RecurringFinanceDto {
    private Long id;
    private String type;
    private String category;
    private String name;
    private BigDecimal amount;
    private Integer dayOfMonth;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean isActive;
    private String memo;
    private LocalDateTime createdAt;
    private List<RecurringFinanceHistoryDto> history;

    public static RecurringFinanceDto fromEntity(RecurringFinance entity) {
        RecurringFinanceDto dto = new RecurringFinanceDto();
        dto.setId(entity.getId());
        dto.setType(entity.getType());
        dto.setCategory(entity.getCategory());
        dto.setName(entity.getName());
        dto.setAmount(entity.getAmount());
        dto.setDayOfMonth(entity.getDayOfMonth());
        dto.setStartDate(entity.getStartDate());
        dto.setEndDate(entity.getEndDate());
        dto.setIsActive(entity.getIsActive());
        dto.setMemo(entity.getMemo());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<RecurringFinanceHistoryDto> getHistory() { return history; }
    public void setHistory(List<RecurringFinanceHistoryDto> history) { this.history = history; }
}
