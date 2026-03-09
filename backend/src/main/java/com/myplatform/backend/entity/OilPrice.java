package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "oil_price")
@Getter
@Setter
public class OilPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "price_per_barrel", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricePerBarrel;

    @Column(name = "price_krw", precision = 15, scale = 2)
    private BigDecimal priceKrw;

    @Column(name = "open_price", precision = 15, scale = 2)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 15, scale = 2)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 15, scale = 2)
    private BigDecimal lowPrice;

    @Column(name = "close_price", precision = 15, scale = 2)
    private BigDecimal closePrice;

    @Column(name = "change_price", precision = 15, scale = 2)
    private BigDecimal changePrice;

    @Column(name = "change_rate", precision = 10, scale = 4)
    private BigDecimal changeRate;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "base_date", length = 8)
    private String baseDate;

    @Column(name = "base_date_time")
    private LocalDateTime baseDateTime;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
