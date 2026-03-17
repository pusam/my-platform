-- AI 종합 추천 TOP 5 영구 저장 (장 외 시간에도 조회 가능)
CREATE TABLE IF NOT EXISTS recommendation_snapshot (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code  VARCHAR(20) NOT NULL,
    stock_name  VARCHAR(100) NOT NULL,
    total_score INT NOT NULL DEFAULT 0,
    ai_strategy INT NOT NULL DEFAULT 0,
    earnings    INT NOT NULL DEFAULT 0,
    supply_demand INT NOT NULL DEFAULT 0,
    technical   INT NOT NULL DEFAULT 0,
    sector_momentum INT NOT NULL DEFAULT 0,
    tags        VARCHAR(500),
    change_rate DECIMAL(10, 2),
    rank_order  INT NOT NULL DEFAULT 0,
    snapshot_at DATETIME NOT NULL COMMENT '점수 산출 시점',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rec_snapshot_at ON recommendation_snapshot (snapshot_at DESC);
