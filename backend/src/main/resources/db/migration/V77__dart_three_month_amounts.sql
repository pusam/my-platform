-- V77: DART 반기·3분기 보고서의 3개월 금액 칸 (2026-11-12)
--
-- ## 왜
--
-- 별도재무제표 회사의 분기는 보고서 누적의 차로 만든다(V69: 2분기 = 반기 누적 − 1분기 보고서 값). 그런데 나중 보고서가 앞 분기를 다시 적는
-- 회사가 있다. 앤로보틱스(138360) 2026 1분기 보고서는 매출 23.85억·영업손실 1.11억인데, 공개 수치로는 1분기 매출 90억·영업이익 4억,
-- 2분기 77억·0억이다(반기 누적 167.57억은 같다). 앞 보고서 값으로 빼서 2분기가 143.72억·4.87억이 됐고 '2분기 연속 적자 뒤 흑자전환'
-- (실적 20점)으로 읽혔다(2026-11-11 실적 판정 외부 대조).
--
-- OpenDART 단일회사 전체 재무제표의 분·반기 손익 행은 3개월 금액(thstrm_amount)과 누적(thstrm_add_amount)을 따로 준다. 지금까지는 누적만
-- 저장했다. 3개월 금액을 같이 두면 '반기 누적 − 반기보고서의 3개월'로 반기보고서 기준의 1분기를 알 수 있다 — 분기 값은 가장 나중 보고서의
-- 기준을 따른다(DartQuarterly.latestBasisQuarters).
--
-- quarter_parsed 는 3개월 칸을 읽으려 해 본 행인지다. 분기를 채우는 종목의 반기·3분기 보고서(종목당 최대 5건)만 21:30 회차가 한 번 다시
-- 받는다 — 있던 금액은 건드리지 않고 이 세 칸만 채운다. 부팅 따라잡기는 걸지 않았다(장 마감 뒤 배포 직후에 DART 를 몰아 부르지 않게).
ALTER TABLE dart_controlling_financial
    ADD COLUMN revenue_q DECIMAL(15,2) NULL COMMENT '이 보고서의 3개월 매출(억원) — 반기·3분기 보고서만',
    ADD COLUMN operating_profit_q DECIMAL(15,2) NULL COMMENT '이 보고서의 3개월 영업이익(억원)',
    ADD COLUMN total_net_income_q DECIMAL(15,2) NULL COMMENT '이 보고서의 3개월 당기순이익(억원, 비지배 포함)',
    ADD COLUMN quarter_parsed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '3개월 칸을 읽으려 해 본 행인가';
