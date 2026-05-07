package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_master")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMaster {

    @Id
    @Column(name = "stock_code", length = 20, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 200, nullable = false)
    private String stockName;

    @Column(name = "market", length = 20)
    private String market; // KOSPI / KOSDAQ / KONEX / ETF

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "listed_date")
    private LocalDate listedDate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "source", length = 20, nullable = false)
    @Builder.Default
    private String source = "KRX";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
