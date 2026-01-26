package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceSummaryDto {

    private Integer year;
    private Integer month;
    private BigDecimal totalIncome;        // 총 수입 (고정 + 일반)
    private BigDecimal totalExpense;       // 총 지출 (고정 + 일반)
    private BigDecimal balance;            // 잔액
    private BigDecimal recurringIncome;    // 고정 수입
    private BigDecimal recurringExpense;   // 고정 지출
    private BigDecimal variableIncome;     // 변동 수입
    private BigDecimal variableExpense;    // 변동 지출
    private List<FinanceTransactionDto> transactions;
    private List<RecurringFinanceDto> recurringItems; // 고정 항목 목록
}
