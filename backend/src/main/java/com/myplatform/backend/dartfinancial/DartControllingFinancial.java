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
    /** 재무제표가 원화가 아니다(달러·위안·엔) — 금액을 저장하지 않고 PER·PBR 에 쓰지 않는다(2026-11-04). */
    public static final String STATUS_NON_KRW = "NON_KRW";
    /** 금액 단위를 잘못 공시한 보고서로 보인다({@link DartQuarterly#scaleSuspect}) — 금액을 저장하지 않는다. 정정하면 재조회가 잡는다(2026-11-05). */
    public static final String STATUS_BAD_SCALE = "BAD_SCALE";

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

    /** 매출액 — 분·반기는 연초부터 누적, 사업보고서는 연간(억원). null = 그 계정이 없었다(은행 등, V69). */
    @Column(precision = 15, scale = 2)
    private BigDecimal revenue;

    /** 영업이익 — 분·반기는 연초부터 누적, 사업보고서는 연간(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal operatingProfit;

    /** 자산총계(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal totalAssets;

    /** 부채총계(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal totalLiabilities;

    /** 전년 동기 누적 매출(억원) — 사업보고서는 전년 연간. 이 보고서 자신의 비교 칸이라 기준이 같다(V71). */
    @Column(precision = 15, scale = 2)
    private BigDecimal revenuePrev;

    /** 전년 동기 누적 영업이익(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal operatingProfitPrev;

    /** 전년 동기 누적 당기순이익(억원, 비지배 포함). */
    @Column(precision = 15, scale = 2)
    private BigDecimal totalNetIncomePrev;

    /** 위 칸까지 읽은 행인가 — V71 이 비워 분기를 채울 종목만 다시 받는다. */
    @Column(nullable = false)
    private boolean incomeParsed;

    /**
     * 이 보고서의 3개월 매출(억원) — 반기·3분기 보고서만(V77, 2026-11-12). 누적({@link #revenue}) − 이 값 = 이 보고서가 말하는 '그 앞 시점까지의
     * 누적'이고, 앞 보고서 값과 다르면 나중 보고서가 앞 분기를 다시 적은 것이다({@link DartQuarterly#latestBasisQuarters}).
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal revenueQ;

    /** 이 보고서의 3개월 영업이익(억원). */
    @Column(precision = 15, scale = 2)
    private BigDecimal operatingProfitQ;

    /** 이 보고서의 3개월 당기순이익(억원, 비지배 포함). */
    @Column(precision = 15, scale = 2)
    private BigDecimal totalNetIncomeQ;

    /** 3개월 칸을 읽으려 해 본 행인가 — V77 이전에 받은 반기·3분기 보고서만 한 번 다시 받는다(없어서 못 읽은 행도 true). */
    @Column(nullable = false)
    private boolean quarterParsed;

    @Column(nullable = false)
    private LocalDateTime collectedAt;

    ControllingEarnings.ReportKey key() {
        return new ControllingEarnings.ReportKey(bsnsYear, ControllingEarnings.ReportCode.of(reprtCode));
    }

    /** TTM 계산 입력으로. */
    public ControllingEarnings.Report toReport() {
        return new ControllingEarnings.Report(key(), fsDiv, ctrlNetIncome, ctrlNetIncomePrev, ctrlEquity, filedOn);
    }

    /** 개별 분기 환산 입력으로 — 순이익은 비지배 포함(KIS 분기 원본의 thtr_ntin 과 같은 정의). */
    DartQuarterly.Statement toStatement() {
        return new DartQuarterly.Statement(key(), fsDiv, revenue, operatingProfit, totalNetIncome,
                revenueQ, operatingProfitQ, totalNetIncomeQ);
    }
}
