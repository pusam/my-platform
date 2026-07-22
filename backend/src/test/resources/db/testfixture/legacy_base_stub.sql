-- ============================================================================
-- FlywayMigrationTest 전용 레거시 베이스 스텁 (Testcontainers MariaDB init script)
-- ============================================================================
-- 이 스텁은 ddl-auto가 소유한 베이스 테이블의 **최소 재현**이다 — 운영 엔티티와
-- 컬럼이 다를 수 있으며, **마이그레이션이 참조하는 컬럼만** 포함한다.
-- 새 마이그레이션이 스텁에 없는 기존 컬럼을 참조하면 이 테스트가 깨지는데,
-- 그건 **스텁에 컬럼을 추가하라는 신호**다(테스트 결함 아님).
--
-- 배경: V1__baseline.sql 은 no-op(SELECT 1) 이고, 운영 Flyway 는 baseline-version=14
--       + baseline-on-migrate=true 로 V15+ 만 적용한다(§17). 베이스 테이블은 레거시
--       ddl-auto: update 시절 Hibernate 가 만들었고 어떤 마이그레이션도 CREATE 하지
--       않는다. 이 스텁이 그 테이블들을 최소 형태로 심어(스키마 non-empty 화 →
--       baseline-on-migrate 트리거), V15→V38 이 실제 MariaDB 에서 적용되는지 검증한다.
--
-- 각 테이블 옆 주석 = 이 스텁이 필요한 마이그레이션 근거.
-- ============================================================================

-- V15(fk_diet_user)·V17(fk_webauthn_user): `FOREIGN KEY (user_id) REFERENCES users(id)`
--   → 참조 대상 users(id) 가 존재해야 FK 생성 성공. (users 는 엔티티 소유, 마이그레이션 CREATE 안 함)
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
);

-- V16: `RENAME TABLE stock_short_data TO ...` (IF EXISTS 가드 없음 → 반드시 존재해야 함)
CREATE TABLE stock_short_data (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
);

-- V21: ADD COLUMN version / V23: `DROP INDEX uk_strategy_stock` + 재생성(strategy,stock_code,trading_mode)
--      → 스텁에 uk_strategy_stock 명명 유니크 인덱스와 strategy/stock_code 컬럼이 있어야 함.
--      (V14 가 만들지만 baseline=14 가 건너뜀)
CREATE TABLE bot_trading_position (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy VARCHAR(30) NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    UNIQUE KEY uk_strategy_stock (strategy, stock_code)
);

-- V22: ADD value_stability / V29: ADD growth (V12 가 만들지만 baseline=14 가 건너뜀)
CREATE TABLE recommendation_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
);

-- V20: 인덱스 idx_vth_account_date(account_id,trade_date), idx_vth_account_type_date(account_id,trade_type,trade_date)
CREATE TABLE virtual_trade_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NULL,
    trade_type VARCHAR(10) NULL,
    trade_date DATE NULL
);

-- V20: 인덱스 idx_stock_price_code_fetched(stock_code,fetched_at)
-- V51: ADD COLUMN ... AFTER market_cap — 운영 실테이블(레거시 ddl-auto 산물)에 있는 market_cap 이
--      스텁에도 있어야 V51 이 적용된다(스텁 최소주의 예외: AFTER 앵커 컬럼은 포함 필수).
CREATE TABLE stock_price (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(20) NULL,
    market_cap DECIMAL(20,0) NULL,
    fetched_at DATETIME NULL
);

-- V25: 인덱스 idx_user_asset_user_id(user_id) — 테이블 존재는 미가드(인덱스 존재만 체크)
CREATE TABLE user_asset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL
);

-- V25: 인덱스 idx_finance_tx_user_date(username,transaction_date) — 테이블 존재 미가드
CREATE TABLE finance_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NULL,
    transaction_date DATE NULL
);

-- V25: 인덱스 idx_board_author_created(author_name,created_at) — column-guard 有(선택적)이나 충실성 위해 포함
CREATE TABLE board (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    author_name VARCHAR(50) NULL,
    created_at DATETIME NULL
);

-- V33/V34/V38: ALTER bot_config ADD COLUMN (엔티티 소유, 어떤 마이그레이션도 CREATE 안 함)
CREATE TABLE bot_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
);
