package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 연금복권 720+ 금주의 추천 번호 엔티티
 */
@Entity
@Table(name = "pension_lottery_weekly_recommendation", indexes = {
        @Index(name = "idx_pension_weekly_generated_date", columnList = "generated_date"),
        @Index(name = "idx_pension_weekly_target_draw", columnList = "target_draw_no")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PensionLotteryWeeklyRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generated_date", nullable = false)
    private LocalDate generatedDate;

    @Column(name = "target_draw_no", nullable = false)
    private Integer targetDrawNo;

    @Column(name = "latest_analyzed_draw_no", nullable = false)
    private Integer latestAnalyzedDrawNo;

    @Column(name = "analyzed_draw_count")
    private Integer analyzedDrawCount;

    // 추천 번호 5게임 (JSON 형태로 저장)
    @Column(name = "recommendations", columnDefinition = "TEXT")
    private String recommendations;

    // 통계 요약 (JSON)
    @Column(name = "statistics_summary", columnDefinition = "TEXT")
    private String statisticsSummary;

    // Hot/Cold 숫자 (JSON)
    @Column(name = "hot_digits", columnDefinition = "TEXT")
    private String hotDigits;

    @Column(name = "cold_digits", columnDefinition = "TEXT")
    private String coldDigits;

    // 조 번호 통계 (JSON)
    @Column(name = "group_stats", columnDefinition = "TEXT")
    private String groupStats;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
