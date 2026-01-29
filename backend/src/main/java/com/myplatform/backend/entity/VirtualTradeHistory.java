package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 가상 거래 내역 엔티티
 * - 모의투자 매수/매도 거래 기록
 */
@Entity
@Table(name = "virtual_trade_history", indexes = {
        @Index(name = "idx_vth_account_date", columnList = "account_id, trade_date"),
        @Index(name = "idx_vth_stock_code", columnList = "stock_code")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualTradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "trade_type", nullable = false, length = 10)
    private String tradeType; // BUY, SELL

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "price", nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "commission", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal commission = BigDecimal.ZERO;

    @Column(name = "tax", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(name = "profit_loss", precision = 15, scale = 2)
    private BigDecimal profitLoss; // 실현손익 (매도 시)

    @Column(name = "trade_reason", length = 50)
    private String tradeReason; // AUTO_BUY, STOP_LOSS, TAKE_PROFIT, MANUAL

    @Column(name = "trade_date", nullable = false)
    private LocalDateTime tradeDate;

    @PrePersist
    protected void onCreate() {
        if (tradeDate == null) {
            tradeDate = LocalDateTime.now();
        }
    }
}
