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

    /**
     * QUEUED·WAITING 은 CLAUDE 작업자 경로만 쓴다(2026-10-01) — QUEUED=작업자가 아직 안 가져감, WAITING=사용량 한도·로그인
     * 만료로 {@link #nextAttemptAt} 까지 대기. 둘 다 '진행 중'이지 실패도 '의견 없음'도 아니다.
     */
    public enum Status { QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED }

    public static final String ANALYZER_GEMINI = "GEMINI";
    public static final String ANALYZER_CLAUDE = "CLAUDE";

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

    // ---- 분석기·작업자 상태(V66, 2026-10-01) ----

    /** GEMINI(서버 직접) · CLAUDE(로컬 작업자). */
    @Builder.Default
    @Column(name = "analyzer", nullable = false, length = 10)
    private String analyzer = ANALYZER_GEMINI;

    /** 요청 모델(별칭 가능, 예: sonnet). 실제로 응답한 모델은 {@link #model} 에 남는다. */
    @Column(name = "requested_model", length = 80)
    private String requestedModel;

    /** CLAUDE 작업 임대 토큰 — 이 토큰을 든 작업자만 결과를 낼 수 있다(재시작·중복 작업자가 덮어쓰지 못하게). */
    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    /** 작업자 실행 시도 수 — 사용량 한도·로그인 대기는 세지 않는다. */
    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    /** WAITING 이 다시 집힐 수 있는 시각. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** WAITING 사유 — QUOTA(사용량 한도) · LOGIN(로그인 만료). */
    @Column(name = "wait_reason", length = 20)
    private String waitReason;

    /** 작업자 식별(로그용 — 비밀값 아님). */
    @Column(name = "worker_id", length = 60)
    private String workerId;
}
