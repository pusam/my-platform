package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주식 재무 데이터 엔티티
 * - Open DART API 또는 크롤링으로 수집한 재무제표 정보
 * - 퀀트 스크리너에서 활용
 */
@Entity
@Table(name = "stock_financial_data", indexes = {
    @Index(name = "idx_stock_code", columnList = "stockCode"),
    @Index(name = "idx_date", columnList = "reportDate"),
    @Index(name = "idx_per", columnList = "per"),
    @Index(name = "idx_roe", columnList = "roe"),
    @Index(name = "idx_pbr", columnList = "pbr"),
    @Index(name = "idx_peg", columnList = "peg"),
    @Index(name = "idx_magic_formula_rank", columnList = "magicFormulaRank")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockFinancialData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String stockCode;

    @Column(nullable = false, length = 100)
    private String stockName;

    @Column(nullable = false)
    private String market; // KOSPI, KOSDAQ, KONEX

    @Column(nullable = false)
    private LocalDate reportDate; // 재무제표 기준일

    // 가격 정보
    @Column(precision = 15, scale = 2)
    private BigDecimal currentPrice; // 현재가

    @Column(precision = 15, scale = 2)
    private BigDecimal marketCap; // 시가총액 (억원)

    // 밸류에이션 지표
    @Column(precision = 10, scale = 2)
    private BigDecimal per; // PER (주가수익비율)

    @Column(precision = 10, scale = 2)
    private BigDecimal pbr; // PBR (주가순자산비율)

    @Column(precision = 10, scale = 2)
    private BigDecimal pcr; // PCR (주가현금흐름비율)

    @Column(precision = 10, scale = 2)
    private BigDecimal psr; // PSR (주가매출액비율)

    // 수익성 지표
    @Column(precision = 10, scale = 2)
    private BigDecimal roe; // ROE (자기자본이익률) %

    @Column(precision = 10, scale = 2)
    private BigDecimal roa; // ROA (총자산이익률) %

    @Column(precision = 10, scale = 2)
    private BigDecimal operatingMargin; // 영업이익률 %

    @Column(precision = 10, scale = 2)
    private BigDecimal netMargin; // 순이익률 %

    // 성장성 지표
    @Column(precision = 10, scale = 2)
    private BigDecimal revenueGrowth; // 매출액 증가율 %

    @Column(precision = 10, scale = 2)
    private BigDecimal profitGrowth; // 순이익 증가율 %

    // EPS 관련 지표
    @Column(precision = 15, scale = 2)
    private BigDecimal eps; // 주당순이익

    @Column(precision = 10, scale = 2)
    private BigDecimal epsGrowth; // EPS 성장률 (%)

    @Column(precision = 10, scale = 2)
    private BigDecimal peg; // PEG = PER / EPS성장률

    // 마법의 공식 순위
    private Integer magicFormulaRank;

    // 안정성 지표
    @Column(precision = 10, scale = 2)
    private BigDecimal debtRatio; // 부채비율 %

    @Column(precision = 10, scale = 2)
    private BigDecimal currentRatio; // 유동비율 %

    // 배당 정보
    @Column(precision = 10, scale = 2)
    private BigDecimal dividendYield; // 배당수익률 %

    @Column(precision = 10, scale = 2)
    private BigDecimal dividendPayoutRatio; // 배당성향 %

    // 재무제표 금액 (억원)
    @Column(precision = 15, scale = 2)
    private BigDecimal revenue; // 매출액

    @Column(precision = 15, scale = 2)
    private BigDecimal operatingProfit; // 영업이익

    @Column(precision = 15, scale = 2)
    private BigDecimal netIncome; // 당기순이익

    @Column(precision = 15, scale = 2)
    private BigDecimal totalAssets; // 총자산

    @Column(precision = 15, scale = 2)
    private BigDecimal totalEquity; // 자본총계

    @Column(precision = 15, scale = 2)
    private BigDecimal totalDebt; // 부채총계

    // 메타 정보
    @Column(length = 20)
    private String sector; // 업종

    @Column(length = 500)
    private String description; // 기업 설명

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

