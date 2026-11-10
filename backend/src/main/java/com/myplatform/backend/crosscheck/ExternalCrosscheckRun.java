package com.myplatform.backend.crosscheck;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 외부 공개 수치 대조 실행 한 번(V76). 쓰는 곳은 {@link ExternalCrosscheckService} 하나뿐이다.
 * {@code notCompared} 는 '대조 못 함'이다 — 일치로 세지 않는다(§4c).
 */
@Entity
@Table(name = "external_crosscheck_run", indexes = @Index(name = "idx_ecr_run_date", columnList = "run_date"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalCrosscheckRun {

    public static final String SOURCE_NAVER = "NAVER_INTEGRATION";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_ABORTED = "ABORTED";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    /** 대조한 우리 봉 날짜 — 직전 마감 거래일. */
    @Column(name = "target_bar_date", nullable = false)
    private LocalDate targetBarDate;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    /** CRON / MANUAL */
    @Column(name = "trigger_type", nullable = false, length = 10)
    private String triggerType;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "planned", nullable = false)
    private int planned;

    @Column(name = "attempted", nullable = false)
    private int attempted;

    @Column(name = "matched", nullable = false)
    private int matched;

    @Column(name = "mismatched", nullable = false)
    private int mismatched;

    @Column(name = "not_compared", nullable = false)
    private int notCompared;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
