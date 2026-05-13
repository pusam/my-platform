-- V26: 시그널 적중률 추적 테이블
--
-- 목적: 각 시그널(STRONG_BUY/COMPOSITE_4PLUS/SURGE_HOT 등) 발생 후 N일 가격 변동을 추적해
--       시그널 종류별 실제 적중률을 정량화. 사용자가 "이 시그널 얼마나 맞나" 확인 가능.
--
-- 흐름:
--   1. 시그널 생성 시 signal_outcome INSERT (price_after_3d / pct_change_3d / hit 는 NULL)
--   2. 일일 배치 — 3일 전 unevaluated 항목의 현재 가격 조회 → 평가 결과 채움
--   3. 통계 쿼리 — signal_type 별 hit_rate / avg_pct_change

CREATE TABLE IF NOT EXISTS signal_outcome (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    signal_type VARCHAR(50) NOT NULL COMMENT 'STRONG_BUY / BUY / SURGE_HOT / COMPOSITE_4PLUS 등',
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100),
    signal_date DATE NOT NULL COMMENT '시그널 발생일 (KST)',
    signal_score INT COMMENT '시그널 발생 시점 점수 (있으면)',
    price_at_signal DECIMAL(15,2) NOT NULL COMMENT '시그널 시점 가격',
    price_after_3d DECIMAL(15,2) COMMENT '3일 후 가격 — 배치가 채움',
    pct_change_3d DECIMAL(10,4) COMMENT '3일 변동률 %',
    hit BOOLEAN COMMENT '+3% 이상 도달 여부',
    evaluated_at DATETIME COMMENT '평가 완료 시각',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_so_type_date (signal_type, signal_date),
    INDEX idx_so_unevaluated (signal_date, evaluated_at),
    INDEX idx_so_stock_code (stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='시그널 적중률 추적 — 시그널 발생 후 N일 가격 변동 기록';
