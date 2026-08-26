package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 분기 재무 원본 — KIS 손익계산서(FHKST66430200)가 주는 분기별 행을 <b>그대로</b> 보존한다.
 *
 * <p><b>왜 별도 테이블인가</b>: {@code stock_financial_data} 에는 행 종류가 <b>두 가지 섞여</b> 있다 —
 * 일별 스냅샷({@code reportDate=오늘}, 당일 주가 기반 PER/PBR)과 네이버 크롤이 넣는 분기 행
 * ({@code reportDate=회계기간 말일}). 어닝 서프라이즈는 {@code net_income IS NOT NULL} 로 걸러
 * 후자만 보는데, 네이버 크롤이 듬성듬성해 <b>결측 분기</b>가 많다(prod 실측: 431종목 중 두 최신 행이
 * 인접 분기인 것은 ~90종목뿐, 나머지는 184·275일 간격이라 120일 가드에 걸려 탈락).
 *
 * <p>KIS 손익계산서는 <b>한 번의 호출로 4분기를 준다</b>. 그 응답을 여기 담아 결측 분기를 메우는 것이
 * 이 테이블의 목적이다 — "죽은 축 살리기"가 아니라 <b>얇은 축 두껍게 하기</b>다.
 * 그리고 그 응답은 <b>이미 매일 받고 있었다</b>. TTM 합산에만 쓰고 {@code stac_yymm}(결산년월)을
 * 버렸을 뿐이라 새 API 호출은 없다.
 *
 * <p><b>§4c</b>: 금액 필드 null = API 미제공(0 아님, 비교에서 제외). {@link #cumulative} 는
 * 원본이 누적(YTD)이었다는 <b>사실 기록</b>이고 값은 저장 시점에 보정하지 않는다 —
 * 개별 분기 환산은 직전 누적 행이 있어야 가능해서, 읽는 쪽 순수함수가
 * "환산 가능할 때만" 한다.
 */
@Entity
@Table(name = "stock_quarterly_financial",
       uniqueConstraints = @UniqueConstraint(name = "uq_sqf_stock_period",
                                             columnNames = {"stockCode", "fiscalPeriod"}),
       indexes = {
           @Index(name = "idx_sqf_stock_end", columnList = "stockCode,periodEnd"),
           @Index(name = "idx_sqf_end", columnList = "periodEnd")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockQuarterlyFinancial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String stockCode;

    /** KIS {@code stac_yymm} 원본 (YYYYMM, 예: "202506"). 분기 정체성의 유일한 출처. */
    @Column(nullable = false, length = 6)
    private String fiscalPeriod;

    /** {@link #fiscalPeriod} 말일 — 정렬·인접분기(3개월) 판정용 파생값. */
    @Column(nullable = false)
    private LocalDate periodEnd;

    /** 원본이 누적(YTD)이었는지. true 여도 값은 미보정 — 환산은 읽는 쪽에서. */
    @Column(nullable = false)
    private boolean cumulative;

    /** 매출액 (억원). null = API 미제공. */
    @Column(precision = 15, scale = 2)
    private BigDecimal revenue;

    /** 영업이익 (억원). null = API 미제공. */
    @Column(precision = 15, scale = 2)
    private BigDecimal operatingProfit;

    /** 당기순이익 (억원). null = API 미제공. */
    @Column(precision = 15, scale = 2)
    private BigDecimal netIncome;

    /** 수집 출처 — 현재 {@code KIS_INCOME_STMT} 하나. 나중에 DART 를 붙이면 구분자가 된다. */
    @Column(nullable = false, length = 20)
    private String source;

    /** 이 행을 마지막으로 받아 쓴 시각(수집 신선도 판단용 — 회계 기간과 무관). */
    @Column(nullable = false)
    private LocalDateTime collectedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
