-- V59: signal_outcome 에 "기록시점 → D+3 KRX 종가" 교정 평가 컬럼 추가 (2026-09-17)
--
-- ## 무엇이 틀렸나
--
-- 기존 3일 평가(price_after_3d / pct_change_3d / hit / mfe·mae)는 정의가 흔들린다.
--   ① price_after_3d 에 평가 배치가 도는 시점의 '현재가'가 들어간다 — 배치가 3거래일 뒤에 돌면
--      맞지만 밀리면(9/11~9/15 API 사망처럼) 5일·6일째 시세가 "3일 뒤 가격"으로 저장된다.
--      게다가 19:30 시세는 KRX 종가가 아니라 NXT 야간 거래 중 가격일 수 있다.
--   ② mfe/mae 는 최근 7봉에서 '시그널일 이후'만 걸러 상한이 없다 — 늦게 평가되면 4~7일째 봉이
--      섞이고, 아주 늦으면 3일 안 봉이 아예 빠진다.
-- 그래서 그 수치로 낸 손익(손실 -6.5%, 최악 -12.4%)은 "방향은 맞다"조차 보장 못 하는 참고치다.
--
-- ## 교정 정의
--
-- 시작 = 기록 시점 가격(price_at_signal, 장중·NXT 시간대 가능 — 바꾸지 않는다).
-- 끝   = 거래일 달력으로 정한 D+3 의 KRX 종가(stock_price_history). 지수도 같은 D+3 종가.
-- 창   = 정확히 D+1·D+2·D+3 세 봉. 봉이 없으면 다음 봉으로 미루지 않고 사유(d3_status)를 남긴다.
-- 이름에 "close" 대신 "d3_" 를 쓴 것은 "종가 대 종가"가 아니라 "기록시점 → D+3 종가"이기 때문.
--
-- ## 왜 새 컬럼인가
--
-- 구값은 그대로 둔다(감사 흔적). 교정값이 없을 때 구값으로 자동 대체하지 않는다 — 그러면 이번
-- 문제가 그대로 남는다. 게이트 전환은 구값·교정값 비교표(변경 건수·차이·미평가 사유)를 본 뒤 한다.
-- d3_status: OK | MISSING_BARS | HALTED_IN_WINDOW | UNIT_MISMATCH_SUSPECT | CORPORATE_ACTION_SUSPECT
--            | NO_START_PRICE | NO_INDEX. NULL = 아직 시도 안 함.

ALTER TABLE signal_outcome
    ADD COLUMN d3_end_date     DATE           NULL COMMENT '거래일 달력 기준 D+3 (평가 종료일)',
    ADD COLUMN d3_close        DECIMAL(15,2)  NULL COMMENT 'D+3 KRX 종가(stock_price_history)',
    ADD COLUMN d3_pct_change   DECIMAL(10,4)  NULL COMMENT '(d3_close - price_at_signal)/price_at_signal %',
    ADD COLUMN d3_bm_close     DECIMAL(15,2)  NULL COMMENT 'D+3 KOSPI 종가',
    ADD COLUMN d3_bm_return    DECIMAL(10,4)  NULL COMMENT '(d3_bm_close - bm_price_at_signal)/bm_price_at_signal %',
    ADD COLUMN d3_alpha        DECIMAL(10,4)  NULL COMMENT 'd3_pct_change - d3_bm_return',
    ADD COLUMN d3_mfe_pct      DECIMAL(10,4)  NULL COMMENT 'D+1..D+3 최고가 기준 %',
    ADD COLUMN d3_mae_pct      DECIMAL(10,4)  NULL COMMENT 'D+1..D+3 최저가 기준 %',
    ADD COLUMN d3_hit          TINYINT(1)     NULL COMMENT '기존 hit 규칙 동일(alpha>=0 & pct>0, 지수 결측 시 pct>=3)',
    ADD COLUMN d3_status       VARCHAR(32)    NULL COMMENT 'OK | MISSING_BARS | HALTED_IN_WINDOW | UNIT_MISMATCH_SUSPECT | CORPORATE_ACTION_SUSPECT | NO_START_PRICE | NO_INDEX',
    ADD COLUMN d3_note         VARCHAR(255)   NULL COMMENT '미평가·의심 사유(사람이 읽는 문장)',
    ADD COLUMN d3_evaluated_at DATETIME(6)    NULL COMMENT '교정 평가 시각';

CREATE INDEX idx_so_d3_status ON signal_outcome (signal_date, d3_status);
