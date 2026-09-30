-- V63: 재무비율 API 오독으로 저장된 영업이익률을 비운다 (2026-09-30)
--
-- ## 왜
--
-- 수집기가 KIS 재무비율 API 의 bsop_prfi_inrt 를 영업이익률로 담았다. 공식 샘플(open-trading-api
-- examples_llm/domestic_stock/finance_financial_ratio/chk_finance_financial_ratio.py COLUMN_MAPPING)에서 그 필드는
-- '영업 이익 증가율'이다. 손익계산서 TTM 이 있는 행은 뒤에서 TTM 영업이익÷매출로 덮였지만, TTM 이 없는 행
-- (revenue NULL)은 **증가율이 영업이익률로 남았다** — 진양제약 34.86·기도산업 81.65 는 상반기 영업이익 증가율이고
-- 실제 이익률은 약 11%·4.6% 다. 값이 비어 있으면 파싱이 0 을 넣어 0 도 섞였다(그것도 '모름'이다).
-- 코드는 b3ec7483(9/30)에서 고쳤다 — 이 마이그레이션은 그 전에 쌓인 행을 비운다. 최근 행을 이어 쓰는 소비처
-- (종목 상세·AI 분석 — 최근 10행의 첫 양수값)가 새 행의 null 을 옛 행의 증가율로 메우지 않게.
--
-- ## 대상 — KIS 일별 행 중 TTM 매출이 없는 행의 영업이익률 전부
--
-- 수집기에서 영업이익률이 들어가는 길은 둘뿐이다: TTM 영업이익÷매출(그러면 revenue 가 있다)과 재무비율 API 오독.
-- 그래서 revenue 가 NULL 인 KIS 행(market_cap NOT NULL)의 영업이익률은 전부 오독(또는 그 파싱 실패 0)이다 — 오독은
-- 2026-01-28 커밋에서 들어왔고 그런 행의 첫 날짜가 2026-01-30 이다. 운영 실측(9/30): 57,535행(0 이 아닌 값 42,913).
-- 네이버 분기 행(market_cap NULL)은 writer 가 달라 건드리지 않는다(V56→V57 사고).
--
-- ## 되돌리기
--
--   UPDATE stock_financial_data s JOIN stock_financial_opm_backup_v63 b ON b.id = s.id
--      SET s.operating_margin = b.operating_margin;
-- ⚠ 되돌리면 증가율이 다시 영업이익률 자리에 들어온다.

CREATE TABLE IF NOT EXISTS stock_financial_opm_backup_v63 (
    id               BIGINT        NOT NULL PRIMARY KEY COMMENT 'stock_financial_data.id',
    stock_code       VARCHAR(10)   NULL,
    report_date      DATE          NULL,
    operating_margin DECIMAL(10,2) NULL,
    backed_up_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='V63 가 비우기 전 영업이익률(재무비율 API 오독, KIS 일별 행 중 TTM 매출 없는 행) — 되돌리기용';

INSERT INTO stock_financial_opm_backup_v63 (id, stock_code, report_date, operating_margin)
SELECT id, stock_code, report_date, operating_margin
  FROM stock_financial_data
 WHERE market_cap IS NOT NULL
   AND revenue IS NULL
   AND operating_margin IS NOT NULL;

UPDATE stock_financial_data
   SET operating_margin = NULL
 WHERE market_cap IS NOT NULL
   AND revenue IS NULL
   AND operating_margin IS NOT NULL;
