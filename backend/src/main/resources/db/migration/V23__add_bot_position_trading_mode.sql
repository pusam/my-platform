-- BotTradingPosition 에 trading_mode 추가.
-- AutoTradingBotService 의 모드 전환(REAL ↔ VIRTUAL) 시 deleteAll() 안전망 외에,
-- 시작 복구 시점에 현재 모드 외 포지션이 잘못 활성화되는 것을 방지하는 추가 가드.
-- 기존 데이터는 모두 VIRTUAL 로 가정 (스캘핑은 VIRTUAL 만 동작했음).

ALTER TABLE bot_trading_position
ADD COLUMN trading_mode VARCHAR(20) NOT NULL DEFAULT 'VIRTUAL'
COMMENT 'VIRTUAL(모의투자) 또는 REAL(실전투자)';

-- 기존 unique key (strategy, stock_code) → (strategy, stock_code, trading_mode) 로 갱신.
-- 같은 종목을 양쪽 모드에서 동시 보유하는 케이스를 허용 (모드 전환 후 옛 포지션 보존 가능).
ALTER TABLE bot_trading_position DROP INDEX uk_strategy_stock;
CREATE UNIQUE INDEX uk_strategy_stock_mode
ON bot_trading_position(strategy, stock_code, trading_mode);
