package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 어닝 서프라이즈 DTO
 * - 분기 실적 비교 결과
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EarningSurpriseDto {

    private String stockCode;
    private String stockName;
    private String market;

    private BigDecimal latestOperatingProfit;      // 최근 분기 영업이익 (억원)
    private BigDecimal previousOperatingProfit;    // 전 분기 영업이익 (억원)
    private BigDecimal operatingProfitChangeRate;  // 변화율 %

    private BigDecimal latestNetIncome;            // 최근 분기 순이익
    private BigDecimal previousNetIncome;          // 전 분기 순이익
    private BigDecimal netIncomeChangeRate;        // 변화율 %

    private BigDecimal latestRevenue;              // 최근 분기 매출
    private BigDecimal revenueChangeRate;          // 매출 변화율 %

    private LocalDate latestReportDate;
    private LocalDate previousReportDate;

    private SurpriseType surpriseType;             // POSITIVE, NEGATIVE, TURNAROUND
    private String summary;                        // 요약 설명

    public enum SurpriseType {
        POSITIVE,      // 영업이익 20%+ 증가
        NEGATIVE,      // 영업이익 20%+ 감소
        TURNAROUND     // 적자→흑자 전환
    }
}
