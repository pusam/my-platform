package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinanceRecordRequest {

    private Integer year;
    private Integer month;

    // 수입
    private BigDecimal salary;
    private BigDecimal bonus;

    // 고정지출
    private BigDecimal rent;
    private BigDecimal utilities;
    private BigDecimal insurance;
    private BigDecimal loan;
    private BigDecimal subscription;
    private BigDecimal transportation;
    private BigDecimal food;
    private BigDecimal etc;

    private String memo;
}
