-- ============================================================
-- 성능 최적화를 위한 추가 인덱스
-- 자주 조회되는 컬럼에 대한 인덱스 추가
-- ============================================================

-- 투자자 일별 매매 테이블 (investor_daily_trade)
-- 종목별 조회 최적화 (getStockInvestorDetail 등)
CREATE INDEX IF NOT EXISTS idx_investor_stock_code
ON investor_daily_trade (stock_code);

-- 종목 + 날짜 복합 인덱스 (종목별 기간 조회)
CREATE INDEX IF NOT EXISTS idx_investor_stock_date
ON investor_daily_trade (stock_code, trade_date);

-- 투자자 유형 + 거래 유형 복합 인덱스 (순매수/순매도 상위 조회)
CREATE INDEX IF NOT EXISTS idx_investor_type_trade
ON investor_daily_trade (investor_type, trade_type, trade_date, rank_num);


-- 공매도 데이터 테이블 (stock_short_data)
-- 공매도 비율 정렬 조회 최적화
CREATE INDEX IF NOT EXISTS idx_short_ratio
ON stock_short_data (short_ratio DESC);

-- 잔고 비율 정렬 조회 최적화
CREATE INDEX IF NOT EXISTS idx_short_balance_ratio
ON stock_short_data (balance_ratio DESC);


-- 주식 재무 데이터 테이블 (stock_financial_data)
-- 마법의 공식 순위 조회 최적화
CREATE INDEX IF NOT EXISTS idx_financial_magic_rank
ON stock_financial_data (magic_formula_rank ASC);

-- PER/PBR 범위 조회 최적화
CREATE INDEX IF NOT EXISTS idx_financial_per_pbr
ON stock_financial_data (per, pbr);

-- 종목코드 + 날짜 복합 인덱스 (종목별 최신 데이터 조회)
CREATE INDEX IF NOT EXISTS idx_financial_stock_date
ON stock_financial_data (stock_code, report_date DESC);


-- 가상 포트폴리오 테이블 (virtual_portfolio)
-- 계좌 + 종목 조회 최적화
CREATE INDEX IF NOT EXISTS idx_vp_account_stock
ON virtual_portfolio (account_id, stock_code);


-- 가상 거래 내역 테이블 (virtual_trade_history)
-- 계좌별 최근 거래 조회 최적화
CREATE INDEX IF NOT EXISTS idx_vth_account_date_desc
ON virtual_trade_history (account_id, trade_date DESC);

-- 거래 유형별 조회 최적화
CREATE INDEX IF NOT EXISTS idx_vth_trade_type
ON virtual_trade_history (trade_type, trade_date);


-- 기술적 신호 테이블 (technical_signals)
-- 최신 신호 조회 최적화
CREATE INDEX IF NOT EXISTS idx_signal_stock_created
ON technical_signals (stock_code, created_at DESC);

-- 신호 유형별 조회 최적화
CREATE INDEX IF NOT EXISTS idx_signal_type_strength
ON technical_signals (signal_type, signal_strength DESC);


-- 장중 스냅샷 테이블 (investor_intraday_snapshot)
-- 특정 날짜 + 시간 스냅샷 조회 최적화
CREATE INDEX IF NOT EXISTS idx_intraday_stock_datetime
ON investor_intraday_snapshot (stock_code, snapshot_date, snapshot_time);


-- 게시판 테이블 (board)
-- 최신 게시글 조회 최적화
CREATE INDEX IF NOT EXISTS idx_board_created_at
ON board (created_at DESC);

-- 사용자별 게시글 조회 최적화
CREATE INDEX IF NOT EXISTS idx_board_user_id
ON board (user_id, created_at DESC);


-- 사용자 자산 테이블 (user_asset)
-- 사용자별 자산 조회 최적화
CREATE INDEX IF NOT EXISTS idx_asset_user_id
ON user_asset (user_id);

-- 자산 유형별 조회 최적화
CREATE INDEX IF NOT EXISTS idx_asset_type
ON user_asset (asset_type, user_id);


-- 금 시세 테이블 (gold_price)
-- 최신 시세 조회 최적화
CREATE INDEX IF NOT EXISTS idx_gold_date
ON gold_price (price_date DESC);


-- 은 시세 테이블 (silver_price)
-- 최신 시세 조회 최적화
CREATE INDEX IF NOT EXISTS idx_silver_date
ON silver_price (price_date DESC);


-- 활동 로그 테이블 (activity_logs)
-- 최근 활동 조회 최적화
CREATE INDEX IF NOT EXISTS idx_activity_created
ON activity_logs (created_at DESC);

-- 사용자별 활동 조회 최적화
CREATE INDEX IF NOT EXISTS idx_activity_user
ON activity_logs (user_id, created_at DESC);


-- ============================================================
-- 인덱스 통계 업데이트 (MariaDB/MySQL)
-- 쿼리 최적화기가 최신 통계를 사용하도록 함
-- ============================================================
ANALYZE TABLE investor_daily_trade;
ANALYZE TABLE stock_short_data;
ANALYZE TABLE stock_financial_data;
ANALYZE TABLE virtual_portfolio;
ANALYZE TABLE virtual_trade_history;
ANALYZE TABLE technical_signals;
ANALYZE TABLE investor_intraday_snapshot;
