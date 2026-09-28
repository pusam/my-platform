package com.myplatform.backend.youtubeopinion;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 영상 발언 단위 종목 의견 — <b>참고 표시 전용, 추천 산식·봇·signal_outcome 미편입</b>. V61.
 *
 * <p>분석 실행({@code runId})마다 행이 새로 생긴다. 화면은 영상의 {@code currentRunId} 행만 읽으므로 현재 의견은
 * 중복되지 않고, 지난 실행의 행이 바뀐 이력이다. 같은 실행 안에서는 {@code statementKey} 가 유일하다.
 */
@Entity
@Table(name = "yt_opinion",
        uniqueConstraints = @UniqueConstraint(name = "uq_yto_run_key", columnNames = {"run_id", "statement_key"}),
        indexes = {
                @Index(name = "idx_yto_stock", columnList = "stock_code, run_id"),
                @Index(name = "idx_yto_video", columnList = "video_id, run_id"),
                @Index(name = "idx_yto_review", columnList = "review_status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YtOpinion {

    /** 조건이 붙은 의견은 CONDITIONAL — 긍정·부정으로 바꾸지 않는다. 판단이 불분명하면 UNDETERMINABLE. */
    public enum Stance { POSITIVE, NEGATIVE, NEUTRAL, CONDITIONAL, UNDETERMINABLE }

    /** CURRENT_VIEW(발언자 본인의 현재 의견)만 집계한다. 인용·과거 회고·단순 언급은 기록만. */
    public enum StatementType { CURRENT_VIEW, QUOTE, PAST_REVIEW, MENTION }

    /** VERIFIED 또는 관리자가 확인한 MANUAL 만 종목에 붙는다. 나머지는 검토 대상. */
    public enum MappingStatus { VERIFIED, AMBIGUOUS, UNMATCHED, CODE_MISMATCH, MANUAL }

    /** AUTO_OK·APPROVED 만 화면에 나간다. */
    public enum ReviewStatus { AUTO_OK, NEEDS_REVIEW, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "video_id", nullable = false, length = 11)
    private String videoId;

    @Column(name = "statement_key", nullable = false, length = 64)
    private String statementKey;

    @Column(name = "speaker_label", length = 50)
    private String speakerLabel;

    /** 출연자 명단과 결정적으로 맞을 때만 — 모르면 null(인물 수 집계 제외). */
    @Column(name = "person_id")
    private Long personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "speaker_role", length = 10)
    private YtVideoParticipant.Role speakerRole;

    @Column(name = "start_sec", nullable = false)
    private Integer startSec;

    @Column(name = "end_sec", nullable = false)
    private Integer endSec;

    @Column(name = "stock_name_raw", nullable = false, length = 60)
    private String stockNameRaw;

    @Column(name = "stock_code", length = 10)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_status", nullable = false, length = 16)
    private MappingStatus mappingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "stance", nullable = false, length = 16)
    private Stance stance;

    @Enumerated(EnumType.STRING)
    @Column(name = "statement_type", nullable = false, length = 16)
    private StatementType statementType;

    @Column(name = "claim_summary", nullable = false, length = 300)
    private String claimSummary;

    @Column(name = "rationale", length = 500)
    private String rationale;

    /** 전제 조건 — 없으면 null(모델이 보충하지 않는다). */
    @Column(name = "conditions", length = 300)
    private String conditions;

    @Column(name = "horizon", length = 40)
    private String horizon;

    @Column(name = "target_price", precision = 15, scale = 2)
    private BigDecimal targetPrice;

    @Column(name = "evidence_quote", nullable = false, length = 300)
    private String evidenceQuote;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 12)
    private ReviewStatus reviewStatus;

    @Column(name = "review_reasons", length = 300)
    private String reviewReasons;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
