-- V51: stock_price 에 누적거래대금·데이터소스 컬럼 추가 (DB 왕복 소실 봉합)
--
-- 배경: StockPriceDto 는 accumulatedTradingValue(KIS 실측 누적거래대금)와 dataSource(KIS/NAVER)를
--       담지만 엔티티에 대응 컬럼이 없어 L1 미스 → DB 경로 서빙 시 항상 NULL 로 소실됐다.
--       SectorTradingService.resolveAccumulatedValue 가 실측 누적거래대금을 1순위로 쓰는데(§4c),
--       DB 경유 시세는 무조건 현재가×거래량 폴백으로만 계산되던 원인.
-- NULL = 미수집(§4c) — 기존 행/미제공 소스는 NULL 유지, 폴백 계산은 소비자 몫.

ALTER TABLE stock_price
    ADD COLUMN accumulated_trading_value DECIMAL(20,0) NULL
        COMMENT '누적 거래대금(원) — KIS 등 API 실측만 저장. NULL=미수집(현재가×거래량 폴백은 소비자 계산)'
        AFTER market_cap,
    ADD COLUMN data_source VARCHAR(20) NULL
        COMMENT '시세 출처 (KIS/NAVER 등) — DB 왕복 후에도 출처 추적. NULL=미기록'
        AFTER accumulated_trading_value;
