package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관제실 크루 턴별 발언 — V54 참조.
 *
 * <p>턴은 1~5 고정이다(자동 루프·재시도로 늘어나지 않는다). 세션당 {@code (session_id, turn_no)} UNIQUE.
 *
 * <p>모델·토큰 메타를 함께 남기는 이유는 <b>비용 추적</b>이다. 설정 모델이 바뀌어도 과거 세션의
 * 실제 단가를 계산할 수 있어야 한다.
 *
 * <p>NULL 의미(§4c): {@code inputTokens}/{@code outputTokens} NULL = 응답에 usage 가 없어 미수집.
 * 0 으로 채우면 "공짜 호출"로 위장되므로 집계에서 제외한다.
 * {@code stopReason} 이 {@code max_tokens} 면 응답이 잘린 것이며 화면에 "응답 잘림"으로 명시한다.
 */
@Entity
@Table(name = "crew_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cm_session_turn", columnNames = {"session_id", "turn_no"})
        },
        indexes = {
                @Index(name = "idx_cm_session", columnList = "session_id, turn_no")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrewMessage {

    /** 발언 주체. OPERATOR 는 사람 지시(턴 0 성격)이며 LLM 호출이 아니다. */
    public enum Agent { OPERATOR, EREN, SCOUT, FIREWALL }

    /** 5턴 파이프라인의 단계. ORDER 는 오퍼레이터 지시 기록용. */
    public enum Phase { ORDER, ROUTING, DRAFT, REVIEW, REVISE, CLOSING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "turn_no", nullable = false)
    private Integer turnNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent", nullable = false, length = 20)
    private Agent agent;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 20)
    private Phase phase;

    @Column(name = "content", columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "effort", length = 10)
    private String effort;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "stop_reason", length = 30)
    private String stopReason;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
