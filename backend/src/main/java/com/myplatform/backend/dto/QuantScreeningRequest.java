package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Schema(description = "퀀트 스크리닝 조건")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuantScreeningRequest {

    @Schema(description = "최소 PER")
    private BigDecimal minPer;

    @Schema(description = "최대 PER")
    private BigDecimal maxPer;

    @Schema(description = "최소 ROE (%)")
    private BigDecimal minRoe;

    @Schema(description = "최대 ROE (%)")
    private BigDecimal maxRoe;

    @Schema(description = "최소 PBR")
    private BigDecimal minPbr;

    @Schema(description = "최대 PBR")
    private BigDecimal maxPbr;

    @Schema(description = "최소 시가총액 (억원)")
    private BigDecimal minMarketCap;

    @Schema(description = "최대 시가총액 (억원)")
    private BigDecimal maxMarketCap;

    @Schema(description = "최소 배당수익률 (%)")
    private BigDecimal minDividendYield;

    @Schema(description = "최소 부채비율 (%)")
    private BigDecimal minDebtRatio;

    @Schema(description = "최대 부채비율 (%)")
    private BigDecimal maxDebtRatio;

    @Schema(description = "최소 매출 성장률 (%)")
    private BigDecimal minRevenueGrowth;

    @Schema(description = "최소 영업이익률 (%)")
    private BigDecimal minOperatingMargin;

    @Schema(description = "시장 (KOSPI, KOSDAQ, KONEX)")
    private String market;

    @Schema(description = "업종")
    private String sector;

    @Schema(description = "정렬 기준 (per, roe, pbr, marketCap, dividendYield)")
    private String sortBy;

    @Schema(description = "정렬 방향 (asc, desc)")
    private String sortDirection;

    @Schema(description = "결과 개수 제한")
    private Integer limit;
}

