-- V41: signal_outcome 에 RVOL 스냅샷 추가 (표시·측정 전용 — 산식 미편입)
--
-- 배경: RVOL(당일 거래대금 ÷ 직전 20거래일 평균)이 "관심 쏠린 날"의 시그널 적중률에
--       영향을 주는지 사후검증하려면 시그널 시점 값이 함께 저장되어야 함 (V30/V31/V32 동일 패턴).
--       분자는 시세 캐시 거래대금, 분모는 stock_price_history 종가×거래량 근사.
--
-- 기존 행은 NULL(집계 제외), 신규 시그널부터 record() 가 best-effort 로 채움.
-- NULL = 미수집(20거래일 미만 · 시세캐시 미스 · 산출 실패) — §4c 임시값 생성 금지.

ALTER TABLE signal_outcome
    ADD COLUMN rvol_at_signal DECIMAL(8,2) NULL
        COMMENT '시그널 시점 RVOL = 당일 거래대금 ÷ 직전 20거래일 평균. NULL=미수집'
        AFTER regime_at_signal;
