package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

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
}
