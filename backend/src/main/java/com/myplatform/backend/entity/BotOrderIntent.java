package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 봇 실주문 멱등키 — 리더 전환 순간 중복 BUY 방지 (P3-1 잔여).
 *
 * <p>리더 A가 KIS BUY 주문을 쏜 직후(응답 전, BotTradingPosition 저장 전) 죽고 B가 승계하면 B가 같은 종목을
 * 재매수할 수 있다(포지션 저장 전이라 자연 가드 공백). KIS 호출 <b>직전</b> 이 키를 선기록해, 같은
 * (종목, 방향, 거래일, 의도시그널)이 이미 PENDING/DONE 이면 재주문을 차단한다.
 *
 * <p><b>BUY 전용</b>: SELL 은 RealTradeService.sell 의 보유수량 체크가 이미 자연 멱등 + 정규장 청산 재시도와
 * 충돌하므로 미적용. 일자 포함 unique 라 일자별 자연 만료(별도 TTL 불필요).
 * SELL 의 동시 부분청산 창은 별도 메커니즘 {@link BotSellInflight}(V45, TTL 60s 마커·fail-open)가 방어.
 */
@Entity
@Table(name = "bot_order_intent",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_intent",
                columnNames = {"stock_code", "side", "trade_date", "reason"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotOrderIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    /** BUY / SELL — 현재 BUY 전용(컬럼은 일반화 유지). */
    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 의도 시그널(예: SWING_FOREIGN, SWING_INSTITUTION). 같은 종목 다른 시그널은 별개 주문으로 취급. */
    @Column(name = "reason", nullable = false, length = 50)
    private String reason;

    /** PENDING(선기록) → DONE(KIS 성공) / FAILED(KIS 확정거부 → 재시도 허용). */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "kis_order_no", length = 50)
    private String kisOrderNo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
