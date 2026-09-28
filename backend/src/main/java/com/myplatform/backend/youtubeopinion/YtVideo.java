package com.myplatform.backend.youtubeopinion;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 분석 대상 영상 — 같은 영상 ID 는 한 행(재등록은 새 행을 만들지 않는다). V61.
 *
 * <p>{@code status} 는 <b>마지막 시도</b>의 상태이고, 화면이 읽는 결과는 {@code currentRunId}(마지막 성공)다.
 * 그래서 재분석이 실패해도 이전 성공 결과는 그대로 보이고, 상태는 FAILED + 원인으로 드러난다(§4c — 실패를 성공으로 두지 않는다).
 */
@Entity
@Table(name = "yt_video",
        uniqueConstraints = @UniqueConstraint(name = "uq_ytv_video_id", columnNames = "video_id"),
        indexes = {
                @Index(name = "idx_ytv_published", columnList = "published_at"),
                @Index(name = "idx_ytv_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YtVideo {

    /** REGISTERED=자막 없음(미수집) · TRANSCRIPT_READY=자막 있음·분석 전 · ANALYZING · ANALYZED · FAILED(마지막 시도 실패). */
    public enum Status { REGISTERED, TRANSCRIPT_READY, ANALYZING, ANALYZED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false, length = 11)
    private String videoId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "channel_id", nullable = false, length = 64)
    private String channelId;

    @Column(name = "channel_name", nullable = false, length = 100)
    private String channelName;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "source_note", nullable = false, length = 300)
    private String sourceNote;

    /** 재업로드·편집본이면 원본 영상 ID — 집계에서 뺀다. */
    @Column(name = "duplicate_of", length = 11)
    private String duplicateOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "current_run_id")
    private Long currentRunId;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "registered_by", length = 50)
    private String registeredBy;

    /** 최초 수집(등록) 시각. */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
