-- ==================================================================
-- V25: 자주 필터되는 컬럼에 인덱스 추가 — full table scan 회피
-- ==================================================================
--  user_asset.user_id          : 사용자별 자산 조회 (List<UserAsset> findByUserId)
--  finance_transactions.username + transaction_date :
--      월별 가계부 조회 (findByUsernameAndTransactionDateBetween).
--      복합 인덱스 — username으로 좁힌 후 날짜 범위 scan.
--  board.author_id + created_at :
--      "내가 쓴 글" 조회 + 최신순 정렬.
-- ------------------------------------------------------------------
--  MariaDB는 CREATE INDEX IF NOT EXISTS 미지원 → 사전에 information_schema 체크.
-- ==================================================================

-- user_asset(user_id)
SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'user_asset'
      AND index_name   = 'idx_user_asset_user_id'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_user_asset_user_id ON user_asset (user_id)',
    'SELECT "skip — idx_user_asset_user_id already exists"'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- finance_transactions(username, transaction_date) — 월별 조회 핵심 경로
SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'finance_transactions'
      AND index_name   = 'idx_finance_tx_user_date'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_finance_tx_user_date ON finance_transactions (username, transaction_date)',
    'SELECT "skip — idx_finance_tx_user_date already exists"'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- board(author_id, created_at DESC) — 내 글 최신순
SET @table_exists := (
    SELECT COUNT(1) FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name   = 'board'
);
SET @idx_exists := IF(@table_exists = 0, 1, (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'board'
      AND index_name   = 'idx_board_author_created'
));
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_board_author_created ON board (author_id, created_at)',
    'SELECT "skip — idx_board_author_created already exists or table absent"'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
