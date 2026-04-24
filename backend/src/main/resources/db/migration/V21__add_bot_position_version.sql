-- 2026-04-24: BotTradingPosition 낙관적 잠금(@Version) 도입.
-- 스캘핑 매도 스케줄러가 같은 포지션을 동시에 업데이트할 때 lost-update 방지.
-- JPA 가 WHERE version=? 조건을 자동으로 붙이고 실패 시 OptimisticLockException.

ALTER TABLE bot_trading_position
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
