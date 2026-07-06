-- 간밤 미국장 tilt 일일 스냅샷 (P3-5, 표시 전용 · unverified · 산식 미편입)
--
-- 배경: 간밤 미국장 tilt(BULL/NEUTRAL/BEAR, 2026-06-30 작업3)는 '오늘' 탭에 표시만 되고
--       판정값이 영속되지 않아 사후검증(캘리브레이션 P3-5)이 불가했다. V39 macro_tilt_snapshot
--       패턴 그대로 복제 — 매일 1행에 판정 + 판정 입력을 함께 기록해 KOSPI 익일 시초가 대비
--       적중률을 나중에 측정할 수 있게 한다.
--
-- 산식 무관: python regime v1 / 추천 점수 / 봇에 미편입 — 측정 전용 스냅샷.
-- 값 컬럼 전부 NULL 허용 = 미수집(§4c: 가짜값 금지 — Yahoo 미가용 축은 NULL 그대로).
-- 판정 입력 4종(ES/NQ/SOX 등락률 + VIX 레벨)을 결과와 함께 저장 — 사후검증 시 당시 입력 재현이 목적.

CREATE TABLE IF NOT EXISTS overnight_us_snapshot (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_date   DATE          NOT NULL COMMENT '스냅샷 일자 (08:10 KST 크론)',
    tilt            VARCHAR(10)   NOT NULL COMMENT 'BULL / NEUTRAL / BEAR (간밤 미국장 tilt — regime v1 어휘와 별개)',
    -- 판정 입력 4종 (classifyOvernight 재현용)
    es_rate         DECIMAL(6,2)  NULL COMMENT 'S&P500(ES) 등락률 %. NULL=미수집',
    nq_rate         DECIMAL(6,2)  NULL COMMENT '나스닥100(NQ) 등락률 %. NULL=미수집',
    sox_rate        DECIMAL(6,2)  NULL COMMENT '필라델피아 반도체(SOX) 등락률 %. NULL=미수집',
    vix_level       DECIMAL(6,2)  NULL COMMENT 'VIX 레벨. NULL=미수집',
    -- 참고 맥락 (classify 입력 아님)
    sox_level       DECIMAL(10,2) NULL COMMENT '^SOX 레벨 (참고 맥락). NULL=미수집',
    trading_time    VARCHAR(40)   NULL COMMENT 'Yahoo 체결시각 문자열(사후검증 신선도 필터용, 사용자가 본 그대로)',
    -- 비교 기준 동시 스냅
    regime_v1       VARCHAR(10)   NULL COMMENT '당일 python regime v1 (BULL/BEAR/SIDEWAYS). NULL=미수집',
    drivers         VARCHAR(300)  NULL COMMENT '표시된 drivers 문자열 (사용자가 본 그대로)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 일 1행 — 같은 날 재실행 시 UPSERT (snapshot_date 유일)
    UNIQUE KEY uk_ous_snapshot_date (snapshot_date),
    KEY idx_ous_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
