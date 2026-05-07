-- 종목코드 ↔ 종목명 마스터 테이블
-- KRX 상장법인목록을 일괄 시드 + KIS 응답 들어올 때마다 lazy upsert
-- 기존 StockNameResolver/SectorStockConfig 하드코딩 맵을 대체하는 source-of-truth

CREATE TABLE IF NOT EXISTS stock_master (
    stock_code   VARCHAR(20)  NOT NULL COMMENT '종목코드 (6자리, 우선주는 7자리)',
    stock_name   VARCHAR(200) NOT NULL COMMENT '종목명 (한글)',
    market       VARCHAR(20)            COMMENT 'KOSPI / KOSDAQ / KONEX / ETF / null',
    sector       VARCHAR(100)           COMMENT '업종 (KRX 분류)',
    listed_date  DATE                   COMMENT '상장일',
    is_active    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=상장중, 0=상폐',
    source       VARCHAR(20)  NOT NULL DEFAULT 'KRX' COMMENT 'KRX / KIS / MANUAL',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_code),
    INDEX idx_stock_master_name (stock_name),
    INDEX idx_stock_master_market (market)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
