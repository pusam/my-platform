package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinanceTransactionRequest {

    private String type; // INCOME or EXPENSE
    private String category;
    private BigDecimal amount;
    private LocalDate transactionDate;
    private String memo;
}
