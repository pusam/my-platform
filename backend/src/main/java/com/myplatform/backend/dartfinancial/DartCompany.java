package com.myplatform.backend.dartfinancial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DART 기업개황의 결산월(V65, 2026-10-01) — 지배주주 TTM 의 짝 맞추기가 12월 결산 기준이라, 결산월이 다른 회사
 * (동원모빌리티 3월 결산 등)는 순이익을 만들지 않는다({@link ControllingEarnings#ttm}).
 * 결산월은 거의 바뀌지 않아 종목당 한 번 받는다. {@link #fiscalMonth} null = 조회했지만 값이 없었다(재조회 대상).
 */
@Entity
@Table(name = "dart_company")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DartCompany {

    @Id
    @Column(length = 10)
    private String stockCode;

    @Column(nullable = false, length = 8)
    private String corpCode;

    /** 결산월 "01"~"12"(DART acc_mt). */
    @Column(length = 2)
    private String fiscalMonth;

    @Column(nullable = false)
    private LocalDateTime collectedAt;
}
