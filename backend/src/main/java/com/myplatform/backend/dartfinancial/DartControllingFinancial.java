package com.myplatform.backend.dartfinancial;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DART 정기보고서 한 건의 지배주주 순이익·지배지분 자본(V64, 2026-09-30) — 원본 보존용, 계산은 {@link ControllingEarnings}.
 *
 * <p>금액은 억원. {@code null} = 보고서에 그 표준 계정이 없었다(0 아님). {@link #status} 가 {@code NO_DATA} 면 연결·별도
 * 둘 다 "조회된 데이터 없음"(013)이었다는 기록이라 금액이 전부 비어 있고, 재조회 간격 판단에만 쓴다.
 */
@Entity
@Table(name = "dart_controlling_financial",
       uniqueConstraints = @UniqueConstraint(name = "uq_dcf_stock_report",
                                             columnNames = {"stockCode", "bsnsYear", "reprtCode"}),
       indexes = @Index(name = "idx_dcf_stock", columnList = "stockCode"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DartControllingFinancial {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NO_DATA = "NO_DATA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String stockCode;

    @Column(nullable = false, length = 8)
    private String corpCode;

    /** 사업연도. */
    @Column(nullable = false)
    private Integer bsnsYear;

    /** 11013 1분기 · 11012 반기 · 11014 3분기 · 11011 사업보고서. */
    @Column(nullable = false, length = 5)
    private String reprtCode;

    /** CFS 연결 · OFS 별도. NO_DATA 면 null. */
    @Column(length = 3)
    private String fsDiv;

    /** OK · NO_DATA. */
    @Column(nullable = false, length = 10)
    private String status;

    /** 접수일(접수번호 앞 8자리). */
    private LocalDate filedOn;

    /** 지배주주 순이익 — 분·반기는 연초부터 누적, 사업보고서는 연간(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal ctrlNetIncome;

    /** 전년 동기 누적(사업보고서는 전년 연간) 지배주주 순이익(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal ctrlNetIncomePrev;

    /** 비지배 포함 연결 당기순이익(억원) — KIS thtr_ntin 과 대조용. */
    @Column(precision = 15, scale = 2)
    private BigDecimal totalNetIncome;

    /** 지배기업 소유주지분 자본(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal ctrlEquity;

    /** 자본총계(비지배 포함, 억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal totalEquity;

    @Column(nullable = false)
    private LocalDateTime collectedAt;

    ControllingEarnings.ReportKey key() {
        return new ControllingEarnings.ReportKey(bsnsYear, ControllingEarnings.ReportCode.of(reprtCode));
    }

    /** TTM 계산 입력으로. */
    public ControllingEarnings.Report toReport() {
        return new ControllingEarnings.Report(key(), fsDiv, ctrlNetIncome, ctrlNetIncomePrev, ctrlEquity, filedOn);
    }
}
