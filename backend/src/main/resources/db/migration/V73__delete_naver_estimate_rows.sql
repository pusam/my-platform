-- V73: 옛 네이버 크롤이 남긴 '추정치' 재무 행(기준일이 아직 오지 않은 행)을 백업한 뒤 지운다 (2026-11-10, 사용자 결정 '백업 후 삭제')
--
-- ## 왜
--
-- 네이버 크롤(2026-09-23 은퇴)이 2026-07-21 전까지 'yyyy.12(E)' 추정치 칸을 확정 실적처럼 저장해 342종목에 report_date=2026-12-31
-- 행이 남아 있다(11/10 운영: 342행 전부 market_cap NULL = 네이버 writer, 005930 매출 7,324,732억 같은 추정치).
-- 지금은 읽는 쪽이 '미래 날짜'로 전부 거르지만(FinancialRowSynthesizer.excludeFutureDated · 쿼리의 report_date <= CURDATE()),
-- 2026-12-31 이 되면 미래가 아니게 되어 ① 스크리너(마법의 공식·PEG·성장)의 종목별 최신 행 자리에 올라와(PER 없음) 종목이 빠지고
-- ② 합성·composite 가치·성장 축·상세·AI 분석이 그 추정치를 실적처럼 읽는다. 끝나지 않은 기간에 실적이 있을 수 없으므로
-- '네이버 행 중 기준일이 오늘 이후'는 곧 추정치다. 지우는 것이 가장 단순하고 확실하다(읽는 쪽 가드는 그대로 남는다).
--
-- ## 대상
--
-- market_cap IS NULL(네이버 writer — KIS 일별 행은 건드리지 않는다, V56→V57 사고) AND report_date > 실행일.
-- 11/10 운영 확인: 342행 = 342종목, 전부 2026-12-31, 마지막 쓰기 2026-09-02 이전.
-- 지금 이 행들은 모든 소비처에서 이미 걸러지고 있어 추천 입력은 바뀌지 않는다.
--
-- ## 되돌리기
--
--   INSERT INTO stock_financial_data SELECT * FROM stock_financial_estimate_backup_v73;
-- ⚠ 되돌리면 12/31 부터 추정치가 실적처럼 읽힌다.

CREATE TABLE IF NOT EXISTS stock_financial_estimate_backup_v73 LIKE stock_financial_data;

INSERT INTO stock_financial_estimate_backup_v73
SELECT * FROM stock_financial_data
WHERE market_cap IS NULL AND report_date > CURDATE();

DELETE FROM stock_financial_data
WHERE market_cap IS NULL AND report_date > CURDATE();
