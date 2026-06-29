-- 봇 실주문 멱등키 — 리더 전환 순간 중복 BUY 방지 (P3-1 잔여, 2026-06-29 세션 후속 작업2).
-- 리더 A가 KIS BUY 주문을 쏜 직후(응답 전, 포지션 저장 전) 죽고 B가 승계 → 같은 종목 재매수 방지.
-- KIS 호출 직전 (종목,방향,거래일,시그널) 선기록 → PENDING/DONE 이면 재주문 SKIP. BUY 전용.
CREATE TABLE bot_order_intent (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    stock_code    VARCHAR(20)  NOT NULL,
    side          VARCHAR(10)  NOT NULL COMMENT 'BUY / SELL (현재 BUY 전용)',
    trade_date    DATE         NOT NULL,
    reason        VARCHAR(50)  NOT NULL COMMENT '의도 시그널(SWING_FOREIGN 등)',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED',
    kis_order_no  VARCHAR(50)  NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_intent (stock_code, side, trade_date, reason)
) COMMENT='봇 실주문 멱등키 — 리더 전환 중복 BUY 방지';
