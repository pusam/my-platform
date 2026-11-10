-- V76: 외부 공개 수치 자동 대조 (2026-11-11, 개선 후보 3번)
--
-- ## 왜
--
-- 2026년 가을의 큰 데이터 오류(저녁에 받은 일봉이 KRX 종가가 아니었던 것 등)는 전부 사람이 네이버·야후 수치와 손으로 대조해서 찾았다.
-- 내부 테스트로는 하나도 안 보였다. 그래서 매 거래일 작은 표본의 마지막 확정 봉 종가를 네이버 종목 응답의 '전일'(직전 거래일 KRX 종가)과
-- 견주고 결과를 쌓는다(ExternalCrosscheckService). 측정 전용 — 추천·봇·시그널에 넣지 않고, 어긋나도 우리 값을 고치지 않는다(§3).
--
-- ## 칸
--
-- external_crosscheck_run : 실행 한 번 = 한 행. planned(표본 수)·attempted(처리한 종목 수)·matched·mismatched·not_compared(대조 못 함 —
--                           일치가 아니다, §4c). status RUNNING → COMPLETE / ABORTED(연속 실패로 멈춤) / ERROR.
-- external_crosscheck_row : 종목·항목별 한 행. item CLOSE 만 판정한다(MATCH/MISMATCH/EXTERNAL_STALE/NO_OUR_BAR/NO_EXTERNAL_VALUE/
--                           FETCH_FAILED). EPS·BPS 는 판정 없이 기록만(status RECORDED 등) — 허용 폭을 정할 분포를 모으려는 것이다.
--                           external_raw 는 응답 원문 그대로(사람이 재확인할 수 있게).
--
-- 보존 365일 — 같은 잡이 끝에서 지운다(하루 약 40~120행).

CREATE TABLE IF NOT EXISTS external_crosscheck_run (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_date      DATE         NOT NULL COMMENT '실행한 날(KST)',
    target_bar_date DATE       NOT NULL COMMENT '대조한 우리 봉 날짜 — 직전 마감 거래일',
    source        VARCHAR(30)  NOT NULL COMMENT 'NAVER_INTEGRATION',
    trigger_type  VARCHAR(10)  NOT NULL COMMENT 'CRON / MANUAL',
    status        VARCHAR(12)  NOT NULL COMMENT 'RUNNING / COMPLETE / ABORTED / ERROR',
    planned       INT          NOT NULL,
    attempted     INT          NOT NULL,
    matched       INT          NOT NULL,
    mismatched    INT          NOT NULL,
    not_compared  INT          NOT NULL,
    note          VARCHAR(1000) NULL,
    started_at    DATETIME     NOT NULL,
    finished_at   DATETIME     NULL,
    INDEX idx_ecr_run_date (run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='외부 공개 수치 대조 실행 (측정 전용 — 추천·봇·시그널 미편입)';

CREATE TABLE IF NOT EXISTS external_crosscheck_row (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT        NOT NULL,
    stock_code      VARCHAR(10)   NOT NULL,
    sample_reason   VARCHAR(12)   NOT NULL COMMENT 'CANDIDATE / HOLDING / WATCH / MCAP / RANDOM',
    item            VARCHAR(10)   NOT NULL COMMENT 'CLOSE(판정) / EPS / BPS(기록만)',
    our_value       DECIMAL(20,4) NULL COMMENT 'NULL = 우리 값 없음',
    our_value_date  DATE          NULL COMMENT 'CLOSE = 봉 날짜, EPS·BPS = 재무 행 report_date',
    our_basis       VARCHAR(10)   NULL COMMENT 'EPS·BPS: per_basis(CTRL/CONSOL/KIS)',
    our_prev_value  DECIMAL(20,4) NULL COMMENT 'CLOSE: 그 앞 봉 종가(외부 값이 하루 늦은지 가른다)',
    external_value  DECIMAL(20,4) NULL COMMENT 'NULL = 외부 값 없음',
    external_raw    VARCHAR(40)   NULL COMMENT '응답 원문 그대로',
    external_basis  VARCHAR(20)   NULL COMMENT 'EPS·BPS: 응답의 기준 결산(예: 2026.06.)',
    status          VARCHAR(20)   NOT NULL,
    diff_pct        DECIMAL(10,4) NULL COMMENT '(외부-우리)/|우리|×100 — 둘 다 있을 때만',
    reason          VARCHAR(200)  NULL,
    fetched_at      DATETIME      NULL,
    INDEX idx_ecrow_run (run_id),
    INDEX idx_ecrow_code (stock_code, run_id),
    CONSTRAINT fk_ecrow_run FOREIGN KEY (run_id) REFERENCES external_crosscheck_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='외부 공개 수치 대조 종목별 결과';
