-- V80: 주도주 목록 — 날마다의 순위 (2026-11-19)
--
-- ## 왜
--
-- 사용자가 원하는 것은 '저평가 종목'과 '국내 주도주(삼성전자·SK하이닉스 같은)'인데 주도주를 보여 주는 목록이 없었다.
-- 시가총액 1조 원 이상 종목을 20거래일 평균 거래대금 순으로 세우고 20·60거래일 등락을 붙여 발굴 탭에 보인다.
-- 표시 전용이다 — 추천 점수·봇·시그널 기록과 무관하고 '사라'는 신호가 아니다(성과 검증 전).
--
-- ## 칸의 뜻 (§4c)
--   basis_date           : 값이 굳은 마지막 거래일(다음 날 08:00 뒤에 받은 KRX 정규장 일봉). 그날 종가 기준의 목록이다.
--   market_cap_eok       : 시가총액(억 원, 보통주) — 재무 일별 행의 가장 새 값.
--   avg_traded_value_eok : 최근 20봉의 (종가 × 거래량) 평균(억 원). 매수 후보 거래대금 하한(LiquidityFloor)과 같은 정의.
--                          KRX 정규장 일봉이라 대체거래소(NXT) 거래는 들어 있지 않다.
--   return_20d/60d       : 기준일 종가 ÷ 달력이 정한 20·60거래일 전 날짜의 종가 − 1 (%). 그 날짜 봉이 없으면 NULL(0 아님).
--   rank_order           : 그 기준일 안의 순위(1 = 평균 거래대금이 가장 큼).
--
-- ## 지킬 것
--
-- 일봉은 이 계산에만 쓰고 stock_price_history 에 넣지 않는다 — 그 표는 추천 기술 점수·낙폭과대 트랙·거래정지 감지의 입력이다.
-- 한 기준일의 행은 한 번에 통째로 바꾼다(일부만 받은 회차는 저장하지 않는다). 외국인·기관 순매수는 여기 복사하지 않고
-- 화면이 읽을 때 investor_stock_daily 에서 읽는다(다음 거래일에 금액이 한 번 더 확정된다).

CREATE TABLE IF NOT EXISTS leader_stock_daily (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    basis_date           DATE          NOT NULL COMMENT '값이 굳은 마지막 거래일 — 그날 종가 기준',
    stock_code           VARCHAR(10)   NOT NULL COMMENT '종목코드',
    stock_name           VARCHAR(200)  NOT NULL COMMENT '종목명(마스터)',
    market               VARCHAR(20)   NULL     COMMENT 'KOSPI / KOSDAQ',
    market_cap_eok       DECIMAL(20,2) NOT NULL COMMENT '시가총액(억 원, 보통주)',
    close_price          DECIMAL(15,2) NOT NULL COMMENT '기준일 종가(수정주가)',
    avg_traded_value_eok DECIMAL(20,2) NOT NULL COMMENT '20거래일 평균 거래대금(억 원) = 최근 20봉 (종가×거래량) 평균',
    return_20d           DECIMAL(9,2)  NULL     COMMENT '20거래일 등락률(%). NULL=그 날짜 봉 없음',
    return_60d           DECIMAL(9,2)  NULL     COMMENT '60거래일 등락률(%). NULL=그 날짜 봉 없음',
    rank_order           INT           NOT NULL COMMENT '기준일 안의 순위(1 = 평균 거래대금 최대)',
    created_at           DATETIME      NOT NULL COMMENT '이 행을 만든 시각',
    UNIQUE KEY uq_lsd_basis_stock (basis_date, stock_code),
    INDEX idx_lsd_basis_rank (basis_date, rank_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='주도주 목록 — 시가총액 1조 이상, 20거래일 평균 거래대금 순 (표시 전용)';
