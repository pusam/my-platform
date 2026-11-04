-- V68: 묵은 KIS 손익·재무상태표와 외화 DART 금액을 쓰지 않는다 (2026-11-04)
--
-- ## 왜
--
-- ① KIS 분기 손익은 사실상 연결재무제표 회사만 이어서 준다(11/4 전 종목: 연결 2,095개 중 2,083개가 2026 상반기까지 최신,
-- 별도 488개 중 11개). 끊긴 회사는 그 뒤 분기를 매출·영업이익·순이익 전부 0 으로 주거나 연간 행만 준다. 수집기가 ⓐ 0 행을
-- 실적 0 으로 더했고 ⓑ TTM 나이를 보지 않아 끊긴 지점의 4분기를 오늘 값으로 저장했다. 네이버 대조:
--   파미셀   순이익 -267억 → 2014-06 까지의 4분기(실제 최근 4분기 +466억, 부호까지 틀림)
--   HK이노엔 매출 8,465억  → 2022년 연간(실제 1조 786억)
--   두산테스나 매출 828억  → 2024년 4분기 + 0 + 0 + 0(실제 3,264억)
-- ⓒ 연속이 끊긴 자리에 0 행이 끼면 누적 환산이 연간 값을 '4분기 한 분기'로 읽었다 — 한화엔진 매출 2조 786억(실제의 약 1.5배).
-- 같은 0 행이 1년 전 기준이 되면 성장률도 지어낸 값이 됐다(와이투솔루션 매출성장 +758%, 플레이디 PEG 0.07).
-- 재무상태표·재무비율 API 도 첫 행을 나이 확인 없이 썼다 — 별도 회사 353곳 중 자본총계가 DART 와 ±3% 안인 곳은 77곳뿐
-- (연결은 2,087곳 중 2,028곳).
-- 코드는 같은 날 고쳤다(QuarterlyFinancials.isBlankReport·isStale, 수집기 나이 판정). 이 마이그레이션은 그 전에 쌓인 행을 비운다 —
-- 읽는 쪽(종합추천 가치·성장, 트랙, 상세)이 최근 10행의 첫 값을 이어 쓰므로 새 행의 null 을 옛 값으로 메우지 않게(V63 과 같은 이유).
--
-- ② DART 재무제표의 통화가 원화가 아닌 회사(달러·위안·엔)를 원화로 읽었다. 두산밥캣 PER 12,312·PBR 929(네이버 9.52·0.60),
-- 코오롱티슈진 PBR 6,950(5.78), 아남전자 PBR 1,182(0.64). 응답의 currency 칸을 보지 않았다.
-- 코드는 NON_KRW 로 적고 금액을 저장하지 않는다 — 이미 받은 행은 다시 받지 않으므로 여기서 상태를 바꾼다.
--
-- ## 대상 — 전부 KIS 일별 행(market_cap NOT NULL)만
--
-- 네이버 분기 행(market_cap NULL)은 writer 가 달라 건드리지 않는다(V56→V57 사고).
--   STALE : 그 행의 날짜 기준 실제 값이 있는 마지막 분기가 200일보다 오래됨(또는 빈 행뿐) — 손익 + 재무상태표 칸
--           (수집기·성장률·실적 서프라이즈와 같은 나이 기준. 그 날짜엔 최신이었던 행은 남긴다)
--   HOLE12: 마지막 분기 전 12개월 안에 빈 행 — 최근 4분기를 만들 수 없다 — 손익 + 성장률 칸
--   HOLE24: 그 앞 12개월에 빈 행 — 손익은 맞고 1년 전 기준이 없다 — 성장률 칸만
-- 재무상태표 칸은 신선한 회사면 다음 수집이 새 행에 다시 채운다(비운 뒤 잃는 것은 옛 행의 사본뿐).
--
-- ## 되돌리기
--
--   UPDATE stock_financial_data s JOIN stock_financial_stale_backup_v68 b ON b.id = s.id
--      SET s.revenue = b.revenue, s.operating_profit = b.operating_profit, s.net_income = b.net_income,
--          s.operating_margin = b.operating_margin, s.total_assets = b.total_assets, s.total_equity = b.total_equity,
--          s.total_debt = b.total_debt, s.debt_ratio = b.debt_ratio, s.revenue_growth = b.revenue_growth,
--          s.profit_growth = b.profit_growth, s.eps_growth = b.eps_growth, s.peg = b.peg;
--   UPDATE dart_controlling_financial SET status = 'OK' WHERE status = 'NON_KRW';
-- ⚠ 되돌리면 몇 년 전 실적과 달러 금액이 다시 오늘 값이 된다.

CREATE TABLE IF NOT EXISTS stock_financial_stale_backup_v68 (
    id               BIGINT        NOT NULL PRIMARY KEY COMMENT 'stock_financial_data.id',
    stock_code       VARCHAR(10)   NULL,
    report_date      DATE          NULL,
    reason           VARCHAR(10)   NOT NULL COMMENT 'STALE · HOLE12 · HOLE24',
    revenue          DECIMAL(15,2) NULL,
    operating_profit DECIMAL(15,2) NULL,
    net_income       DECIMAL(15,2) NULL,
    operating_margin DECIMAL(10,2) NULL,
    total_assets     DECIMAL(15,2) NULL,
    total_equity     DECIMAL(15,2) NULL,
    total_debt       DECIMAL(15,2) NULL,
    debt_ratio       DECIMAL(10,2) NULL,
    revenue_growth   DECIMAL(10,2) NULL,
    profit_growth    DECIMAL(10,2) NULL,
    eps_growth       DECIMAL(10,2) NULL,
    peg              DECIMAL(10,2) NULL,
    backed_up_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='V68 이 비우기 전 값(끊긴 KIS 분기로 만든 손익·재무상태표·성장률) — 되돌리기용';

-- 종목별 실제 값이 있는 마지막 분기(세 값이 전부 0·NULL 인 빈 행 제외)
CREATE TEMPORARY TABLE v68_real_quarter AS
SELECT stock_code,
       MAX(CASE WHEN NOT (COALESCE(revenue, 0) = 0 AND COALESCE(operating_profit, 0) = 0 AND COALESCE(net_income, 0) = 0)
                THEN period_end END) AS real_end
  FROM stock_quarterly_financial
 GROUP BY stock_code;

-- 마지막 분기 전 24개월 안에 빈 행이 있는 종목 — within_12m 이면 최근 4분기까지 끊겼다
CREATE TEMPORARY TABLE v68_quarter_hole AS
SELECT q.stock_code,
       MAX(CASE WHEN b.period_end >= DATE_SUB(q.real_end, INTERVAL 12 MONTH) THEN 1 ELSE 0 END) AS within_12m
  FROM v68_real_quarter q
  JOIN stock_quarterly_financial b ON b.stock_code = q.stock_code
 WHERE b.period_end < q.real_end
   AND b.period_end >= DATE_SUB(q.real_end, INTERVAL 24 MONTH)
   AND COALESCE(b.revenue, 0) = 0 AND COALESCE(b.operating_profit, 0) = 0 AND COALESCE(b.net_income, 0) = 0
 GROUP BY q.stock_code;

INSERT INTO stock_financial_stale_backup_v68
       (id, stock_code, report_date, reason, revenue, operating_profit, net_income, operating_margin,
        total_assets, total_equity, total_debt, debt_ratio, revenue_growth, profit_growth, eps_growth, peg)
SELECT f.id, f.stock_code, f.report_date, 'STALE', f.revenue, f.operating_profit, f.net_income, f.operating_margin,
       f.total_assets, f.total_equity, f.total_debt, f.debt_ratio, f.revenue_growth, f.profit_growth, f.eps_growth, f.peg
  FROM stock_financial_data f
  JOIN v68_real_quarter q ON q.stock_code = f.stock_code
 WHERE f.market_cap IS NOT NULL
   AND (q.real_end IS NULL OR f.report_date > DATE_ADD(q.real_end, INTERVAL 200 DAY))
   AND (f.revenue IS NOT NULL OR f.operating_profit IS NOT NULL OR f.net_income IS NOT NULL
        OR f.operating_margin IS NOT NULL OR f.total_assets IS NOT NULL OR f.total_equity IS NOT NULL
        OR f.total_debt IS NOT NULL OR f.debt_ratio IS NOT NULL);

INSERT INTO stock_financial_stale_backup_v68
       (id, stock_code, report_date, reason, revenue, operating_profit, net_income, operating_margin,
        total_assets, total_equity, total_debt, debt_ratio, revenue_growth, profit_growth, eps_growth, peg)
SELECT f.id, f.stock_code, f.report_date, IF(h.within_12m = 1, 'HOLE12', 'HOLE24'),
       f.revenue, f.operating_profit, f.net_income, f.operating_margin,
       f.total_assets, f.total_equity, f.total_debt, f.debt_ratio, f.revenue_growth, f.profit_growth, f.eps_growth, f.peg
  FROM stock_financial_data f
  JOIN v68_quarter_hole h ON h.stock_code = f.stock_code
  JOIN v68_real_quarter q ON q.stock_code = f.stock_code
 WHERE f.market_cap IS NOT NULL
   AND f.report_date <= DATE_ADD(q.real_end, INTERVAL 200 DAY)
   AND (f.revenue_growth IS NOT NULL OR f.profit_growth IS NOT NULL OR f.eps_growth IS NOT NULL OR f.peg IS NOT NULL
        OR (h.within_12m = 1 AND (f.revenue IS NOT NULL OR f.operating_profit IS NOT NULL
                                  OR f.net_income IS NOT NULL OR f.operating_margin IS NOT NULL)));

UPDATE stock_financial_data s
  JOIN stock_financial_stale_backup_v68 b ON b.id = s.id AND b.reason IN ('STALE', 'HOLE12')
   SET s.revenue = NULL, s.operating_profit = NULL, s.net_income = NULL, s.operating_margin = NULL;

UPDATE stock_financial_data s
  JOIN stock_financial_stale_backup_v68 b ON b.id = s.id AND b.reason = 'STALE'
   SET s.total_assets = NULL, s.total_equity = NULL, s.total_debt = NULL, s.debt_ratio = NULL;

UPDATE stock_financial_data s
  JOIN stock_financial_stale_backup_v68 b ON b.id = s.id AND b.reason IN ('HOLE12', 'HOLE24')
   SET s.revenue_growth = NULL, s.profit_growth = NULL, s.eps_growth = NULL, s.peg = NULL;

DROP TEMPORARY TABLE v68_quarter_hole;
DROP TEMPORARY TABLE v68_real_quarter;

-- 11/4 운영에서 DART 자본이 KIS 자본의 0.05~10% 인 24종목(달러 ×0.0005~0.0009 · 위안 ×0.004~0.007 · 엔 ×0.10)
-- — 9로 시작하는 해외 상장사 22곳 + 두산밥캣·아남전자. 앞으로 받는 보고서는 코드가 currency 칸으로 거른다.
UPDATE dart_controlling_financial
   SET status = 'NON_KRW'
 WHERE status = 'OK'
   AND stock_code IN ('008700', '241560', '900070', '900100', '900110', '900120', '900140', '900250', '900260', '900270',
                      '900290', '900300', '900310', '900340', '950130', '950140', '950160', '950170', '950190', '950200',
                      '950210', '950220', '950250', '950260');
