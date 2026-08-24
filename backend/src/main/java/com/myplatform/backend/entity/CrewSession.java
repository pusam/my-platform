package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관제실 AI 크루 세션 — V54 참조.
 *
 * <p><b>읽기 전용 레이어</b>. 크루는 조회 결과와 프롬프트만 받으며 DB/파일/봇에 쓰는 툴을 갖지 않는다.
 * 이 테이블이 담는 것은 "무엇을 물었고 무엇이라 답했는가"뿐이고, 결론의 실행은 사람이 한다.
 *
 * <p>5턴 고정이며 실패해도 자동 재시도하지 않는다 — {@code FAILED} + {@link #failureReason} 으로 남기고
 * 재시도 여부는 사람이 판단한다(무한 재시도로 비용이 새는 것 방지).
 *
 * <p>NULL 의미(§4c): {@code finishedAt} NULL = 진행 중(실패 아님).
 */
@Entity
@Table(name = "crew_session",
        indexes = {
                @Index(name = "idx_cs_started", columnList = "started_at"),
                @Index(name = "idx_cs_status", columnList = "status, started_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrewSession {

    /** 세션 상태 — RUNNING 은 진행 중이며 동시 1건 가드의 판정 대상이다. */
    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operator", length = 100)
    private String operator;

    @Column(name = "instruction", nullable = false, columnDefinition = "TEXT")
    private String instruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    /** FAILED 사유 — 자동 재시도 금지이므로 사람이 읽고 판단할 수 있게 원문을 남긴다. */
    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /** 세션 시작 시점의 모델 ID — 설정이 바뀐 뒤에도 과거 세션을 재현/해석할 수 있게 스냅샷. */
    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "context_bytes")
    private Integer contextBytes;

    /** 컨텍스트 상한 초과로 잘라낸 FLAGGED 수 — 0 이 아니면 화면에 "N건 생략"을 표기한다(§4c). */
    @Column(name = "omitted_flags")
    private Integer omittedFlags;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
