-- ============================================================
-- V2: 성능 최적화를 위한 인덱스 추가
-- 자주 조회되는 컬럼에 대한 복합 인덱스
-- ============================================================

-- 투자자 일별 매매 테이블 (investor_daily_trade)
CREATE INDEX IF NOT EXISTS idx_investor_stock_code
    ON investor_daily_trade (stock_code);

CREATE INDEX IF NOT EXISTS idx_investor_stock_date
    ON investor_daily_trade (stock_code, trade_date);

CREATE INDEX IF NOT EXISTS idx_investor_type_trade
    ON investor_daily_trade (investor_type, trade_type, trade_date, rank_num);


-- 공매도 데이터 테이블 (stock_short_data)
CREATE INDEX IF NOT EXISTS idx_short_ratio
    ON stock_short_data (short_ratio DESC);

CREATE INDEX IF NOT EXISTS idx_short_balance_ratio
    ON stock_short_data (balance_ratio DESC);


-- 주식 재무 데이터 테이블 (stock_financial_data)
CREATE INDEX IF NOT EXISTS idx_financial_stock_date
    ON stock_financial_data (stock_code, report_date DESC);


-- 가상 포트폴리오 테이블 (virtual_portfolio)
CREATE INDEX IF NOT EXISTS idx_vp_account_stock
    ON virtual_portfolio (account_id, stock_code);


-- 가상 거래 내역 테이블 (virtual_trade_history)
CREATE INDEX IF NOT EXISTS idx_vth_account_date_desc
    ON virtual_trade_history (account_id, trade_date DESC);

CREATE INDEX IF NOT EXISTS idx_vth_trade_type
    ON virtual_trade_history (trade_type, trade_date);


-- 기술적 신호 테이블 (technical_signals)
CREATE INDEX IF NOT EXISTS idx_signal_stock_created
    ON technical_signals (stock_code, created_at DESC);


-- 장중 스냅샷 테이블 (investor_intraday_snapshot)
CREATE INDEX IF NOT EXISTS idx_intraday_stock_datetime
    ON investor_intraday_snapshot (stock_code, snapshot_date, snapshot_time);


-- 게시판 테이블 (board)
CREATE INDEX IF NOT EXISTS idx_board_created_at
    ON board (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_board_user_id
    ON board (user_id, created_at DESC);


-- 금/은 시세 테이블
CREATE INDEX IF NOT EXISTS idx_gold_date
    ON gold_price (price_date DESC);

CREATE INDEX IF NOT EXISTS idx_silver_date
    ON silver_price (price_date DESC);


-- 활동 로그 테이블 (activity_logs)
CREATE INDEX IF NOT EXISTS idx_activity_created
    ON activity_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_activity_user
    ON activity_logs (user_id, created_at DESC);
