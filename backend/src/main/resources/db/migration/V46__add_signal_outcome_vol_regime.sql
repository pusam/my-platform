-- V46: signal_outcome 에 변동성 국면(VKOSPI) 스냅샷 추가 (측정 전용 — 산식 미편입)
--
-- 배경: VKOSPI 변동성 국면 게이트(VolatilityRegimeService)가 시그널 적중률에 실제로 유효한지
--       사후검증하려면 시그널 시점의 국면(NORMAL/HIGH_VOL)이 함께 저장되어야 한다
--       (V30 카테고리 / V31 재료 / V32 regime / V41 RVOL 동일 패턴). 주간 리포트가
--       NORMAL vs HIGH_VOL 로 분리 집계해 게이트 승격 근거를 쌓는다.
--
-- 게이트 모드(OFF/REDUCED/BLOCK)와 <b>독립</b>으로 항상 best-effort 캡처(데이터 축적 목적).
-- 기존 행은 NULL(집계 제외). NULL = 미수집(VKOSPI 조회 실패·표본 부족·UNKNOWN) — §4c 임시값 생성 금지.
-- signal_outcome 은 V26(V15+ 범위)에서 생성되므로 baseline-14 하이브리드 영향 없음(V41 rvol 과 동일).

ALTER TABLE signal_outcome
    ADD COLUMN vol_regime_at_signal VARCHAR(10) NULL
        COMMENT '시그널 시점 변동성 국면 (NORMAL/HIGH_VOL). NULL=미수집(UNKNOWN 포함)'
        AFTER rvol_at_signal;
