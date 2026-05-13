package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotPerformanceDto {

    private int totalTrades;        // 총 거래 수
    private int winCount;           // 수익 거래
    private int loseCount;          // 손실 거래
    private BigDecimal winRate;     // 승률 %

    private BigDecimal totalPnl;    // 총 손익
    private BigDecimal avgPnl;      // 평균 손익
    private BigDecimal maxWin;      // 최대 수익
    private BigDecimal maxLoss;     // 최대 손실
    private BigDecimal profitFactor; // 수익팩터 (총수익/총손실)
    private BigDecimal maxDrawdown;  // 최대 낙폭 (peak-to-trough, 누적 손익 그래프 기준)

    private Double avgHoldingMinutes; // 평균 보유 시간(분)

    private List<DailyPnlDto> dailyPnl;     // 일별 손익
    private List<StockPnlDto> stockPnl;     // 종목별 손익
    private Map<String, ExitReasonStatDto> exitReasonStats; // 엑시트 사유별

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPnlDto {
        private LocalDate date;
        private BigDecimal pnl;
        private int tradeCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockPnlDto {
        private String stockCode;
        private String stockName;
        private BigDecimal totalPnl;
        private int tradeCount;
        private int winCount;
        private BigDecimal winRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExitReasonStatDto {
        private String reason;
        private String reasonLabel;   // Korean label
        private int count;
        private BigDecimal totalPnl;
        private BigDecimal avgPnl;
    }
}
