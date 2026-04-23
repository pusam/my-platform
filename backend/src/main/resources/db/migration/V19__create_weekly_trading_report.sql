-- AI 주간 매매 분석 리포트
-- 매주 일요일 19:00 (KST) 자동 생성. (week_start, week_end) 1주 단위 unique.

CREATE TABLE IF NOT EXISTS weekly_trading_report (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    week_start      DATE         NOT NULL COMMENT '주의 첫날 (월요일)',
    week_end        DATE         NOT NULL COMMENT '주의 마지막 날 (일요일)',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'REAL' COMMENT 'REAL/VIRTUAL',
    -- 집계 통계 (Gemini 호출 전에 미리 계산)
    total_buys      INT          NOT NULL DEFAULT 0,
    total_sells     INT          NOT NULL DEFAULT 0,
    realized_pnl    DECIMAL(15,2) NOT NULL DEFAULT 0,
    win_count       INT          NOT NULL DEFAULT 0,
    loss_count      INT          NOT NULL DEFAULT 0,
    total_buy_amount  DECIMAL(15,2) NOT NULL DEFAULT 0,
    total_sell_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
    blocked_count   INT          NOT NULL DEFAULT 0 COMMENT '차단된 시도 (audit_log blocked_reason 있는 거)',
    -- 본문
    summary_json    TEXT         NULL COMMENT '집계 통계 원본 JSON (디버그/재분석용)',
    ai_report       TEXT         NULL COMMENT 'Gemini 가 생성한 분석 본문 (markdown)',
    -- 메타
    generated_by    VARCHAR(50)  NOT NULL DEFAULT 'system' COMMENT 'system 또는 username',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_week_mode (week_start, week_end, mode),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
