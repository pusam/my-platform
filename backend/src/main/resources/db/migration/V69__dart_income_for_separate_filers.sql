-- V69: DART 재무제표의 매출·영업이익·자산·부채 보존 (2026-11-05)
--
-- ## 왜
--
-- KIS 분기 손익은 사실상 연결재무제표 회사만 이어서 준다(2026-11-04 교차표: 연결 2,083/2,095 최신, 별도 11/488).
-- V68 이 끊긴 값을 비운 뒤로 별도재무제표 회사 약 490종목은 매출·영업이익·순이익·영업이익률·성장률·실적 축을 '모름'이다 —
-- 틀린 값은 없어졌지만 종합추천 실적 축 20점을 받을 수 없어 구조적으로 불리하고 마법의 공식·PEG 후보에서도 빠진다.
-- 리노공업(시총 4.2조)·티씨케이·카카오뱅크·코스모신소재·펩트론이 여기 있다.
--
-- DART 전체 재무제표(fnlttSinglAcntAll)는 이미 종목당 보고서를 받고 있다 — 지배주주 순이익·자본만 꺼내고 나머지를 버렸을 뿐이다.
-- 이 표에 매출·영업이익·자산·부채를 같이 보존하고, 그걸로 개별 분기를 만들어 stock_quarterly_financial 에 source=DART_FNLTT 로 넣는다.
--
-- ## 칸의 뜻 (§4c)
--   revenue/operating_profit  : 분·반기는 연초부터 누적, 사업보고서는 연간(억원). NULL = 그 계정이 없었다(0 아님 — 은행은 매출 행이 없다)
--   total_assets/total_liabilities : 보고서 시점 값(억원)
--   income_parsed             : 이 네 칸까지 읽은 행인가. 이 마이그레이션 전에 받은 행은 0 — 대상 종목만 다시 받는다
--   stock_quarterly_financial.source = DART_FNLTT 행은 cumulative=0 이고 **이미 개별 분기다** — 환산은 DartQuarterly 가 저장 전에 했다.
--   KIS 행과 달리 누적 판정을 거치지 않는다(QuarterlyFinancials.usableFromStored).
ALTER TABLE dart_controlling_financial
    ADD COLUMN revenue DECIMAL(15,2) NULL COMMENT '매출액(억원) — 분·반기는 누적, 사업보고서는 연간',
    ADD COLUMN operating_profit DECIMAL(15,2) NULL COMMENT '영업이익(억원) — 분·반기는 누적, 사업보고서는 연간',
    ADD COLUMN total_assets DECIMAL(15,2) NULL COMMENT '자산총계(억원)',
    ADD COLUMN total_liabilities DECIMAL(15,2) NULL COMMENT '부채총계(억원)',
    ADD COLUMN income_parsed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '매출·영업이익·자산·부채까지 읽은 행인가';
