package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 수동 매매 저널 (V43) — 사용자 본인의 매수/매도 기록 + 매수 시점 신호 스냅샷 + 자동 평가.
 *
 * <p>봇 매매(감사로그·주간리포트)와 달리 수동 매매는 기록이 없어 "내 매매 적중률"을
 * {@code signal_outcome} 과 같은 잣대로 측정 못 했다. 이 엔티티는 <b>봇/VirtualTradeHistory/주문 경로와
 * 완전 분리</b>된 수동 기록 전용 — 봇 코드는 이 테이블을 읽지도 쓰지도 않는다.
 *
 * <p><b>§4c</b>: 스냅샷 필드는 각 소스 실패 시 null(미수집) — 기록 자체는 항상 성공.
 * <p><b>v1 스코프</b>: 매도는 전량 가정(부분매도 미지원). 부분매도는 후속.
 */
@Entity
@Table(name = "manual_trade_journal",
        indexes = {
            @Index(name = "idx_mtj_user", columnList = "username, buy_at"),
            @Index(name = "idx_mtj_eval", columnList = "evaluated_at, buy_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualTradeJournal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    // ===== 매수 기록 =====
    @Column(name = "buy_at", nullable = false)
    private LocalDateTime buyAt;

    @Column(name = "buy_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal buyPrice;

    @Column(precision = 15, scale = 0)
    private BigDecimal quantity;

    @Column(length = 500)
    private String memo;

    // ===== 매수 시점 자동 스냅샷 (null=미수집 §4c) =====
    @Column(name = "total_score")
    private Integer totalScore;
    @Column(name = "earnings")
    private Integer earnings;
    @Column(name = "supply_demand")
    private Integer supplyDemand;
    @Column(name = "technical")
    private Integer technical;
    @Column(name = "sector_momentum")
    private Integer sectorMomentum;
    @Column(name = "rsi", precision = 6, scale = 2)
    private BigDecimal rsi;
    /** ORDER_WIN/EARNINGS/... (StockCatalyst.CatalystType) 또는 null. */
    @Column(name = "catalyst_type", length = 20)
    private String catalystType;
    /** POSITIVE/NEGATIVE/NEUTRAL 또는 null. */
    @Column(name = "catalyst_direction", length = 10)
    private String catalystDirection;
    @Column(name = "rvol", precision = 8, scale = 2)
    private BigDecimal rvol;
    /** BULL/BEAR/SIDEWAYS 또는 null. */
    @Column(name = "regime", length = 10)
    private String regime;
    /** ATR14×2.5 기반 참고 손절 %(음수) 또는 null. */
    @Column(name = "atr_stop_pct", precision = 6, scale = 2)
    private BigDecimal atrStopPct;
    /** 매수 시점 5거래일 누적 등락률 % 또는 null. */
    @Column(name = "five_day_return", precision = 8, scale = 2)
    private BigDecimal fiveDayReturn;
    /** 매수 시점 KOSPI(0001) 지수 — alpha_3d 계산용(V44, signal_outcome.bm_price_at_signal 동일 패턴). */
    @Column(name = "bm_price_at_buy", precision = 15, scale = 2)
    private BigDecimal bmPriceAtBuy;

    // ===== 매도 기록 (전량 가정) =====
    @Column(name = "sell_at")
    private LocalDateTime sellAt;
    @Column(name = "sell_price", precision = 15, scale = 2)
    private BigDecimal sellPrice;
    /** 실현 수익률 % = (매도가-매수가)/매수가×100. 매도 기록 시 확정. */
    @Column(name = "realized_pct", precision = 8, scale = 2)
    private BigDecimal realizedPct;

    // ===== 자동 평가 (매수 3거래일 후 — signal_outcome 동일 잣대) =====
    @Column(name = "pct_change_3d", precision = 8, scale = 2)
    private BigDecimal pctChange3d;
    @Column(name = "alpha_3d", precision = 8, scale = 2)
    private BigDecimal alpha3d;
    /** true=적중(alpha≥0 & 상승) / false=미적중 / null=평가 대기(3거래일 미도래, §4c). */
    @Column(name = "hit")
    private Boolean hit;
    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
