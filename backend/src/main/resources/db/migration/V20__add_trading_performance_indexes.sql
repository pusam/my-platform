-- 2026-04-24: 2026-04-23 장애 이후 성능 감사에서 발견된 인덱스 누락 보완.
-- 기존 테이블에 누락된 복합 인덱스를 추가해 집계 쿼리(일일 한도 체크, 봇 성과 조회)
-- 가 Full Table Scan 되지 않도록 한다.

-- =====================================================================
-- 1. trading_audit_log — 일일 매수 한도 계산 쿼리 최적화
--    WHERE mode = 'REAL' AND success = TRUE AND created_at >= :since
-- =====================================================================
-- 이미 idx_created_at, idx_action_created, idx_request_id 존재. mode+success 조합은 없음.
CREATE INDEX IF NOT EXISTS idx_audit_mode_success_created
    ON trading_audit_log (mode, success, created_at);

-- =====================================================================
-- 2. virtual_trade_history — 봇 성과 리포트 / 종목별 손익 집계 최적화
--    WHERE account_id = :id ORDER BY trade_date DESC
--    WHERE account_id = :id AND trade_type = 'SELL' AND trade_date >= :since
-- =====================================================================
-- account_id 단일 인덱스는 있을 수 있으나, trade_date / trade_type 조합은 없을 가능성.
-- 이미 있는 인덱스면 CREATE INDEX 가 에러를 내므로 IF NOT EXISTS 효과를 위해 별도 이름 사용.
CREATE INDEX IF NOT EXISTS idx_vth_account_date
    ON virtual_trade_history (account_id, trade_date);

CREATE INDEX IF NOT EXISTS idx_vth_account_type_date
    ON virtual_trade_history (account_id, trade_type, trade_date);

-- =====================================================================
-- 3. stock_price — 단건 종목의 최근 시세 조회 최적화
--    SELECT ... WHERE stock_code = :code ORDER BY fetched_at DESC LIMIT 1
-- =====================================================================
-- findTopByStockCodeOrderByFetchedAtDesc 가 자주 호출됨.
-- 이 쿼리는 (stock_code, fetched_at DESC) 복합 인덱스 필요.
CREATE INDEX IF NOT EXISTS idx_stock_price_code_fetched
    ON stock_price (stock_code, fetched_at);
