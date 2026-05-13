package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시그널 적중률 추적 — V26 참조.
 *
 * 시그널 발생 시 INSERT, 일일 배치가 3일 후 평가해 price_after_3d / pct_change_3d / hit / evaluated_at 채움.
 */
@Entity
@Table(name = "signal_outcome", indexes = {
        @Index(name = "idx_so_type_date", columnList = "signal_type, signal_date"),
        @Index(name = "idx_so_unevaluated", columnList = "signal_date, evaluated_at"),
        @Index(name = "idx_so_stock_code", columnList = "stock_code")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_type", nullable = false, length = 50)
    private String signalType;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(name = "signal_date", nullable = false)
    private LocalDate signalDate;

    @Column(name = "signal_score")
    private Integer signalScore;

    @Column(name = "price_at_signal", nullable = false, precision = 15, scale = 2)
    private BigDecimal priceAtSignal;

    /** KOSPI 시그널 발생 시점 지수값 — phase 20 추가. record() 시점에 채워짐. */
    @Column(name = "bm_price_at_signal", precision = 15, scale = 2)
    private BigDecimal bmPriceAtSignal;

    @Column(name = "price_after_3d", precision = 15, scale = 2)
    private BigDecimal priceAfter3d;

    /** 시그널 후 3거래일 최고가 (절대값) — phase 25 MFE 계산 input. */
    @Column(name = "max_high_3d", precision = 15, scale = 2)
    private BigDecimal maxHigh3d;

    /** 시그널 후 3거래일 최저가 (절대값) — phase 25 MAE 계산 input. */
    @Column(name = "max_low_3d", precision = 15, scale = 2)
    private BigDecimal maxLow3d;

    @Column(name = "pct_change_3d", precision = 10, scale = 4)
    private BigDecimal pctChange3d;

    /** 같은 기간 KOSPI 종합지수 변동률 % — phase 20 추가. */
    @Column(name = "bm_return_3d", precision = 10, scale = 4)
    private BigDecimal bmReturn3d;

    /** 초과수익률 alpha = pctChange3d - bmReturn3d — phase 20 추가. */
    @Column(name = "alpha_3d", precision = 10, scale = 4)
    private BigDecimal alpha3d;

    /** Max Favorable Excursion % — phase 25. (max_high - price_at_signal) / price_at_signal × 100. */
    @Column(name = "mfe_pct_3d", precision = 10, scale = 4)
    private BigDecimal mfePct3d;

    /** Max Adverse Excursion % — phase 25. (max_low - price_at_signal) / price_at_signal × 100. 음수. */
    @Column(name = "mae_pct_3d", precision = 10, scale = 4)
    private BigDecimal maePct3d;

    @Column(name = "hit")
    private Boolean hit;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
