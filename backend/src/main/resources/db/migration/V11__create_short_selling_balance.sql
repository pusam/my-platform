CREATE TABLE IF NOT EXISTS short_selling_balance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100),
    trade_date DATE NOT NULL,
    short_selling_volume DECIMAL(15, 0),
    short_selling_amount DECIMAL(15, 2),
    short_selling_ratio DECIMAL(8, 2),
    listed_shares DECIMAL(15, 0),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ssb_stock_date (stock_code, trade_date),
    INDEX idx_ssb_stock_date (stock_code, trade_date),
    INDEX idx_ssb_ratio (short_selling_ratio)
);
