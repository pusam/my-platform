-- 원유(WTI) 시세 히스토리 테이블
CREATE TABLE IF NOT EXISTS oil_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    price_per_barrel DECIMAL(15,2) NOT NULL COMMENT 'WTI 1배럴 가격 (USD)',
    price_krw DECIMAL(15,2) COMMENT '원화 환산 가격 (KRW)',
    open_price DECIMAL(15,2) COMMENT '시가 (USD)',
    high_price DECIMAL(15,2) COMMENT '고가 (USD)',
    low_price DECIMAL(15,2) COMMENT '저가 (USD)',
    close_price DECIMAL(15,2) COMMENT '종가 (USD)',
    change_price DECIMAL(15,2) COMMENT '전일 대비 (USD)',
    change_rate DECIMAL(10,4) COMMENT '등락률 (%)',
    volume BIGINT COMMENT '거래량',
    base_date VARCHAR(8) COMMENT '기준일 (YYYYMMDD)',
    base_date_time DATETIME COMMENT '기준시간',
    fetched_at DATETIME NOT NULL COMMENT '데이터 수집 시간',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oil_price_fetched_at (fetched_at),
    INDEX idx_oil_price_base_date (base_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
