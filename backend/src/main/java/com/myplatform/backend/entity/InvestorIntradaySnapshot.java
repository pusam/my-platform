package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 투자자별 장중 스냅샷
 * - 10분/30분 단위로 외국인/기관 순매수 데이터를 저장
 * - 이전 스냅샷과 비교하여 급증 종목 감지에 활용
 */
@Entity
@Table(name = "investor_intraday_snapshot",
       indexes = {
           @Index(name = "idx_snapshot_date_time", columnList = "snapshot_date, snapshot_time"),
           @Index(name = "idx_snapshot_stock", columnList = "stock_code, snapshot_date"),
           @Index(name = "idx_snapshot_investor", columnList = "investor_type, snapshot_date, snapshot_time")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorIntradaySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "snapshot_time", nullable = false)
    private LocalTime snapshotTime;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "investor_type", nullable = false, length = 20)
    private String investorType;  // FOREIGN, INSTITUTION

    @Column(name = "net_buy_amount", precision = 15, scale = 2)
    private BigDecimal netBuyAmount;  // 순매수 금액 (억원) - 당일 누적

    @Column(name = "net_buy_volume")
    private Long netBuyVolume;  // 순매수 수량 - 당일 누적

    @Column(name = "current_price", precision = 15, scale = 0)
    private BigDecimal currentPrice;  // 현재가

    @Column(name = "change_rate", precision = 8, scale = 2)
    private BigDecimal changeRate;  // 등락률

    @Column(name = "rank_num")
    private Integer rankNum;  // 순매수 순위

    // 이전 스냅샷 대비 변화량
    @Column(name = "amount_change", precision = 15, scale = 2)
    private BigDecimal amountChange;  // 순매수 금액 변화 (억원)

    @Column(name = "rank_change")
    private Integer rankChange;  // 순위 변화 (음수: 상승, 양수: 하락)

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
