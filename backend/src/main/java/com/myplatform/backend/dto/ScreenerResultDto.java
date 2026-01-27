package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 퀀트 스크리너 결과 DTO
 * - 마법의 공식, PEG 스크리너, 턴어라운드 스크리너 결과용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenerResultDto {

    // 기본 정보
    private String stockCode;
    private String stockName;
    private String market;
    private String sector;

    // 가격 정보
    private BigDecimal currentPrice;
    private BigDecimal marketCap; // 시가총액 (억원)

    // 밸류에이션 지표
    private BigDecimal per;
    private BigDecimal pbr;

    // 수익성 지표
    private BigDecimal roe;
    private BigDecimal operatingMargin;
    private BigDecimal netMargin;

    // EPS 관련 지표
    private BigDecimal eps;
    private BigDecimal epsGrowth;
    private BigDecimal peg;

    // 마법의 공식 관련
    private BigDecimal magicFormulaScore;
    private Integer magicFormulaRank;
    private Integer operatingMarginRank;
    private Integer roeRank;
    private Integer perRank;

    // 턴어라운드 관련
    private String turnaroundType; // LOSS_TO_PROFIT, PROFIT_GROWTH, MARGIN_IMPROVEMENT
    private BigDecimal previousNetIncome; // 이전 분기 순이익
    private BigDecimal currentNetIncome; // 현재 분기 순이익
    private BigDecimal netIncomeChangeRate; // 순이익 변화율 (%)

    // 성장성
    private BigDecimal revenueGrowth;
    private BigDecimal profitGrowth;
}
