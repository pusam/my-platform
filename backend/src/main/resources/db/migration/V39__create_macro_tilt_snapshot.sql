-- 매크로 tilt 일일 스냅샷 (P3-7, 표시 전용 · unverified · 산식 미편입)
--
-- 배경: 매크로 3축(VKOSPI 레벨 · 국고3년 20거래일 추세 · SOX 5거래일 추세)으로
--       RISK_ON/NEUTRAL/RISK_OFF tilt 를 '오늘' 탭에 보조 표시(간밤 미국장 tilt 패턴 복제).
--       간밤 tilt 와 달리 판정값을 매일 1행 영속화 — 주간 리포트에서 regime v1 대비
--       예측력을 사후 측정하는 것이 승격 조건이므로 기록이 없으면 검증 자체가 불가.
--
-- 산식 무관: python regime v1 / 추천 점수 / 봇에 미편입 — 측정 전용 스냅샷.
-- 값 컬럼 전부 NULL 허용 = 미수집(§4c: 가짜값 금지). 특히 ECOS 키 발급 전엔 금리 축이,
-- 콜드스타트 ~5거래일간 sox_trend_pct 가 NULL(의도된 웜업 — 자체 스냅샷 축적으로 추세 산출).
-- 판정 "입력 3종 + 관측일"을 결과와 함께 저장 — 사후검증 시 당시 입력의 재현이 목적.

CREATE TABLE IF NOT EXISTS macro_tilt_snapshot (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_date   DATE          NOT NULL COMMENT '스냅샷 일자 (08:15 KST 크론)',
    tilt            VARCHAR(10)   NOT NULL COMMENT 'RISK_ON / NEUTRAL / RISK_OFF (regime v1 어휘와 의도적 분리)',
    -- 판정 입력 3종 (재현용) + 참고 맥락
    vkospi          DECIMAL(6,2)  NULL COMMENT 'VKOSPI 레벨 (KIS 업종코드 0503 일봉 최신 종가). NULL=미수집',
    ktb3y           DECIMAL(6,3)  NULL COMMENT '국고채 3년 금리 %(ECOS 817Y002 최신). NULL=미수집(키 미발급 포함)',
    rate_trend_bp   DECIMAL(7,2)  NULL COMMENT '국고3년 ~20거래일 변화(bp). NULL=미수집/시계열 부족',
    sox_level       DECIMAL(10,2) NULL COMMENT '^SOX 레벨 (Yahoo 단건 시세) — 추세 축적의 원료. NULL=미수집',
    sox_trend_pct   DECIMAL(6,2)  NULL COMMENT 'SOX ~5거래일 추세 %(자체 스냅샷 대비). NULL=웜업/미수집',
    base_rate       DECIMAL(5,2)  NULL COMMENT '한은 기준금리 %(ECOS 722Y001, 참고 맥락 — classify 입력 아님)',
    -- 관측일 (08:15 스냅 시 VKOSPI=T-1, ECOS=T-1~2 — 사후검증 신선도 필터용)
    vkospi_date     DATE          NULL COMMENT 'VKOSPI 관측 영업일',
    rate_date       DATE          NULL COMMENT '국고3년 관측 영업일',
    sox_asof        DATE          NULL COMMENT 'SOX 추세 기준(비교 대상 과거 스냅샷) 일자',
    -- 비교 기준 동시 스냅
    regime_v1       VARCHAR(10)   NULL COMMENT '당일 python regime v1 (BULL/BEAR/SIDEWAYS). NULL=미수집',
    drivers         VARCHAR(300)  NULL COMMENT '표시된 drivers 문자열 (사용자가 본 그대로)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 일 1행 — 같은 날 재실행 시 UPSERT (snapshot_date 유일)
    UNIQUE KEY uk_mts_snapshot_date (snapshot_date),
    KEY idx_mts_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
