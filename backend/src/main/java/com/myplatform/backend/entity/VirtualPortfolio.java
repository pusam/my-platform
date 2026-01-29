package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 가상 포트폴리오 엔티티
 * - 모의투자용 보유 종목 정보
 */
@Entity
@Table(name = "virtual_portfolio", indexes = {
        @Index(name = "idx_vp_account_id", columnList = "account_id"),
        @Index(name = "idx_vp_stock_code", columnList = "stock_code")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualPortfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "average_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal averagePrice;

    @Column(name = "current_price", precision = 15, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "profit_loss", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal profitLoss = BigDecimal.ZERO;

    @Column(name = "profit_rate", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal profitRate = BigDecimal.ZERO;

    @Column(name = "purchase_date")
    private LocalDateTime purchaseDate;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        purchaseDate = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
