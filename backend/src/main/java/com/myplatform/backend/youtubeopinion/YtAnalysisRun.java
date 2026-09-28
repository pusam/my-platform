package com.myplatform.backend.youtubeopinion;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 분석 실행 — 모델·프롬프트 버전과 결과. 실패 실행은 발언 행을 남기지 않는다(부분 결과를 "의견 없음"처럼 보이게 하지 않는다). V61.
 */
@Entity
@Table(name = "yt_analysis_run",
        indexes = {
                @Index(name = "idx_ytar_video", columnList = "video_id, started_at"),
                @Index(name = "idx_ytar_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YtAnalysisRun {

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false, length = 11)
    private String videoId;

    @Column(name = "transcript_id", nullable = false)
    private Long transcriptId;

    @Column(name = "model", nullable = false, length = 80)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    @Column(name = "statement_count", nullable = false)
    private Integer statementCount;

    @Column(name = "dropped_count", nullable = false)
    private Integer droppedCount;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "requested_by", length = 50)
    private String requestedBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** 분석 완료 시각. */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
