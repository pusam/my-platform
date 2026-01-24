package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "주식 재무 정보")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockFinancialDto {

    @Schema(description = "종목 코드")
    private String stockCode;

    @Schema(description = "종목명")
    private String stockName;

    @Schema(description = "시장")
    private String market;

    @Schema(description = "업종")
    private String sector;

    @Schema(description = "재무제표 기준일")
    private LocalDate reportDate;

    @Schema(description = "현재가")
    private BigDecimal currentPrice;

    @Schema(description = "시가총액 (억원)")
    private BigDecimal marketCap;

    @Schema(description = "PER")
    private BigDecimal per;

    @Schema(description = "PBR")
    private BigDecimal pbr;

    @Schema(description = "ROE (%)")
    private BigDecimal roe;

    @Schema(description = "ROA (%)")
    private BigDecimal roa;

    @Schema(description = "영업이익률 (%)")
    private BigDecimal operatingMargin;

    @Schema(description = "순이익률 (%)")
    private BigDecimal netMargin;

    @Schema(description = "배당수익률 (%)")
    private BigDecimal dividendYield;

    @Schema(description = "부채비율 (%)")
    private BigDecimal debtRatio;

    @Schema(description = "매출액 (억원)")
    private BigDecimal revenue;

    @Schema(description = "영업이익 (억원)")
    private BigDecimal operatingProfit;

    @Schema(description = "당기순이익 (억원)")
    private BigDecimal netIncome;

    @Schema(description = "매출 성장률 (%)")
    private BigDecimal revenueGrowth;

    @Schema(description = "순이익 성장률 (%)")
    private BigDecimal profitGrowth;

    @Schema(description = "기업 설명")
    private String description;
}

