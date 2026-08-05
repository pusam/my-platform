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
 * 캔들 패턴 shadow 감지 기록 — V52 참조.
 *
 * <p><b>산식/봇/게이트 어디에도 연결 금지</b>(§4c — 검증 안 된 지표 합산 금지). 일일 배치가 감지 시
 * INSERT 하고, 확정 후 10거래일 히스토리가 쌓이면 같은 배치의 평가 단계가 return_1/3/5/10d 를 채운다.
 * 4주 이상 쌓인 뒤 백테스트 승률 검증을 통과할 때만 합산을 논의한다.
 *
 * <p>NULL 의미: return_* NULL = 미평가(집계 제외) — §4c, 실패를 0% 로 위장하지 않는다.
 */
@Entity
@Table(name = "pattern_detection",
        uniqueConstraints = {
                // 같은 (종목, 패턴, 확정일) 중복 INSERT 방어 — 배치 재실행·late data 재스캔 dedup.
                @UniqueConstraint(name = "uq_pd_code_type_date",
                        columnNames = {"stock_code", "pattern_type", "detected_date"})
        },
        indexes = {
                @Index(name = "idx_pd_type_date", columnList = "pattern_type, detected_date"),
                @Index(name = "idx_pd_unevaluated", columnList = "evaluated_at, detected_date")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatternDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    /** {@code CandlePatternDetector.PatternType} name — BULL_CONTINUATION / CONVERGENCE_BOX. */
    @Column(name = "pattern_type", nullable = false, length = 30)
    private String patternType;

    /** 패턴 확정 봉의 거래일 — 배치 실행일이 아님(히스토리 지연 유입 대비). */
    @Column(name = "detected_date", nullable = false)
    private LocalDate detectedDate;

    /** 확정 봉 종가 — 이후 수익률의 기준가. */
    @Column(name = "close_at_detection", nullable = false, precision = 15, scale = 2)
    private BigDecimal closeAtDetection;

    /** 감지 시점 임계값·실측 지표 JSON — 임계 튜닝 후에도 과거 행 재현 가능하게. */
    @Column(name = "params_snapshot", columnDefinition = "TEXT")
    private String paramsSnapshot;

    @Column(name = "return_1d", precision = 10, scale = 4)
    private BigDecimal return1d;

    @Column(name = "return_3d", precision = 10, scale = 4)
    private BigDecimal return3d;

    @Column(name = "return_5d", precision = 10, scale = 4)
    private BigDecimal return5d;

    @Column(name = "return_10d", precision = 10, scale = 4)
    private BigDecimal return10d;

    /** 10거래일 확보 시 일괄 평가 완료 시각 — NULL=미평가. */
    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
