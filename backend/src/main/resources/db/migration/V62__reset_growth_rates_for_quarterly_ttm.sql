-- V62: 성장률 4종(매출·순이익·EPS·PEG)을 비우고 새 정의로 다시 채운다 (2026-09-29)
--
-- ## 왜
--
-- 성장률 배치(calculateAndUpdateGrowthRates)는 최신 일별 행(TTM)을 이 테이블의 "1년 전 ±30일" 아무 행과
-- 비교했다. 그 행이 한 분기짜리이거나 단위 오류 시절 행이면 매출이 +300% 가 됐고, 1년 전 행이 없으면
-- 30일 전 행을 "YoY"로 썼다. 운영 실측(9/28): 매출 성장률이 채워진 2,156종목 중 2,048종목이 ±200% 초과
-- (베뉴지 매출 +371% → 실제 TTM +9.3%). 게다가 값이 0/NULL 일 때만 채워서 한 번 들어간 값은 고쳐지지 않았다.
--
-- 새 배치는 분기 원본(V55 stock_quarterly_financial) TTM 전년 동기 대비로 **최신 일별 행만** 매일 덮어쓴다.
-- 그런데 읽는 쪽(종합추천 scoreGrowth·5트랙 합성)은 최근 10행에서 0 이 아닌 첫 값을 집으므로
-- (firstNonZero), 새 값이 "모름(NULL)"인 종목은 옛 행의 엉터리 값으로 떨어진다. 그래서 옛 값을 비운다.
-- 비운 직후의 공백은 기동 시 따라잡기(FinancialDataScheduler.catchUpGrowthRates)가 곧바로 메운다
-- — 분기 원본만 읽고 KIS 는 부르지 않는다.
--
-- ## 대상 — KIS 일별 행만 (writer 구분)
--
-- 이 테이블은 writer 가 둘이다(V56→V57 사고). 성장률 배치가 쓰는 것은 KIS 일별 행이고, 그 행은
-- market_cap 이 값 또는 0 이다(NULL 이 아니다). 네이버 분기 행(market_cap NULL, 2026-09-23 크롤 은퇴)은
-- 건드리지 않는다 — 운영 실측(9/29) 어느 종목의 최근 10행에도 들어가지 않아 읽히지 않는다.
--
-- ## 되돌리기
--
-- 비우기 전 값을 stock_financial_growth_backup_v62 에 행 id 로 남긴다. 복구:
--   UPDATE stock_financial_data s JOIN stock_financial_growth_backup_v62 b ON b.id = s.id
--      SET s.eps_growth = b.eps_growth, s.profit_growth = b.profit_growth,
--          s.revenue_growth = b.revenue_growth, s.peg = b.peg;
-- ⚠ 복구하면 옛 정의(기준이 섞인 비교) 값이 돌아온다 — 새 배치가 다음 회차에 최신 행만 다시 덮는다.

CREATE TABLE IF NOT EXISTS stock_financial_growth_backup_v62 (
    id             BIGINT        NOT NULL PRIMARY KEY COMMENT 'stock_financial_data.id',
    stock_code     VARCHAR(10)   NULL,
    report_date    DATE          NULL,
    eps_growth     DECIMAL(10,2) NULL,
    profit_growth  DECIMAL(10,2) NULL,
    revenue_growth DECIMAL(10,2) NULL,
    peg            DECIMAL(10,2) NULL,
    backed_up_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='V62 가 비우기 전 성장률 4종(KIS 일별 행) — 되돌리기용';

INSERT INTO stock_financial_growth_backup_v62
       (id, stock_code, report_date, eps_growth, profit_growth, revenue_growth, peg)
SELECT id, stock_code, report_date, eps_growth, profit_growth, revenue_growth, peg
  FROM stock_financial_data
 WHERE market_cap IS NOT NULL
   AND (eps_growth IS NOT NULL OR profit_growth IS NOT NULL
        OR revenue_growth IS NOT NULL OR peg IS NOT NULL);

UPDATE stock_financial_data
   SET eps_growth = NULL,
       profit_growth = NULL,
       revenue_growth = NULL,
       peg = NULL
 WHERE market_cap IS NOT NULL
   AND (eps_growth IS NOT NULL OR profit_growth IS NOT NULL
        OR revenue_growth IS NOT NULL OR peg IS NOT NULL);
