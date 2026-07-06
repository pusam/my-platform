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
 * 매크로 tilt 일일 스냅샷 (P3-7, 표시 전용 · unverified · 산식 미편입).
 *
 * <p>매일 08:15 크론이 3축 입력(VKOSPI·국고3년 추세·SOX 추세)과 판정(RISK_ON/NEUTRAL/RISK_OFF)을
 * <b>1일 1행</b> 저장. 주간 리포트에서 regime v1 대비 예측력을 사후 측정하는 것이 승격 조건이라
 * <b>판정 입력 3종 + 관측일을 결과와 함께</b> 기록한다(당시 입력의 재현이 목적 — 결과만 저장 금지).
 *
 * <p>값 필드 전부 NULL 허용 = 미수집(§4c). ECOS 키 발급 전 금리 축, 콜드스타트 ~5거래일 SOX 추세는
 * 의도된 NULL(웜업). regime v1 어휘(BULL/BEAR/SIDEWAYS)와 tilt 어휘는 의도적으로 분리.
 */
@Entity
@Table(name = "macro_tilt_snapshot",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mts_snapshot_date", columnNames = {"snapshot_date"})
        },
        indexes = {
                @Index(name = "idx_mts_created_at", columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MacroTiltSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** RISK_ON / NEUTRAL / RISK_OFF. */
    @Column(name = "tilt", nullable = false, length = 10)
    private String tilt;

    /** VKOSPI 레벨(KIS 업종코드 0503 일봉 최신 종가). NULL=미수집. */
    @Column(name = "vkospi", precision = 6, scale = 2)
    private BigDecimal vkospi;

    /** 국고채 3년 금리 %(ECOS 817Y002 최신). NULL=미수집(키 미발급 포함). */
    @Column(name = "ktb3y", precision = 6, scale = 3)
    private BigDecimal ktb3y;

    /** 국고3년 ~20거래일 변화(bp). NULL=미수집/시계열 부족. */
    @Column(name = "rate_trend_bp", precision = 7, scale = 2)
    private BigDecimal rateTrendBp;

    /** ^SOX 레벨(Yahoo 단건) — 추세 축적의 원료. NULL=미수집. */
    @Column(name = "sox_level", precision = 10, scale = 2)
    private BigDecimal soxLevel;

    /** SOX ~5거래일 추세 %(자체 스냅샷 대비). NULL=웜업/미수집. */
    @Column(name = "sox_trend_pct", precision = 6, scale = 2)
    private BigDecimal soxTrendPct;

    /** 한은 기준금리 %(ECOS 722Y001, 참고 맥락 — classify 입력 아님). */
    @Column(name = "base_rate", precision = 5, scale = 2)
    private BigDecimal baseRate;

    /** VKOSPI 관측 영업일 (08:15 스냅 시 보통 T-1). */
    @Column(name = "vkospi_date")
    private LocalDate vkospiDate;

    /** 국고3년 관측 영업일 (ECOS 발표 지연으로 T-1~2). */
    @Column(name = "rate_date")
    private LocalDate rateDate;

    /** SOX 추세 기준(비교 대상 과거 스냅샷) 일자. */
    @Column(name = "sox_asof")
    private LocalDate soxAsof;

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
