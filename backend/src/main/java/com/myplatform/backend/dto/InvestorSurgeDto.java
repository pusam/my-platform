package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 외국인/기관 수급 급증 종목 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorSurgeDto {

    private String stockCode;
    private String stockName;
    private String investorType;
    private String investorTypeName;

    private LocalTime snapshotTime;        // 스냅샷 시간

    private BigDecimal netBuyAmount;       // 현재 순매수 금액 (억원)
    private BigDecimal amountChange;       // 변화량 (억원)
    private Double changePercent;          // 변화율 (%)

    private Integer currentRank;           // 현재 순위
    private Integer rankChange;            // 순위 변화 (음수: 상승)

    private BigDecimal currentPrice;       // 현재가
    private BigDecimal changeRate;         // 등락률

    private String surgeLevel;             // 급등 레벨: HOT, WARM, NORMAL
}
