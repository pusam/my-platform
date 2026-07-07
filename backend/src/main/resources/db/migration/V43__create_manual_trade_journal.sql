-- V43: 수동 매매 저널 — 사용자 본인의 매수/매도를 기록하고 매수 시점 신호 스냅샷 + 자동 평가.
--
-- 봇 매매(감사로그·주간리포트)와 달리 사용자 수동 매매는 기록이 없어 "내 매매 적중률"을
-- signal_outcome 과 같은 잣대로 측정할 수 없었다. 이 테이블은 봇/VirtualTradeHistory/주문 경로와
-- 완전 분리(수동 기록 전용). 스냅샷 필드 NULL = 미수집(§4c, 소스 실패 시에도 기록은 성공).
--
-- v1 스코프: 매도는 전량 가정(부분매도 미지원 — 엔티티/서비스 주석 명시).
-- 신규 테이블이라 baseline-14 하이브리드 영향 없음(CREATE 자립).

CREATE TABLE manual_trade_journal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100),

    -- 매수 기록
    buy_at DATETIME NOT NULL,
    buy_price DECIMAL(15,2) NOT NULL,
    quantity DECIMAL(15,0),
    memo VARCHAR(500),

    -- 매수 시점 자동 스냅샷 (각 소스 실패 시 해당 필드 NULL = 미수집 §4c)
    total_score INT,
    earnings INT,
    supply_demand INT,
    technical INT,
    sector_momentum INT,
    rsi DECIMAL(6,2),
    catalyst_type VARCHAR(20),
    catalyst_direction VARCHAR(10),
    rvol DECIMAL(8,2),
    regime VARCHAR(10),
    atr_stop_pct DECIMAL(6,2),
    five_day_return DECIMAL(8,2),

    -- 매도 기록 (전량 가정 — 부분매도 v1 스코프 밖)
    sell_at DATETIME,
    sell_price DECIMAL(15,2),
    realized_pct DECIMAL(8,2),

    -- 자동 평가 (매수 3거래일 후 — signal_outcome 동일 잣대)
    pct_change_3d DECIMAL(8,2),
    alpha_3d DECIMAL(8,2),
    hit TINYINT(1),
    evaluated_at DATETIME,

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_mtj_user (username, buy_at),
    INDEX idx_mtj_eval (evaluated_at, buy_at)
);
