package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 종목·일자별 재료(catalyst) 분류 캐시 — V31.
 *
 * 네이버 뉴스 → Gemini 분류 결과를 하루 1회 캐시 (같은 날 재호출 방지).
 * 산식에는 미편입 — 표시(배지) + 시그널 스냅샷(검증)용. 재료별 적중률이 검증된 뒤
 * 산식 편입을 검토한다.
 */
@Entity
@Table(name = "stock_catalyst",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalyst_stock_date",
                columnNames = {"stock_code", "catalyst_date"}),
        indexes = @Index(name = "idx_catalyst_date", columnList = "catalyst_date"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockCatalyst {

    /** 재료 유형 — Gemini 분류 결과. NONE = 뉴스는 봤으나 유의미한 재료 없음. */
    public enum CatalystType { ORDER_WIN, EARNINGS, MNA, NEW_BUSINESS, REGULATION, LITIGATION, GOVERNANCE, OTHER, NONE }

    /** 재료 방향. NONE = 재료 없음 (type=NONE 과 항상 짝). */
    public enum Direction { POSITIVE, NEGATIVE, NEUTRAL, NONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(name = "catalyst_date", nullable = false)
    private LocalDate catalystDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "catalyst_type", nullable = false, length = 20)
    private CatalystType catalystType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private Direction direction;

    /** 근거가 된 뉴스 제목. */
    @Column(name = "headline", length = 300)
    private String headline;

    /** Gemini 한 줄 요약. */
    @Column(name = "summary", length = 500)
    private String summary;

    /** 분류 근거 대표 뉴스 URL (V53) — null=미확보(§4c, 프론트는 링크 생략). 표시 전용. */
    @Column(name = "news_link", length = 500)
    private String newsLink;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
