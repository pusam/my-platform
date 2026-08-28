-- V57: V56 이 과잉 적용된 행 되돌리기 (2026-08-28)
--
-- ## 무엇이 잘못됐나
--
-- V56 은 재무 금액을 일괄 ×100 했는데, `stock_financial_data` 에는 **writer 가 둘**이다:
--
--   ① KIS 수집기 (일별 스냅샷)  — report_date = 수집일. /100 버그가 **여기에만** 있었다
--   ② 네이버 크롤 (분기/연간)   — report_date = 회계기간 말일. 단위가 원래 맞았다
--
-- V56 이 ②까지 곱해서 100배 부풀렸다. 실측(005930):
--   KIS   2026-08-28  매출 4,852,720   / 시총 15,375,713 = 0.32  ✓ V56 이 맞게 고침
--   네이버 2026-12-31  매출 732,473,200 / 시총 15,375,713 = 47.6 ✗ 과잉 (원래 7,324,732 = 0.48 정상)
--
-- 무해하지 않다 — `findLatestPerStock`·`findRecentPerStock` 는 report_date 최신 행을 집으므로
-- 미래 날짜 연간 행(2026-12-31)이 저평가·성장 트랙과 FinancialRowSynthesizer 에 먼저 들어간다.
--
-- ## 되돌리는 대상을 어떻게 가리나
--
-- 두 조건을 **모두** 만족할 때만 되돌린다(보수적):
--   - market_cap IS NULL      네이버 크롤은 시총을 안 채운다(KIS 일별 행은 값 또는 0)
--   - report_date 가 3/6/9/12월 **말일**   회계기간 말일 = 네이버 행의 형태
--
-- KIS 일별 행이 우연히 분기말에 걸릴 수는 있으나 그 행은 market_cap 이 NULL 이 아니다.
-- 두 조건 교집합이라 오탐 가능성이 낮다.
--
-- ⚠ 교훈: 한 테이블에 writer 가 둘 이상이면 일괄 UPDATE 하지 말 것.
--    V56 에 이 조건을 처음부터 넣었어야 했다.

UPDATE stock_financial_data
SET revenue          = revenue / 100,
    operating_profit = operating_profit / 100,
    net_income       = net_income / 100,
    total_assets     = total_assets / 100,
    total_equity     = total_equity / 100,
    total_debt       = total_debt / 100
WHERE market_cap IS NULL
  AND MONTH(report_date) IN (3, 6, 9, 12)
  AND DAY(report_date) = DAY(LAST_DAY(report_date))
  AND (revenue IS NOT NULL
       OR operating_profit IS NOT NULL
       OR net_income IS NOT NULL
       OR total_assets IS NOT NULL
       OR total_equity IS NOT NULL
       OR total_debt IS NOT NULL);
