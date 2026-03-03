package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_watchlist",
       indexes = {
           @Index(name = "idx_watchlist_unique", columnList = "username, stock_code", unique = true)
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockWatchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "target_price", precision = 15, scale = 0)
    private BigDecimal targetPrice;

    @Column(name = "alert_condition", length = 10)
    private String alertCondition; // ABOVE / BELOW

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "alert_triggered", nullable = false)
    @Builder.Default
    private Boolean alertTriggered = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
