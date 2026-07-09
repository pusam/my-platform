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
@Table(name = "signal_outcome",
        // V36 — (signal_type, stock_code, signal_date) 중복 INSERT 방어(P3-2). record() 앱레벨
        // dedup 보완. idx_so_type_date 와는 컬럼 순서가 달라(stock_code 가 중간) 중복 인덱스 아님 → 둘 다 유지.
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_so_type_code_date",
                        columnNames = {"signal_type", "stock_code", "signal_date"})
        },
        indexes = {
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

    /** 시그널 시점 실적 점수 (0~20) — V30. 카테고리 조건부 적중률 집계용. 기존 행은 NULL. */
    @Column(name = "earnings_at_signal")
    private Integer earningsAtSignal;

    /** 시그널 시점 수급 점수 (0~20) — V30. */
    @Column(name = "supply_demand_at_signal")
    private Integer supplyDemandAtSignal;

    /** 시그널 시점 기술 점수 (0~20) — V30. */
    @Column(name = "technical_at_signal")
    private Integer technicalAtSignal;

    /** 시그널 시점 섹터 점수 (0~20) — V30. */
    @Column(name = "sector_momentum_at_signal")
    private Integer sectorMomentumAtSignal;

    /** 시그널 시점 재료 유형 (V31) — stock_catalyst 일캐시 스냅샷. NULL=미수집. */
    @Column(name = "catalyst_type_at_signal", length = 20)
    private String catalystTypeAtSignal;

    /** 시그널 시점 재료 방향 (V31) — POSITIVE/NEGATIVE/NEUTRAL/NONE. NULL=미수집. */
    @Column(name = "catalyst_direction_at_signal", length = 10)
    private String catalystDirectionAtSignal;

    /** 시그널 시점 시장 국면 (V32) — BULL/BEAR/SIDEWAYS. NULL=미수집(python-backend 미가용). */
    @Column(name = "regime_at_signal", length = 10)
    private String regimeAtSignal;

    /** 시그널 시점 RVOL (V41) — 당일 거래대금 ÷ 직전 20거래일 평균. NULL=미수집(20일 미만·캐시 미스). */
    @Column(name = "rvol_at_signal", precision = 8, scale = 2)
    private BigDecimal rvolAtSignal;

    /** 시그널 시점 변동성 국면 (V46) — NORMAL/HIGH_VOL. NULL=미수집(VKOSPI 조회 실패·표본 부족·UNKNOWN). */
    @Column(name = "vol_regime_at_signal", length = 10)
    private String volRegimeAtSignal;

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
