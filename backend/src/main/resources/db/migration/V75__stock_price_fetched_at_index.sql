-- V75: stock_price.fetched_at 조회용 인덱스 (2026-11-10, 개선 후보 6번)
--
-- 시세 캐시 표 stock_price 는 2026-01-14 부터 한 번도 지운 적이 없어 약 399만 행이다(11/10 운영).
-- 인덱스가 PK 와 (stock_code, fetched_at) 뿐이라, fetched_at 만으로 거르는 조회가 표 전체를 훑었다:
--   · findTopVolumeStockCodes(최근 7일) — 차트 패턴 거래량 상위·🎯종합 순위 universe·퀀트 TA 일괄 수집
--   · findAllSince(최대 168시간) — 진단 API 의 시세 이력 스캔
--   · 보존 잡(StockPriceRetentionService)의 '기준일 이전 행' 순회
-- (stock_code, fetched_at) 는 종목별 최신 행 조회(가격 이상치 그물·DB 폴백)용이라 그대로 둔다.
--
-- 행은 지우지 않는다 — 삭제는 보존 잡이 사용자가 켤 때만 한다(STOCK_PRICE_RETENTION_ENABLED, 기본 꺼짐).
-- 온라인 생성(INPLACE·LOCK=NONE): 만들 수 없으면 표를 잠그지 않고 오류로 멈춘다(부팅이 실패해 드러난다).
-- 소요 실측(로컬 MariaDB 11.2, 400만 행): docs/STOCK_PRICE_RETENTION.md.
CREATE INDEX IF NOT EXISTS idx_stock_price_fetched_at
    ON stock_price (fetched_at) ALGORITHM=INPLACE LOCK=NONE;
