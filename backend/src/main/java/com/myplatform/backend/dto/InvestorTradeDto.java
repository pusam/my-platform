package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 투자자별 매매 데이터 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorTradeDto {

    private String stockCode;
    private String stockName;
    private LocalDate tradeDate;

    // 투자자 정보
    private String investorType;  // FOREIGN(외국인), INSTITUTION(기관), INDIVIDUAL(개인)
    private String investorTypeName;  // 한글명

    // 매매 정보
    private BigDecimal netBuyAmount;  // 순매수 금액 (억원)
    private BigDecimal buyAmount;     // 매수 금액 (억원)
    private BigDecimal sellAmount;    // 매도 금액 (억원)

    // 주가 정보
    private BigDecimal currentPrice;  // 현재가
    private BigDecimal changeRate;    // 등락률 (%)
    private Long tradeVolume;         // 거래량

    private Integer rankNum;          // 순위

    /**
     * KIS 순위 API 로 받은 장중 값이면 그 조회 시각, DB(장 마감 확정치)면 null(2026-10-07). 화면이 '장중 잠정'과
     * '장 마감 집계'를 줄마다 가른다 — 예전엔 둘이 '10.07 기준' 하나로 섞였다.
     */
    private LocalDateTime asOf;
}
