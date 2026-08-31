-- V58: signal_outcome 카테고리 스냅샷의 -1(NA sentinel) 을 NULL 로 소급 (AUDIT R7, 2026-08-31)
--
-- ## 무엇이 틀렸나
--
-- V30 계약은 "각 0~20, NULL=미수집(집계 제외)"인데, record 호출자(RecommendationDto)의
-- 표시 계약 sentinel(-1=NA)이 변환 없이 그대로 저장됐다. -1 은 IS NOT NULL 필터를 통과해
-- 실점수 행세를 한다 — 약세 버킷류 집계·커버리지 카운트·크루가 읽는 원시행이 오염된다.
-- prod 실측(2026-08-31): supply_demand_at_signal < 0 이 36행, technical_at_signal < 0 이 16행.
--
-- ## 왜 소급이 안전한가
--
-- 실점수는 0~20 이라 음수는 NA sentinel 로만 생길 수 있다 — 구분이 무결하다.
-- (0 은 "진짜 약세"일 수 있어 건드리지 않는다 — 그쪽은 구분 불가라 forward 만 정직해진다.)
-- 코드 쪽은 record 경계의 sanitizeCategorySnapshot 이 같은 날 배포된다.

UPDATE signal_outcome
SET earnings_at_signal        = CASE WHEN earnings_at_signal        < 0 THEN NULL ELSE earnings_at_signal        END,
    supply_demand_at_signal   = CASE WHEN supply_demand_at_signal   < 0 THEN NULL ELSE supply_demand_at_signal   END,
    technical_at_signal       = CASE WHEN technical_at_signal       < 0 THEN NULL ELSE technical_at_signal       END,
    sector_momentum_at_signal = CASE WHEN sector_momentum_at_signal < 0 THEN NULL ELSE sector_momentum_at_signal END
WHERE earnings_at_signal < 0 OR supply_demand_at_signal < 0
   OR technical_at_signal < 0 OR sector_momentum_at_signal < 0;
