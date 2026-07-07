package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * SELL in-flight 마커 — 동시 부분청산 과청산 방지 (P3-1 잔여 ③ B안, V45).
 *
 * <p>보유 100주 중 50주 익절(부분청산)을 두 실행 주체(리더 전환/더블런)가 동시에 평가하면 둘 다
 * "보유 100≥50" 통과 → 과청산. 이 마커는 매도 <b>시도 창(TTL 60초)</b>만 잠근다 — 창이 지나면
 * KIS 잔고 재조회가 진실 원장이라(잔여분 재시도는 정당 동작, S3) 마커가 이를 막지 않는다.
 *
 * <p>BUY 멱등키({@link BotOrderIntent}, 일자 키·fail-closed)와 달리 <b>DB 오류 = fail-open</b>
 * (매도 가드가 손절을 지연시키면 손실 확대 — 의도된 극성 반대). 만료 행은 acquire 시 조건부
 * UPDATE 로 재사용(삭제 잡 불필요).
 */
@Entity
@Table(name = "bot_sell_inflight",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bsi_code_mode",
                columnNames = {"stock_code", "trading_mode"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotSellInflight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    /** REAL 전용(VirtualTradeService 는 KIS 미경유 — 마커 불필요). 컬럼은 일반화 유지. */
    @Column(name = "trading_mode", nullable = false, length = 10)
    @Builder.Default
    private String tradingMode = "REAL";

    @Column(name = "acquired_at", nullable = false)
    private LocalDateTime acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 인스턴스/리더 식별 — 진단용. */
    @Column(name = "holder", length = 64)
    private String holder;
}
