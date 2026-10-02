package com.myplatform.backend.shortselling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 공매도 <b>거래 비중</b> 일별 스냅샷 — KIS 공매도 상위종목(국내주식-133) 한 행(V67, 2026-10-02).
 *
 * <p>잔고가 아니다(잔고 표는 {@code short_selling_balance}, 출처가 죽어 비어 있다). <b>표시 전용</b> —
 * 판정·봇·경보·추천에 쓰지 않는다(사용자 결정 2026-10-02, 거래 비중용 기준은 검증된 적이 없다).
 */
@Entity
@Table(name = "short_selling_trade",
        indexes = @Index(name = "idx_sst_date_rank", columnList = "trade_date, rank_no"),
        uniqueConstraints = @UniqueConstraint(name = "uk_sst_stock_date", columnNames = {"stock_code", "trade_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortSellingTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", length = 20, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    /** KIS 기준 일자(stnd_date2) — 수집일이 아니다. */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 응답 순서(1부터). */
    @Column(name = "rank_no")
    private Integer rankNo;

    /** 공매도 체결 수량. */
    @Column(name = "short_volume")
    private Long shortVolume;

    /** 공매도 거래량 비중(%) — 그날 거래량 중 공매도 몫. */
    @Column(name = "short_volume_share", precision = 9, scale = 4)
    private BigDecimal shortVolumeShare;

    /** 공매도 거래 대금(KIS 원본 단위). */
    @Column(name = "short_amount", precision = 22, scale = 0)
    private BigDecimal shortAmount;

    /** 공매도 거래대금 비중(%). */
    @Column(name = "short_amount_share", precision = 9, scale = 4)
    private BigDecimal shortAmountShare;

    @Column(name = "total_volume")
    private Long totalVolume;

    @Column(name = "total_amount", precision = 22, scale = 0)
    private BigDecimal totalAmount;

    /** 수집 시점 현재가. */
    @Column(name = "price", precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "change_rate", precision = 9, scale = 4)
    private BigDecimal changeRate;

    /** 평균가격 — 거래대금 단위 확인용(공매도 대금 ≈ 수량 × 평균가). */
    @Column(name = "avg_price", precision = 15, scale = 2)
    private BigDecimal avgPrice;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
