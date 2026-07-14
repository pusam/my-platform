-- V49: signal_outcome 에 추세 채널(회귀 채널) 스냅샷 추가 (측정 전용 — 산식 미편입)
--
-- 배경: 추세 채널(2026-07-14, 종목상세 차트·종합판단 보드 표시 전용)이 실제 예측력이 있는지
--       ("상승 채널 하단 = 눌림목 매수가 실제로 먹히나 / 상단 = 추격 위험이 실측되나")
--       사후검증하려면 시그널 시점의 채널 상태가 함께 저장되어야 한다
--       (V30 카테고리 / V31 재료 / V32 regime / V41 RVOL / V46 vol_regime 동일 패턴).
--
-- 항상 best-effort 캡처(데이터 축적 목적). 기존 행은 NULL(집계 제외).
-- NULL = 미수집(히스토리 <10거래일·가격 결측) — §4c 임시값 생성 금지.
-- ⚠ 검증 데이터가 유의해지기 전엔 점수/시그널/봇 산식 편입 금지(P2-12 교훈 — 차트 신호 31% 역상관).

ALTER TABLE signal_outcome
    ADD COLUMN channel_direction_at_signal VARCHAR(10) NULL
        COMMENT '시그널 시점 회귀채널 방향 (UP/DOWN/FLAT). NULL=미수집(히스토리 부족)'
        AFTER vol_regime_at_signal,
    ADD COLUMN channel_position_at_signal INT NULL
        COMMENT '시그널 시점 채널 내 위치 0~100 (0=하단/100=상단). NULL=미수집'
        AFTER channel_direction_at_signal;
