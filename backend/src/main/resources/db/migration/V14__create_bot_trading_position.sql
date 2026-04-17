-- 자동매매 봇 포지션 메타데이터 (재시작 복구용)
-- scalping/swing/closing 포지션의 buyTime, highPrice, halfSold 등을 영속화
-- 재시작 시 in-memory Map 상태를 DB에서 복원
CREATE TABLE IF NOT EXISTS bot_trading_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy VARCHAR(20) NOT NULL COMMENT 'SCALPING, SWING, CLOSING',
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    buy_price DECIMAL(15,2) NOT NULL,
    high_price DECIMAL(15,2) NOT NULL,
    buy_time TIMESTAMP NOT NULL,
    half_sold BOOLEAN NOT NULL DEFAULT FALSE,
    time_extended BOOLEAN NOT NULL DEFAULT FALSE,
    original_quantity INT,
    buy_reason VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_strategy_stock (strategy, stock_code),
    INDEX idx_btp_strategy (strategy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='자동매매 봇 포지션 메타데이터';
