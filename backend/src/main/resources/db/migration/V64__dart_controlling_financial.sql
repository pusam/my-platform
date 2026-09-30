-- V64: DART 지배주주 순이익·지배지분 자본 (dart_controlling_financial) + PER 정의 기록 칸 (2026-09-30)
--
-- ## 왜
--
-- KIS 손익계산서의 순이익(thtr_ntin)은 연결 당기순이익 — 비지배지분 몫까지 포함이고 KIS 에는 지배주주 순이익·
-- 지배지분 자본 필드가 없다. 그 값으로 만든 PER·PBR 은 지주사·그룹사를 2~4배 싸 보이게 했다(다우기술 PER 0.9 vs
-- 지배 기준 2.1, PBR 0.20 vs 0.40 — 네이버 16/16 대조). DART 전체 재무제표(fnlttSinglAcntAll)는 지배주주 귀속 순이익
-- (ifrs-full_ProfitLossAttributableToOwnersOfParent)과 지배지분 자본을 표준 계정으로 준다.
--
-- ## 이 표
--
-- 정기보고서 한 건 = 한 행(원본 보존). 수집기가 "최신 분·반기 + 직전 사업보고서"로 최근 4분기 지배주주 순이익을
-- 만든다(ControllingEarnings.ttm — 계산은 읽는 쪽 순수 함수). 금액은 억원, NULL = 그 표준 계정이 없었다(0 아님).
-- status NO_DATA = 연결·별도 둘 다 '조회된 데이터 없음'(013) — 재조회 간격 판단용이라 금액이 비어 있다.
--
-- ## per_basis
--
-- stock_financial_data 에 PER·EPS 를 만든 순이익의 정의를 남긴다: CTRL(DART 지배주주 최근 4분기) · CONSOL(KIS 연결
-- 최근 4분기, 비지배 포함) · KIS(KIS 현재가 API — 지배주주 기준 최근 결산 연간). 이전 행은 NULL(= 이 칸이 생기기 전).
-- 한 화면 안에서 정의가 다른 PER 이 섞여 있을 수 있어서, 어느 쪽인지 사후에 가를 수 있게 한다.

CREATE TABLE IF NOT EXISTS dart_controlling_financial (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL COMMENT '종목코드',
    corp_code VARCHAR(8) NOT NULL COMMENT 'DART 고유번호',
    bsns_year INT NOT NULL COMMENT '사업연도',
    reprt_code VARCHAR(5) NOT NULL COMMENT '11013 1분기 · 11012 반기 · 11014 3분기 · 11011 사업보고서',
    fs_div VARCHAR(3) NULL COMMENT 'CFS 연결 · OFS 별도 (NO_DATA 면 NULL)',
    status VARCHAR(10) NOT NULL COMMENT 'OK · NO_DATA',
    filed_on DATE NULL COMMENT '접수일(접수번호 앞 8자리)',
    ctrl_net_income DECIMAL(15,2) NULL COMMENT '지배주주 순이익 — 분·반기 누적 / 사업보고서 연간 (억원)',
    ctrl_net_income_prev DECIMAL(15,2) NULL COMMENT '전년 동기 누적(사업보고서는 전년 연간) 지배주주 순이익 (억원)',
    total_net_income DECIMAL(15,2) NULL COMMENT '비지배 포함 연결 당기순이익 (억원) — KIS thtr_ntin 대조용',
    ctrl_equity DECIMAL(15,2) NULL COMMENT '지배기업 소유주지분 자본 (억원)',
    total_equity DECIMAL(15,2) NULL COMMENT '자본총계 — 비지배 포함 (억원)',
    collected_at DATETIME NOT NULL COMMENT '마지막으로 조회한 시각',
    UNIQUE KEY uq_dcf_stock_report (stock_code, bsns_year, reprt_code),
    INDEX idx_dcf_stock (stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='DART 정기보고서 지배주주 순이익·지배지분 자본 — PER·PBR·ROE 지배주주 기준의 원본';

ALTER TABLE stock_financial_data
    ADD COLUMN per_basis VARCHAR(10) NULL
        COMMENT 'PER·EPS 순이익 정의: CTRL(DART 지배주주 TTM) · CONSOL(KIS 연결 TTM, 비지배 포함) · KIS(현재가 API 연간 지배주주)';
