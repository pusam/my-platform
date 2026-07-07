-- SELL in-flight 마커 — 부분청산 동시 창 과청산 방지 (P3-1 잔여 ③ B안, DESIGN_P3-1_IDEMPOTENT_ORDERS.md).
-- 매도 시도 창(TTL 60s)만 잠근다: 리더 전환/더블런의 "동시" 평가만 차단하고, 그 후는 KIS 잔고
-- 재조회가 진실 원장(잔여분 재시도는 정당 동작이라 통과해야 함 — S3). BUY 멱등키(bot_order_intent,
-- fail-closed)와 달리 DB 오류 = fail-open(매도 지연 = 손실 확대 방지, 비대칭 의도).
CREATE TABLE bot_sell_inflight (
    id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
    stock_code   VARCHAR(20) NOT NULL,
    trading_mode VARCHAR(10) NOT NULL DEFAULT 'REAL',
    acquired_at  DATETIME    NOT NULL,
    expires_at   DATETIME    NOT NULL,
    holder       VARCHAR(64) NULL COMMENT '인스턴스/리더 식별(진단용)',
    UNIQUE KEY uk_bsi_code_mode (stock_code, trading_mode)
) COMMENT='SELL in-flight 마커 — 동시 부분청산 과청산 방지(TTL 60s, 만료 행은 acquire 시 조건부 UPDATE 재사용)';
