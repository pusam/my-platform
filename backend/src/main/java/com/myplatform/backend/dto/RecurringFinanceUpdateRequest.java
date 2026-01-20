package com.myplatform.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class RecurringFinanceUpdateRequest {
    private BigDecimal amount;
    private LocalDate effectiveDate;
    private String changeReason;
    private String name;
    private String memo;

    // Getters and Setters
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
}
