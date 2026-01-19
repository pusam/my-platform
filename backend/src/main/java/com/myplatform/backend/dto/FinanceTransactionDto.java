package com.myplatform.backend.dto;

import com.myplatform.backend.entity.FinanceTransaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceTransactionDto {

    private Long id;
    private String type;
    private String category;
    private BigDecimal amount;
    private LocalDate transactionDate;
    private String memo;
    private LocalDateTime createdAt;

    public static FinanceTransactionDto fromEntity(FinanceTransaction entity) {
        return FinanceTransactionDto.builder()
                .id(entity.getId())
                .type(entity.getType().name())
                .category(entity.getCategory())
                .amount(entity.getAmount())
                .transactionDate(entity.getTransactionDate())
                .memo(entity.getMemo())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
