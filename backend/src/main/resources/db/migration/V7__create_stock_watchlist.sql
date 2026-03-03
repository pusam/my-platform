-- 관심종목(워치리스트) 테이블
CREATE TABLE stock_watchlist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    target_price DECIMAL(15,0) DEFAULT NULL,
    alert_condition VARCHAR(10) DEFAULT NULL COMMENT 'ABOVE / BELOW',
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    alert_triggered TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_watchlist_unique (username, stock_code)
);
