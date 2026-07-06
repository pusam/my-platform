-- V42: ATR 청산+리스크 균등 사이징 세트 (VIRTUAL 전용 · flag `bot.atr-trading.enabled` 기본 OFF)
--
-- 배경: exit 백테스트(exit_backtest_2026-07-07)에서 ATR×2.5 청산이 고정 -3/+5 전면 우위 —
--       단 개별 손실 확대 트레이드오프가 있어 리스크 균등 사이징과 세트로만, VIRTUAL 에서 검증부터.
--       설계/매핑은 docs/ATR_TRADING_SET.md.
--
-- bot_trading_position: 진입 시점 ATR 스냅샷(사후 변동 반영 금지) — 재시작 복원에 필요.
--   NULL = 현행 고정 청산 포지션(flag OFF/REAL/ATR 결측 — §4c 결측이면 완전 현행).
-- bot_config: 종목당 리스크 예산 오버라이드(전용 행 config_key='atr_trading' 이 소유,
--   'trading_bot'/'daily_loss_breaker' 행 분리 원칙 유지). NULL = 일일 손실 브레이커 한도 ÷ 6.

ALTER TABLE bot_trading_position
    ADD COLUMN entry_atr DECIMAL(15,4) NULL COMMENT '진입 시점 ATR14(원, Wilder). NULL=현행 고정 청산',
    ADD COLUMN atr_stop_pct DECIMAL(8,4) NULL COMMENT 'ATR 손절 레벨(음수 %) — 진입 스냅샷 고정',
    ADD COLUMN atr_target_pct DECIMAL(8,4) NULL COMMENT 'ATR 익절 레벨(양수 %) — 진입 스냅샷 고정';

ALTER TABLE bot_config
    ADD COLUMN atr_risk_budget_krw DECIMAL(15,2) NULL COMMENT 'ATR 세트 종목당 리스크 예산(원). NULL=브레이커 한도÷6(기본 5만)';
