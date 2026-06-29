-- 봇 정규장 마감(15:20) 강제청산 옵션 — 기본 ON(오버나잇 노출 방지).
-- 기존 행도 default TRUE 로 청산 활성. (작업2, 2026-06-29)
ALTER TABLE bot_config
    ADD COLUMN force_regular_session_liquidation TINYINT(1) NOT NULL DEFAULT 1;
