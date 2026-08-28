-- V56: 재무 금액 100배 보정 (2026-08-28)
--
-- ## 무엇이 틀렸나
--
-- 수집기가 KIS 손익계산서·재무상태표 금액을 "백만원 → 억원"이라며 /100 했는데,
-- **원본이 이미 억원**이었다. 그래서 모든 금액이 100배 작게 저장됐다.
--
-- 실측 근거(2026-08-28):
--   005930  자본총계 57,931 / 시총 15,375,713  →  PBR 265
--   000660  자본총계 26,269 / 시총 12,535,249  →  PBR 477
-- 어떤 회사에도 불가능한 값이다. 시총 단위와 무관하게도 확인된다 —
-- 삼성전자 TTM 매출이 4.85조(48,527억)일 수 없다.
--
-- ⚠ PBR 일관성 가드(PBR ≈ PER × ROE / 100)가 그 말도 안 되는 PBR 을 덮어써서
--   화면엔 정상으로 보였다. **가드가 단위 버그를 가리고 있었다.**
--
-- ## 왜 마이그레이션이 필요한가
--
-- 코드만 고치면 오늘부터의 행만 옳고 어제까지의 행은 100배 작은 채로 남는다.
-- `FinancialRowSynthesizer` 가 최근 10행에서 필드별로 값을 주워 합성하므로
-- **한 종목의 매출은 오늘 값, 자본총계는 어제 값**이 되는 혼합이 생긴다.
-- 그 상태로 계산한 ROE·PBR 은 100배 틀린다.
--
-- ## 범위
--
-- 값이 들어오기 시작한 것은 2026-08-27 tr_id 정정 이후다(그 전엔 전부 NULL).
-- 즉 실제 대상은 이틀치뿐이고, NULL 은 그대로 둔다(결측을 0 이나 값으로 만들지 않는다, §4c).
--
-- 비율 컬럼(per/pbr/roe/영업이익률 등)은 건드리지 않는다 — 스케일 불변이거나 주가 기반이다.
-- market_cap 도 건드리지 않는다 — 다른 API 에서 오고 단위가 맞다(이 판정의 기준점이었다).

UPDATE stock_financial_data
SET revenue          = revenue * 100,
    operating_profit = operating_profit * 100,
    net_income       = net_income * 100,
    total_assets     = total_assets * 100,
    total_equity     = total_equity * 100,
    total_debt       = total_debt * 100
WHERE revenue IS NOT NULL
   OR operating_profit IS NOT NULL
   OR net_income IS NOT NULL
   OR total_assets IS NOT NULL
   OR total_equity IS NOT NULL
   OR total_debt IS NOT NULL;

-- 분기 원본도 같은 /100 을 거쳤다(toEokWon).
-- 다음 배치가 UPSERT 로 덮어쓰긴 하지만, 그 전까지 서프라이즈 요약 문구의 금액이 100배 작다.
UPDATE stock_quarterly_financial
SET revenue          = revenue * 100,
    operating_profit = operating_profit * 100,
    net_income       = net_income * 100
WHERE revenue IS NOT NULL
   OR operating_profit IS NOT NULL
   OR net_income IS NOT NULL;
