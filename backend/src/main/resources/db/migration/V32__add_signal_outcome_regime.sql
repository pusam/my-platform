-- V32: signal_outcome 에 시장 국면 스냅샷 추가
--
-- 배경: 적중률이 시장 국면(상승장/하락장)과 무관하게 집계됨 — "하락장에서도 이 추천이
--       먹히는가"를 검증할 수 없음. python-backend 재편(pykrx 국면 분류)과 함께,
--       시그널 record 시점의 국면을 스냅샷해 국면별 적중률을 집계한다.
--       (V30 카테고리 / V31 재료와 동일 패턴 — NULL=미수집, 집계 제외)
--
-- regime_at_signal: BULL / BEAR / SIDEWAYS (python-backend /api/v2/regime/current 소스)

ALTER TABLE signal_outcome
    ADD COLUMN regime_at_signal VARCHAR(10) NULL COMMENT '시그널 시점 시장 국면 (NULL=미수집)' AFTER catalyst_direction_at_signal;
