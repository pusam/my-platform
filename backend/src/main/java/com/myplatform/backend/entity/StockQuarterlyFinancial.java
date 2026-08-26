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
 * 분기 재무 원본 — KIS 손익계산서(FHKST66430300)가 주는 분기별 행을 <b>그대로</b> 보존한다.
 *
 * <p><b>왜 별도 테이블인가</b>: {@code stock_financial_data} 는 이름과 달리 <b>일별 스냅샷</b>이다
 * (매일 {@code reportDate=오늘} 로 TTM 합 + 당일 주가 기반 PER/PBR 을 한 행씩 적재). 그래서
 * 어닝 서프라이즈가 그 테이블에서 "최신 2행"을 뽑으면 <b>오늘 vs 어제</b>를 비교하게 되고,
 * TTM 은 하루 사이 거의 안 변하니 변화율 ≈ 0 → earnings 카테고리가 사실상 死였다(R1).
 *
 * <p>필요한 분기 데이터는 <b>이미 매일 받고 있었다</b> — TTM 합산에만 쓰고 {@code stac_yymm}
 * (결산년월)을 버렸을 뿐이다. 이 엔티티는 그 버려지던 행을 담는다. 새 API 호출은 없다.
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
