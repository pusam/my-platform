package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 연속 매수 종목 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsecutiveBuyDto {

    private String stockCode;
    private String stockName;
    private String investorType;        // FOREIGN, INSTITUTION
    private String investorTypeName;    // 외국인, 기관

    private Integer consecutiveDays;    // 연속 매수 일수
    private BigDecimal totalNetBuyAmount;  // 누적 순매수 금액 (억원)
    private BigDecimal avgDailyAmount;     // 일평균 순매수 금액 (억원)

    private LocalDate startDate;        // 연속 매수 시작일
    private LocalDate endDate;          // 연속 매수 종료일 (최근일)

    private BigDecimal currentPrice;    // 현재가
    private BigDecimal changeRate;      // 등락률
}
