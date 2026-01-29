package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 종목별 투자자 매매 상세 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockInvestorDetailDto {

    private String stockCode;
    private String stockName;

    // 일자별 투자자 매매 데이터
    private List<DailyInvestorTrade> dailyTrades;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyInvestorTrade {
        private LocalDate tradeDate;

        // 종가 (차트용)
        private java.math.BigDecimal closePrice;

        // 외국인 매매
        private InvestorTradeSummary foreign;

        // 기관 매매
        private InvestorTradeSummary institution;

        // 개인 매매
        private InvestorTradeSummary individual;

        // 연기금 매매
        private InvestorTradeSummary pension;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestorTradeSummary {
        private java.math.BigDecimal buyAmount;      // 매수 금액
        private java.math.BigDecimal sellAmount;     // 매도 금액
        private java.math.BigDecimal netBuyAmount;   // 순매수 금액 (매수 - 매도)
    }
}
