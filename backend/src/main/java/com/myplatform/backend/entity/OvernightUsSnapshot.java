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
 * 간밤 미국장 tilt 일일 스냅샷 (P3-5, 표시 전용 · unverified · 산식 미편입).
 *
 * <p>매일 08:10 크론이 판정 입력 4종(ES/NQ/SOX 등락률 + VIX 레벨)과 판정(BULL/NEUTRAL/BEAR)을
 * <b>1일 1행</b> 저장 — V39 {@link MacroTiltSnapshot} 패턴 복제. 지금까지 tilt 는 미영속이라
 * KOSPI 익일 시초가 대비 적중률 사후검증(캘리브레이션 P3-5)이 불가했다.
 * <b>판정 입력을 결과와 함께</b> 기록한다(당시 입력의 재현이 목적 — 결과만 저장 금지).
 *
 * <p>값 필드 전부 NULL 허용 = 미수집(§4c). Yahoo 미가용 축은 NULL 그대로 저장.
 * tilt 어휘(BULL/NEUTRAL/BEAR)는 간밤 미국장 판정이며 regime v1 과 별개 판정임에 유의.
 */
@Entity
@Table(name = "overnight_us_snapshot",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ous_snapshot_date", columnNames = {"snapshot_date"})
        },
        indexes = {
                @Index(name = "idx_ous_created_at", columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OvernightUsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** BULL / NEUTRAL / BEAR. */
    @Column(name = "tilt", nullable = false, length = 10)
    private String tilt;

    /** S&P500(ES) 등락률 %. NULL=미수집. */
    @Column(name = "es_rate", precision = 6, scale = 2)
    private BigDecimal esRate;

    /** 나스닥100(NQ) 등락률 %. NULL=미수집. */
    @Column(name = "nq_rate", precision = 6, scale = 2)
    private BigDecimal nqRate;

    /** 필라델피아 반도체(SOX) 등락률 %. NULL=미수집. */
    @Column(name = "sox_rate", precision = 6, scale = 2)
    private BigDecimal soxRate;

    /** VIX 레벨. NULL=미수집. */
    @Column(name = "vix_level", precision = 6, scale = 2)
    private BigDecimal vixLevel;

    /** ^SOX 레벨(참고 맥락 — classify 입력 아님). */
    @Column(name = "sox_level", precision = 10, scale = 2)
    private BigDecimal soxLevel;

    /** Yahoo 체결시각 문자열(사후검증 신선도 필터용, 사용자가 본 그대로). */
    @Column(name = "trading_time", length = 40)
    private String tradingTime;

    /** 당일 python regime v1 (BULL/BEAR/SIDEWAYS). NULL=미수집 — 비교 기준 동시 스냅. */
    @Column(name = "regime_v1", length = 10)
    private String regimeV1;

    /** 표시된 drivers 문자열(사용자가 본 그대로). */
    @Column(name = "drivers", length = 300)
    private String drivers;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
