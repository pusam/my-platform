-- V44: manual_trade_journal 에 매수 시점 KOSPI(벤치마크) 가격 추가 — 자동 평가(Phase 2) alpha_3d 계산용.
--
-- signal_outcome.bm_price_at_signal 과 동일 패턴: 기록 시점에 KIS 지수(0001) 현재가를 스냅샷하고,
-- 3거래일 후 평가 배치가 (지수now-지수buy)/지수buy 로 bm 수익률을 구해 alpha = 종목수익 - 지수수익.
-- NULL = 미수집(KIS 미설정/조회 실패, §4c) — 평가 시 alpha 없이 pct ≥ +3% 폴백으로 hit 판정.

ALTER TABLE manual_trade_journal
    ADD COLUMN bm_price_at_buy DECIMAL(15,2) NULL
        COMMENT '매수 시점 KOSPI 종합지수(0001) 현재가. NULL=미수집(KIS 미설정/실패)'
        AFTER five_day_return;
