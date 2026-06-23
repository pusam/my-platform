package com.myplatform.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 모의투자 관련 DTO
 */
public class PaperTradingDto {

    /**
     * 계좌 요약 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountSummaryDto {
        private Long accountId;
        private String accountName;
        private BigDecimal initialBalance;      // 초기 자본
        private BigDecimal currentBalance;      // 현재 잔액 (현금)
        private BigDecimal totalInvested;       // 총 투자금액
        private BigDecimal totalEvaluation;     // 총 평가금액
        private BigDecimal totalProfitLoss;     // 총 손익 (평가손익 + 실현손익)
        private BigDecimal totalProfitRate;     // 총 수익률 (%)
        private BigDecimal realizedProfitLoss;  // 실현손익
        private BigDecimal unrealizedProfitLoss; // 평가손익
        private Integer holdingCount;           // 보유 종목 수
        private Long totalTradeCount;           // 총 거래 수
        private Long winCount;                  // 수익 거래 수
        private Long loseCount;                 // 손실 거래 수
        private BigDecimal winRate;             // 승률 (%)
        private Long todayTradeCount;           // 오늘 거래 수
        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * 포트폴리오 항목 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortfolioItemDto {
        private Long id;
        private String stockCode;
        private String stockName;
        private Integer quantity;
        private BigDecimal averagePrice;        // 평균 매입가
        private BigDecimal currentPrice;        // 현재가
        private BigDecimal totalInvested;       // 투자금액 (평균가 x 수량)
        private BigDecimal totalEvaluation;     // 평가금액 (현재가 x 수량)
        private BigDecimal profitLoss;          // 평가손익
        private BigDecimal profitRate;          // 손익률 (%)
        private LocalDateTime purchaseDate;     // 최초 매수일
        private LocalDateTime updatedAt;
    }

    /**
     * 거래 내역 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeHistoryDto {
        private Long id;
        private String stockCode;
        private String stockName;
        private String tradeType;               // BUY, SELL
        private String tradeTypeName;           // 매수, 매도
        private Integer quantity;
        private BigDecimal price;               // 체결가
        private BigDecimal totalAmount;         // 총 금액
        private BigDecimal commission;          // 수수료
        private BigDecimal tax;                 // 세금
        private BigDecimal profitLoss;          // 실현손익 (매도 시)
        private String tradeReason;             // AUTO_BUY, STOP_LOSS, TAKE_PROFIT, MANUAL
        private String tradeReasonName;         // 자동매수, 손절, 익절, 수동
        private LocalDateTime tradeDate;
        private String orderNo;                 // KIS 주문번호(ODNO) — 실전 체결조회용(B2-A). 모의는 null.
    }

    /**
     * 봇 상태 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BotStatusDto {
        private Boolean active;
        private LocalDateTime lastTradeTime;
        private String lastError;
        private LocalDateTime lastErrorTime;
        private String status;                  // RUNNING, STOPPED, ERROR
        private Integer todayBuyCount;
        private Integer todaySellCount;
        private String tradingMode;             // VIRTUAL, REAL
        private String tradingModeName;         // 모의투자, 실전투자
    }

    /**
     * 거래 요청 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeRequestDto {
        @NotBlank(message = "종목코드는 필수입니다.")
        private String stockCode;

        private String stockName;

        @NotNull(message = "수량은 필수입니다.")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        private Integer quantity;

        @NotNull(message = "가격은 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "가격은 0보다 커야 합니다.")
        private BigDecimal price;

        @NotBlank(message = "거래구분(BUY/SELL)은 필수입니다.")
        private String tradeType;               // BUY, SELL
    }

    /**
     * 거래 통계 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeStatisticsDto {
        private Long totalTrades;               // 총 거래 수
        private Long buyCount;                  // 매수 수
        private Long sellCount;                 // 매도 수
        private Long winCount;                  // 수익 거래 수
        private Long loseCount;                 // 손실 거래 수
        private BigDecimal winRate;             // 승률 (%)
        private BigDecimal totalRealizedProfitLoss; // 총 실현손익
        private BigDecimal avgProfitPerWin;     // 평균 수익
        private BigDecimal avgLossPerLose;      // 평균 손실
        private BigDecimal profitFactor;        // 손익비
        private Long todayTrades;               // 오늘 거래 수
    }
}
