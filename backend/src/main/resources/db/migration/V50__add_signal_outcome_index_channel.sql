-- V50: signal_outcome 에 KOSPI 지수(0001) 추세 채널 스냅샷 추가 (측정 전용 — 산식 미편입)
--
-- 배경: V49(종목 채널)와 동형이되 입력만 지수(0001). python regime v1(종가 vs MA60 + MA20 슬로프
--       → BULL/BEAR/SIDEWAYS)은 이분법이라 지수의 "위치"(채널 상/하단)를 못 본다 — 2026-07-13 -8.95%
--       급락일도 MA60 위면 BULL. 지수 채널 위치가 regime 대비 추가 예측력이 있는지 사후검증하려면
--       시그널 시점의 지수 채널 상태를 함께 저장해야 한다(V30 카테고리 / V32 regime / V46 vol_regime 동일 패턴).
--
-- 항상 best-effort 캡처. 기존 행은 NULL(집계 제외). NULL = 미수집(봉<10 · 지수 고저가 결측 · 조회 실패)
--       — §4c 가짜 FLAT·중앙값 위장 금지.
-- ⚠ 검증 데이터가 유의해지기 전엔 점수/시그널/봇/추천/regime 산식 편입 금지(P2-12 교훈 — 차트 신호 31% 역상관).
--
-- ⚠ width_pct 를 저장하는 이유(재현용): TrendChannelCalculator 는 고저가 최대이탈 평행 채널이라
--    이상치 1봉이 폭 전체를 결정한다. 현재 30봉 창에 2026-07-13(-8.95%, 서킷브레이커)이 포함돼
--    향후 ~6주간 position 이 중앙으로 압축된다. 폭을 저장해야 사후에 "폭 N% 이하 창만" 필터해
--    재집계할 수 있다(V39 가 tilt 판정값과 입력 3종을 재현용으로 함께 저장한 것과 동일 원칙).

ALTER TABLE signal_outcome
    ADD COLUMN index_channel_direction_at_signal VARCHAR(10) NULL
        COMMENT '시그널 시점 KOSPI 지수 회귀채널 방향 (UP/DOWN/FLAT). NULL=미수집'
        AFTER channel_position_at_signal,
    ADD COLUMN index_channel_position_at_signal INT NULL
        COMMENT '시그널 시점 KOSPI 지수 채널 내 위치 0~100 (0=하단/100=상단). NULL=미수집'
        AFTER index_channel_direction_at_signal,
    ADD COLUMN index_channel_width_pct_at_signal DECIMAL(8,2) NULL
        COMMENT '시그널 시점 KOSPI 지수 채널 폭 % ((상단-하단)/중심선*100). 이상치 급락일 압축 재현/필터용. NULL=미수집'
        AFTER index_channel_position_at_signal;
