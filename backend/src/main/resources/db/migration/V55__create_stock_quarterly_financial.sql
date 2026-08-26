-- V55: 분기 재무 원본 보존 (stock_quarterly_financial) — AUDIT 2026-08-21 R1
--
-- ## 왜 새 테이블인가
--
-- `stock_financial_data` 는 이름과 달리 **분기 재무가 아니라 일별 스냅샷**이다.
-- 수집기가 매일 `report_date = LocalDate.now()` 로 TTM(최근 4분기 합) + 당일 주가 기반
-- 밸류에이션(PER/PBR)을 한 행씩 쌓는다. 그 자체는 옳다 — PER/PBR 은 매일 변하는 값이라
-- 일별 스냅샷이 맞다.
--
-- 문제는 **어닝 서프라이즈가 그 테이블을 '분기'로 읽는다**는 것이다.
-- `findLatestTwoQuartersPerStock` 은 report_date 최신 2행을 뽑는데, 그건 "오늘 vs 어제"다.
-- 인접분기 가드(≤120일)는 gap=1 이라 통과하고, TTM 은 하루 사이 거의 안 변하니
-- 변화율 ≈ 0 → 임계 ±20% 미달 → 서프라이즈 미발생. **earnings 카테고리가 사실상 死**였다.
-- `isEarningsReportFresh(200일)` 도 report_date 가 항상 오늘이라 no-op 이었다.
--
-- ## 데이터는 이미 받고 있었다 — 버리고 있었을 뿐
--
-- 수집기는 KIS 손익계산서 API(FHKST66430300, FID_DIV_CLS_CODE=1)로 **분기별 행**을
-- 매번 받아온다. 각 행에 `stac_yymm`(결산년월, 예: 202506)이 들어 있는데, TTM 합산에만
-- 쓰고 분기 정체성은 그대로 버렸다. 이 테이블은 **그 버려지던 행을 그대로 보존**한다.
-- 새 API 호출은 없다.
--
-- ## 두 테이블의 역할 분리 (섞지 말 것)
--   stock_financial_data        = 일별 스냅샷 (TTM 합 + 당일 주가 기반 PER/PBR/ROE)
--   stock_quarterly_financial   = 분기 원본 (stac_yymm 단위, 주가 무관)
-- 밸류에이션 소비자는 전자를, 실적 변화 판정은 후자를 본다.
--
-- ## NULL/플래그 의미 (§4c)
--   - revenue/operating_profit/net_income NULL = 그 항목을 API 가 안 줬음(0 아님, 비교에서 제외)
--   - cumulative=1 = 원본이 누적(YTD) 이었음. **값을 저장 시점에 보정하지 않는다** —
--     받은 그대로 두고, 개별 분기 환산은 읽는 쪽 순수함수가 한다(원본 훼손 금지).
--     같은 회계연도 안에서 직전 누적을 빼야 하는데, 그 직전 행이 결측일 수 있어
--     "환산 가능할 때만 환산"이 정직하다.
--   - period_end = fiscal_period 의 말일. 정렬·인접분기(3개월) 판정용 파생값이다.

CREATE TABLE IF NOT EXISTS stock_quarterly_financial (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL COMMENT '종목코드',
    fiscal_period VARCHAR(6) NOT NULL COMMENT 'KIS stac_yymm 원본 (YYYYMM, 예: 202506)',
    period_end DATE NOT NULL COMMENT 'fiscal_period 말일 — 정렬·인접분기 판정용 파생값',
    cumulative TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=원본이 누적(YTD). 값은 미보정, 환산은 읽는 쪽에서',
    revenue DECIMAL(15,2) COMMENT '매출액 (억원). NULL=API 미제공(0 아님)',
    operating_profit DECIMAL(15,2) COMMENT '영업이익 (억원). NULL=API 미제공(0 아님)',
    net_income DECIMAL(15,2) COMMENT '당기순이익 (억원). NULL=API 미제공(0 아님)',
    source VARCHAR(20) NOT NULL COMMENT '수집 출처 — 현재 KIS_INCOME_STMT 하나',
    collected_at DATETIME NOT NULL COMMENT '이 행을 마지막으로 받아 쓴 시각',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_sqf_stock_period (stock_code, fiscal_period),
    INDEX idx_sqf_stock_end (stock_code, period_end),
    INDEX idx_sqf_end (period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='분기 재무 원본 (KIS 손익계산서 stac_yymm 단위) — 일별 스냅샷 테이블과 역할 분리';
