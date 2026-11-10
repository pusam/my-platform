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
     * KIS 순위 API 로 받은 장중 값이면 그 조회 시각, DB 행이면 null(2026-10-07). 화면이 '장중 가집계'와 DB 값을
     * 줄마다 가른다 — 예전엔 둘이 '10.07 기준' 하나로 섞였다.
     */
    private LocalDateTime asOf;

    /**
     * KIS 순위로 받은 값이 장중 값인가 — 조회 시각이 그날 마감({@code MarketCalendarService.marketCloseOf}) 10분 뒤 전이면 true
     * (2026-11-10). 화면이 '15:50' 을 다시 계산하지 않게 서버가 달력으로 판정한다 — 2026-11-19 수능일은 16:50 까지 장중이다.
     * DB 행은 null.
     */
    private Boolean intraday;

    /** DB 행의 정체 — TALLY 가집계 · FINAL 장 마감 확정치(2026-11-05). KIS 순위로 바로 받은 장중 값은 null. */
    private String dataBasis;
}
