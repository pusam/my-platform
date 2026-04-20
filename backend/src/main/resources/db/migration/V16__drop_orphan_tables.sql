-- 엔티티가 더 이상 존재하지 않아 ddl-auto:update 시절에 고아로 남은 테이블 정리.
-- ddl-auto: validate 로 복귀하기 전 단계: validate 자체는 '엔티티 대비 누락 컬럼'만
-- 검사하므로 잉여 테이블은 에러 원인이 아니지만, 장기 유지보수 차원에서 제거한다.

-- 1) 완전 empty + 미사용 테이블은 즉시 삭제 (0 rows 확인 완료)
DROP TABLE IF EXISTS lotto_draw;
DROP TABLE IF EXISTS lotto_weekly_recommendation;
DROP TABLE IF EXISTS pension_lottery_draw;
DROP TABLE IF EXISTS pension_lottery_weekly_recommendation;
DROP TABLE IF EXISTS market_investor_history;

-- 2) stock_short_data 는 576 rows 존재 (구 공매도 데이터).
--    현재 활성 엔티티는 short_selling_balance 이고 코드 참조 0건이지만, 혹시 모를 복구용으로
--    RENAME 만 수행해 이름공간에서만 제거 → 운영 정상 확인 후 V17 에서 DROP 예정.
RENAME TABLE stock_short_data TO _deprecated_stock_short_data_20260420;
