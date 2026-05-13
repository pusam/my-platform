-- V27: signal_outcome 에 BM(코스피) 대비 alpha 컬럼 추가
--
-- 배경: 기존 평가는 "3일 후 +3% 이상 → hit". 그러나 KOSPI 가 그날 +4% 올랐다면 시그널은
--       시장을 못 이긴 것. 시장 베타만 먹은 종목과 진짜 시그널의 실력을 구분 못 함.
--
-- 신규 컬럼:
--   bm_return_3d: 같은 기간 KOSPI 종합지수 변동률 (%)
--   alpha_3d:    pct_change_3d - bm_return_3d (초과수익률 %)
--
-- hit 기준 변경 (서비스 코드):
--   기존: pct_change_3d >= 3.0
--   신규: alpha_3d >= 0.0 (시장 대비 양의 초과수익) AND pct_change_3d >= 0
--
-- 기존 데이터는 컬럼만 NULL 로 남고, 신규 평가부터 채워짐. 기존 hit 컬럼은 그대로 유지.

ALTER TABLE signal_outcome
    ADD COLUMN bm_price_at_signal DECIMAL(15,2) NULL COMMENT 'KOSPI 시그널 발생 시점 지수값' AFTER price_at_signal,
    ADD COLUMN bm_return_3d DECIMAL(10,4) NULL COMMENT 'KOSPI 3일 변동률 %' AFTER pct_change_3d,
    ADD COLUMN alpha_3d DECIMAL(10,4) NULL COMMENT '초과수익률 = pct_change_3d - bm_return_3d' AFTER bm_return_3d;
