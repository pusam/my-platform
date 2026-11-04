-- V70: 종목별 투자자 확정치 (2026-11-05)
--
-- ## 왜
--
-- investor_daily_trade 는 KIS 매매종목가집계(FHPTJ04400000) 순위다. 공식 샘플 주석이 말하는 그대로 증권사 직원이 장중에 입력한
-- 잠정 집계이고 마지막 입력은 14:30 이다 — 15:50 에 받아도 14:30 값이다. 2026-11-04 네이버 확정치 대조(10/28~11/3 600행):
-- 부호 일치 91%, 크기 ±50% 안 33%, 1위가 뒤집힌 날도 있다(10/30 외국인 삼성전자 +1,210억 vs 확정 −2,366억).
-- 종합추천 수급 축·💰수급 트랙·연속 순매수가 이 값을 쓰고, 추천 후보의 91% 가 수급 보유 종목이다.
--
-- 확정치는 종목별 API 뿐이다(종목별 투자자매매동향(일별) FHPTJ04160001, 종목당 1콜). 그래서 후보 풀 종목의 일별 순매수를 이 표에
-- 쌓고, investor_daily_trade 의 그날 행을 이 값으로 다시 만든다 — 소비처는 그 표를 그대로 읽는다. 한 번 호출에 지난 날짜들이 같이
-- 오므로 첫 회차에 최근 거래일이 함께 확정치로 바뀐다.
--
-- ## 칸의 뜻 (§4c)
--   foreign_net/institution_net/pension_net : 순매수 금액(억원). NULL = 응답에 없었다(0 아님). 기관은 기관계(연기금 포함).
--   investor_daily_trade.data_basis : TALLY(가집계 순위 — 그날 재구성이 실패했거나 재구성 전) · FINAL(확정치로 다시 만든 행).
--                                     화면 이름표가 이 값을 따른다.
--
-- ## 되돌리기
--   첫 재구성이 지난 날짜의 가집계 행을 지우므로 여기서 통째로 복사해 둔다:
--     DELETE FROM investor_daily_trade WHERE data_basis = 'FINAL';
--     INSERT INTO investor_daily_trade (market_type, trade_date, investor_type, trade_type, rank_num, stock_code, stock_name,
--            net_buy_amount, buy_amount, sell_amount, current_price, change_rate, trade_volume, created_at)
--     SELECT market_type, trade_date, investor_type, trade_type, rank_num, stock_code, stock_name,
--            net_buy_amount, buy_amount, sell_amount, current_price, change_rate, trade_volume, created_at
--       FROM investor_daily_trade_tally_backup_v70 b
--      WHERE NOT EXISTS (SELECT 1 FROM investor_daily_trade t WHERE t.trade_date = b.trade_date);
CREATE TABLE investor_daily_trade_tally_backup_v70 AS SELECT * FROM investor_daily_trade;

ALTER TABLE investor_daily_trade
    ADD COLUMN data_basis VARCHAR(10) NOT NULL DEFAULT 'TALLY' COMMENT 'TALLY 가집계 순위 · FINAL 확정치로 다시 만든 행';

CREATE TABLE IF NOT EXISTS investor_stock_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL COMMENT '종목코드',
    trade_date DATE NOT NULL COMMENT '거래일',
    foreign_net DECIMAL(15,2) COMMENT '외국인 순매수(억원). NULL=응답에 없음(0 아님)',
    institution_net DECIMAL(15,2) COMMENT '기관계 순매수(억원) — 연기금 포함',
    pension_net DECIMAL(15,2) COMMENT '기금(연기금) 순매수(억원)',
    close_price DECIMAL(15,0) COMMENT '그날 종가',
    change_rate DECIMAL(8,2) COMMENT '등락률(%)',
    volume BIGINT COMMENT '거래량',
    collected_at DATETIME NOT NULL COMMENT '이 행을 마지막으로 받아 쓴 시각',
    UNIQUE KEY uq_isd_stock_date (stock_code, trade_date),
    INDEX idx_isd_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='종목별 투자자 일별 순매수 확정치 (KIS FHPTJ04160001)';
