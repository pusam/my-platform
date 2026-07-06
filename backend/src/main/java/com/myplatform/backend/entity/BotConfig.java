package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 자동매매 봇 설정 엔티티
 * - 봇 상태(활성화/비활성화)를 DB에 저장하여 서버 재시작 시에도 유지
 */
@Entity
@Table(name = "bot_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 설정 키 (예: "trading_bot")
     */
    @Column(name = "config_key", nullable = false, unique = true, length = 50)
    private String configKey;

    /**
     * 봇 활성화 여부
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * 매매 모드 (VIRTUAL / REAL)
     */
    @Column(name = "trading_mode", length = 20)
    @Builder.Default
    private String tradingMode = "VIRTUAL";

    /**
     * 정규장 마감(KRX 15:20) 강제청산 여부 — 기본 ON. 봇이 포지션 들고 마감하는 오버나잇 노출 방지.
     * null(레거시 행)은 ON 으로 해석. ⚠ NXT 연장장(08~20) 청산은 후속 과제(여기선 정규 연속세션 끝만).
     */
    @Column(name = "force_regular_session_liquidation", nullable = false)
    @Builder.Default
    private Boolean forceRegularSessionLiquidation = true;

    /**
     * 정규장 강제청산 완료 일자 — 멱등 가드. 청산 윈도우(15:20~15:28) 내 완전 청산 시 오늘 날짜로 기록.
     * 새 리더가 페일오버 승계 시 "오늘 == lastForceLiquidationDate" 면 캐치업 skip(중복 청산 방지). null=미실행.
     */
    @Column(name = "last_force_liquidation_date")
    private LocalDate lastForceLiquidationDate;

    // ── 일일 손실 한도 서킷브레이커 (V38, 2026-07-06) ─────────────────────────────
    // ⚠ 브레이커는 config_key='daily_loss_breaker' 전용 행 사용 — 'trading_bot' 행은 saveBotState 등이
    //   load-modify-save(무 @Version) 전체 UPDATE 라 병행 쓰기가 tripped_date 를 클로버할 수 있음(행 분리로 차단).

    /** 일일 실현손실 한도(원, 절대금액). 당일 봇 실현손익 누적 ≤ -한도 시 신규 진입 차단. null(레거시)=기본 300,000. */
    @Column(name = "daily_loss_limit_krw", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private java.math.BigDecimal dailyLossLimitKrw = new java.math.BigDecimal("300000");

    /** 서킷브레이커 on/off — 기본 ON. null(레거시 행)은 ON 으로 해석(forceRegularSessionLiquidation 패턴). */
    @Column(name = "daily_loss_breaker_enabled", nullable = false)
    @Builder.Default
    private Boolean dailyLossBreakerEnabled = true;

    /** 브레이커 발동 일자 — =오늘이면 신규 진입 차단, ≠오늘이면 자동 해제(날짜 비교, 리셋 잡 불필요). null=미발동. */
    @Column(name = "daily_loss_breaker_tripped_date")
    private LocalDate dailyLossBreakerTrippedDate;

    // ── ATR 세트 (V42) — config_key='atr_trading' 전용 행이 소유(행 분리 원칙, 브레이커와 동일 패턴) ──

    /** ATR 세트 종목당 리스크 예산(원) 오버라이드. null = 일일 손실 브레이커 한도 ÷ 6 (기본 5만원). */
    @Column(name = "atr_risk_budget_krw", precision = 15, scale = 2)
    private java.math.BigDecimal atrRiskBudgetKrw;

    /**
     * 마지막 상태 변경 시간
     */
    @Column(name = "last_status_change")
    private LocalDateTime lastStatusChange;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
