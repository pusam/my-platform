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
