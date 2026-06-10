-- V30: signal_outcome 에 카테고리 점수 스냅샷 4종 추가
--
-- 배경: 적중률이 시그널 타입(STRONG_BUY/BUY)별 전체 평균만 집계됨. "수급 만점짜리 추천이
--       실제로 먹혔나 vs 기술 만점짜리가 먹혔나" 같은 카테고리 조건부 적중률을 보려면
--       시그널 발생 시점의 카테고리 점수가 함께 저장되어야 함.
--
-- 신규 컬럼 (각 0~20, 시그널 record 시점의 RecommendationDto 카테고리 점수):
--   earnings_at_signal / supply_demand_at_signal / technical_at_signal / sector_momentum_at_signal
--
-- 기존 행은 NULL (집계에서 제외), 신규 시그널부터 누적.

ALTER TABLE signal_outcome
    ADD COLUMN earnings_at_signal INT NULL COMMENT '시그널 시점 실적 점수 (0~20)' AFTER signal_score,
    ADD COLUMN supply_demand_at_signal INT NULL COMMENT '시그널 시점 수급 점수 (0~20)' AFTER earnings_at_signal,
    ADD COLUMN technical_at_signal INT NULL COMMENT '시그널 시점 기술 점수 (0~20)' AFTER supply_demand_at_signal,
    ADD COLUMN sector_momentum_at_signal INT NULL COMMENT '시그널 시점 섹터 점수 (0~20)' AFTER technical_at_signal;
