-- V65: DART 기업개황 결산월 (dart_company) (2026-10-01)
--
-- ## 왜
--
-- V64 의 지배주주 최근 4분기(TTM) = 최신 분·반기 누적 + 직전 연도 사업보고서 − 전년 동기 누적. 짝(직전 연도
-- 사업보고서)과 조회 계획이 12월 결산 기준이라, 결산월이 다른 회사는 어긋난 보고서로 틀린 값을 만들었다 —
-- 동원모빌리티(3월 결산): '2026 1분기' + '2025년 3월 결산 사업보고서'로 296억(실제 475억, 네이버) → PER 1.3 이 2.1 로.
-- 그래서 결산월을 받아 두고 12월 결산일 때만 순이익을 만든다(모르면 만들지 않는다). 지배지분 자본은 최신 보고서의
-- 시점 값이라 결산월과 무관하게 쓴다.
--
-- ## 이 표
--
-- 종목 하나 = 한 행. fiscal_month = DART company.json 의 acc_mt("01"~"12"). NULL = 조회했지만 값이 없었다(7일 뒤 재조회).
-- 결산월은 거의 바뀌지 않아 한 번 받으면 다시 받지 않는다.

CREATE TABLE IF NOT EXISTS dart_company (
    stock_code VARCHAR(10) NOT NULL PRIMARY KEY COMMENT '종목코드',
    corp_code VARCHAR(8) NOT NULL COMMENT 'DART 고유번호',
    fiscal_month VARCHAR(2) NULL COMMENT '결산월 01~12 (DART acc_mt) — NULL 이면 모름',
    collected_at DATETIME NOT NULL COMMENT '조회 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='DART 기업개황 결산월 — 지배주주 TTM 은 12월 결산만';
