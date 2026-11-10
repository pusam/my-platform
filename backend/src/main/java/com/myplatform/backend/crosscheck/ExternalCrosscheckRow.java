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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 외부 대조 종목·항목별 결과(V76). CLOSE 만 판정하고 EPS·BPS 는 기록만 한다 — 상태 이름은 {@link CrosscheckRules}.
 */
@Entity
@Table(name = "external_crosscheck_row", indexes = {
        @Index(name = "idx_ecrow_run", columnList = "run_id"),
        @Index(name = "idx_ecrow_code", columnList = "stock_code, run_id")})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalCrosscheckRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    /** CANDIDATE / HOLDING / WATCH / MCAP / RANDOM */
    @Column(name = "sample_reason", nullable = false, length = 12)
    private String sampleReason;

    /** CLOSE / EPS / BPS */
    @Column(name = "item", nullable = false, length = 10)
    private String item;

    @Column(name = "our_value", precision = 20, scale = 4)
    private BigDecimal ourValue;

    @Column(name = "our_value_date")
    private LocalDate ourValueDate;

    @Column(name = "our_basis", length = 10)
    private String ourBasis;

    @Column(name = "our_prev_value", precision = 20, scale = 4)
    private BigDecimal ourPrevValue;

    @Column(name = "external_value", precision = 20, scale = 4)
    private BigDecimal externalValue;

    @Column(name = "external_raw", length = 40)
    private String externalRaw;

    @Column(name = "external_basis", length = 20)
    private String externalBasis;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "diff_pct", precision = 10, scale = 4)
    private BigDecimal diffPct;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
