package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 자동매매 봇 포지션 메타데이터
 * - scalping/swing/closing 포지션의 상태(buyTime, highPrice, halfSold 등)를 영속화
 * - 앱 재시작 시 in-memory Map을 DB에서 복원하기 위한 용도
 */
@Entity
@Table(name = "bot_trading_position", indexes = {
        @Index(name = "idx_btp_strategy", columnList = "strategy"),
        // 같은 종목을 VIRTUAL/REAL 양쪽에서 동시 보유 가능하도록 trading_mode 포함 unique.
        @Index(name = "uk_strategy_stock_mode", columnList = "strategy,stock_code,trading_mode", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotTradingPosition {

    public enum Strategy { SCALPING, SWING, CLOSING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 20)
    private Strategy strategy;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "buy_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal buyPrice;

    @Column(name = "high_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal highPrice;

    @Column(name = "buy_time", nullable = false)
    private LocalDateTime buyTime;

    @Column(name = "half_sold", nullable = false)
    @Builder.Default
    private Boolean halfSold = false;

    @Column(name = "time_extended", nullable = false)
    @Builder.Default
    private Boolean timeExtended = false;

    @Column(name = "original_quantity")
    private Integer originalQuantity;

    @Column(name = "buy_reason", length = 100)
    private String buyReason;

    // ── ATR 세트 스냅샷 (V42, VIRTUAL 전용 · flag `bot.atr-trading.enabled`) ─────────
    // 진입 시점 1회 계산 후 고정(사후 ATR 변동 반영 금지). NULL = 현행 고정 청산 포지션
    // (flag OFF / REAL / ATR 결측 — §4c: 결측이면 수량·청산 모두 완전 현행 폴백).

    /** 진입 시점 ATR14(원, Wilder). */
    @Column(name = "entry_atr", precision = 15, scale = 4)
    private BigDecimal entryAtr;

    /** ATR 손절 레벨(음수 %) — SWING_STOP_LOSS 비교 규약과 동일 부호. */
    @Column(name = "atr_stop_pct", precision = 8, scale = 4)
    private BigDecimal atrStopPct;

    /** ATR 익절 레벨(양수 %). */
    @Column(name = "atr_target_pct", precision = 8, scale = 4)
    private BigDecimal atrTargetPct;

    /**
     * 매수 시점의 거래 모드 — VIRTUAL(모의) 또는 REAL(실전).
     * 모드 전환 시 다른 모드 포지션이 잘못 활성화되어 자동 매도되는 사고 방지.
     */
    @Column(name = "trading_mode", nullable = false, length = 20)
    @Builder.Default
    private String tradingMode = "VIRTUAL";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 낙관적 잠금 — 포지션 업데이트가 동시 스레드에서 충돌할 때 감지.
     * 스캘핑 매도 경로에서 halfSold/highPrice/timeExtended 를 갱신하는데,
     * 같은 포지션이 여러 스레드에서 동시에 update 되면 lost-update 발생 가능.
     * V21 마이그레이션에서 version 컬럼을 추가하고 기본값 0 으로 채움.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

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
