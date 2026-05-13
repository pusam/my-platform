-- V28: signal_outcome 에 MFE / MAE 컬럼 추가 (phase 25)
--
-- 배경: 기존 평가는 3일 후 종가만 비교. 그러나 시그널 직후 +5% 까지 갔다가 -2% 로 마감한 경우와
--       단조 상승 +3% 로 마감한 경우는 동일한 hit 으로 잡힘. 실제 트레이딩에서 익절/손절 라인을
--       어디에 둘지 결정하는 데 MFE/MAE 가 핵심.
--
-- 신규 컬럼:
--   max_high_3d:    시그널 이후 3거래일간 최고가 (절대값)
--   max_low_3d:     시그널 이후 3거래일간 최저가 (절대값)
--   mfe_pct_3d:     Max Favorable Excursion — (max_high - price_at_signal) / price_at_signal × 100
--   mae_pct_3d:     Max Adverse Excursion — (max_low - price_at_signal) / price_at_signal × 100 (음수)
--
-- 평가 batch (SignalOutcomeService.evaluatePendingSignals) 가 KIS getDailyOhlcv 로 채움.
-- 기존 데이터는 NULL, 신규 평가부터 누적. 데이터 1~2주 후 익절/손절 라인 최적화 분석 가능.

ALTER TABLE signal_outcome
    ADD COLUMN max_high_3d DECIMAL(15,2) NULL COMMENT '시그널 후 3거래일 최고가' AFTER price_after_3d,
    ADD COLUMN max_low_3d DECIMAL(15,2) NULL COMMENT '시그널 후 3거래일 최저가' AFTER max_high_3d,
    ADD COLUMN mfe_pct_3d DECIMAL(10,4) NULL COMMENT 'Max Favorable Excursion % (양수)' AFTER alpha_3d,
    ADD COLUMN mae_pct_3d DECIMAL(10,4) NULL COMMENT 'Max Adverse Excursion % (음수)' AFTER mfe_pct_3d;
