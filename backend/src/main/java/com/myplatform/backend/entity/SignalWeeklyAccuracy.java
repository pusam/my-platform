package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시그널 예측력 주간 측정 스냅샷 (P1-6 측정 상설화).
 *
 * <p>매주 일요일 크론이 (카테고리 × regime × 밴드) 적중률/평균 alpha/표본수를 집계해 <b>1주 1행</b> 저장.
 * 시간에 따른 예측력 변화 추적(악화 감지·수급 역상관 지속 주차)이 목적 — <b>종합점수 산식과 무관한 측정 전용</b>.
 *
 * <p>{@link #reportJson} 에 regime 파티션별 카테고리/밴드 stats + 누적 델타 + 경고 전체를 담는다
 * (WeeklyTradingReport.summary_json 패턴). 표본 축적 후 3중 크로스탭 추가도 스키마 변경 없이 가능.
 */
@Entity
@Table(name = "signal_weekly_accuracy",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_swa_week_start", columnNames = {"week_start"})
        },
        indexes = {
                @Index(name = "idx_swa_created_at", columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalWeeklyAccuracy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    /** 이번 주 평가완료 board(STRONG_BUY/BUY) 시그널 수. */
    @Column(name = "weekly_n", nullable = false)
    private int weeklyN;

    /** phase-38 컷오프 이후 누적 board 시그널 수. */
    @Column(name = "cumulative_n", nullable = false)
    private int cumulativeN;

    /** 누적 강세-수급(≥15) avg alpha<0 & 표본 충분 = 역상관 지속 플래그. 연속 주차(스트릭) 카운트용. */
    @Column(name = "supply_inverted", nullable = false)
    private boolean supplyInverted;

    /** 전체 크로스탭 JSON — regime 파티션별 카테고리/밴드 stats + 누적 델타 + 경고. */
    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;

    @Column(name = "generated_by", nullable = false, length = 50)
    private String generatedBy;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (generatedAt == null) generatedAt = createdAt;
        if (generatedBy == null) generatedBy = "system";
    }
}
