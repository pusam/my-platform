package com.myplatform.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinanceTransactionRequest {

    @NotBlank(message = "거래 유형은 필수입니다")
    @Pattern(regexp = "INCOME|EXPENSE", message = "거래 유형은 INCOME 또는 EXPENSE여야 합니다")
    private String type;

    @NotBlank(message = "카테고리는 필수입니다")
    @Size(max = 50, message = "카테고리는 50자 이하여야 합니다")
    private String category;

    @NotNull(message = "금액은 필수입니다")
    @DecimalMin(value = "0.0", inclusive = false, message = "금액은 0보다 커야 합니다")
    private BigDecimal amount;

    @NotNull(message = "거래 일자는 필수입니다")
    private LocalDate transactionDate;

    @Size(max = 500, message = "메모는 500자 이하여야 합니다")
    private String memo;
}
