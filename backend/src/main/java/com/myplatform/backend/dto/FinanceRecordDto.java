package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceRecordDto {

    private Long id;
    private Integer year;
    private Integer month;

    // 수입
    private BigDecimal salary;
    private BigDecimal bonus;
    private BigDecimal totalIncome;

    // 고정지출
    private BigDecimal rent;
    private BigDecimal utilities;
    private BigDecimal insurance;
    private BigDecimal loan;
    private BigDecimal subscription;
    private BigDecimal transportation;
    private BigDecimal food;
    private BigDecimal etc;
    private BigDecimal totalExpense;

    // 결과
    private BigDecimal balance;

    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
