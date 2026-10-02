-- 공매도 거래 비중 — KIS 국내주식 공매도 상위종목(국내주식-133, FHPST04820000) 일별 스냅샷 (2026-10-02)
--
-- ⚠ 잔고가 아니다. 그날 거래량(·거래대금) 중 공매도 몫이다. 잔고 표(short_selling_balance)의 출처
--   (KRX data 포털·네이버 금융)는 둘 다 죽었고 KIS 에는 공매도 잔고 API 가 없다 — 그래서 잔고 표는 비어 있고
--   봇 고공매도 차단·19시 경보·체크리스트 필수 판정(잔고 5% 기준)은 데이터가 없어 작동하지 않는다.
-- ⚠ 표시 전용이다(사용자 결정 2026-10-02 "거래 비중으로, 표시만"): 시장 탭 공매도 화면과 체크리스트 '참고' 항목.
--   판정·봇·경보·추천에 쓰지 말 것 — 거래 비중용 기준은 검증된 적이 없다.
-- 기준일(trade_date)은 KIS 응답의 기준 일자(stnd_date2)다 — 수집한 날로 찍지 않는다.
-- 같은 기준일을 다시 수집하면 그날 행을 통째로 갈아 끼운다(순위표 스냅샷이라 빠진 종목이 남지 않게).
CREATE TABLE short_selling_trade (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stock_code         VARCHAR(20)   NOT NULL COMMENT '유가증권 단축 종목코드(mksc_shrn_iscd)',
    stock_name         VARCHAR(100)  NULL     COMMENT 'HTS 한글 종목명(hts_kor_isnm)',
    trade_date         DATE          NOT NULL COMMENT 'KIS 기준 일자(stnd_date2, 없으면 stnd_date1)',
    rank_no            INT           NULL     COMMENT '응답 순서(1부터, 연속조회 페이지를 이어 센다)',
    short_volume       BIGINT        NULL     COMMENT '공매도 체결 수량(ssts_cntg_qty)',
    short_volume_share DECIMAL(9, 4) NULL     COMMENT '공매도 거래량 비중 %(ssts_vol_rlim)',
    short_amount       DECIMAL(22, 0) NULL    COMMENT '공매도 거래 대금(ssts_tr_pbmn, KIS 원본 단위)',
    short_amount_share DECIMAL(9, 4) NULL     COMMENT '공매도 거래대금 비중 %(ssts_tr_pbmn_rlim)',
    total_volume       BIGINT        NULL     COMMENT '누적 거래량(acml_vol)',
    total_amount       DECIMAL(22, 0) NULL    COMMENT '누적 거래 대금(acml_tr_pbmn)',
    price              DECIMAL(15, 2) NULL    COMMENT '주식 현재가(stck_prpr) — 수집 시점 값',
    change_rate        DECIMAL(9, 4) NULL     COMMENT '전일 대비율(prdy_ctrt)',
    avg_price          DECIMAL(15, 2) NULL    COMMENT '평균가격(avrg_prc) — 거래대금 단위 확인용',
    collected_at       DATETIME      NOT NULL COMMENT '수집 시각',
    CONSTRAINT uk_sst_stock_date UNIQUE (stock_code, trade_date)
);

CREATE INDEX idx_sst_date_rank ON short_selling_trade (trade_date, rank_no);
