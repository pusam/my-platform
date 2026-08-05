-- pattern_detection shadow sanity 체크 — 첫 주 임계값 1차 조정용 (V52, 2026-08-05)
--
-- 사용 (서버):
--   docker compose exec -T mariadb mariadb -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/pattern-detection-sanity.sql
--
-- ⚠ 기각 사유별 집계는 DB가 아니라 배치 로그에 있다(테이블엔 '감지된 것'만 기록):
--   docker compose logs backend --since 168h | grep "기각 집계"
--   → 하루 1줄: 후보 N건 + 사유별 카운트(0 포함, 고정 순서).
--   해석: 특정 사유 카운트 ≈ 그 단계 도달 수 전부(=100% 기각)면 해당 임계가 죽어있는 것.
--        BULL_MA_FILTER / BULL_NO_HIGH_BREAKOUT 대량 기각은 정상(위치 필터의 역할).
--        봐야 할 것은 그 아래 단계(장대양봉/눌림RVOL/수렴/박스) 도달 수와 통과율.
--   감지 개별 로그: grep "\[패턴감지\]" (종목/타입/일자/종가)

-- 1) 일자별 × 패턴타입별 감지 건수 (여기 없는 날짜 = 그날 감지 0건 — 로그의 후보 수와 대조)
SELECT detected_date,
       pattern_type,
       COUNT(*)                        AS detections,
       SUM(evaluated_at IS NOT NULL)   AS evaluated,
       ROUND(AVG(return_1d), 2)        AS avg_r1d,
       ROUND(AVG(return_3d), 2)        AS avg_r3d,
       ROUND(AVG(return_5d), 2)        AS avg_r5d,
       ROUND(AVG(return_10d), 2)       AS avg_r10d
FROM pattern_detection
GROUP BY detected_date, pattern_type
ORDER BY detected_date DESC, pattern_type;

-- 2) 패턴타입별 총계 + 잠정 승률 (AVG/집계는 NULL=미평가 자동 제외 — §4c)
--    표본 적을 땐 참고만. 정식 판정은 4주 후 백테스트.
SELECT pattern_type,
       COUNT(*)                          AS total,
       COUNT(DISTINCT stock_code)        AS distinct_stocks,
       COUNT(DISTINCT detected_date)     AS distinct_days,
       SUM(evaluated_at IS NOT NULL)     AS evaluated,
       ROUND(100 * AVG(return_3d > 0), 1)  AS win3d_pct,
       ROUND(100 * AVG(return_5d > 0), 1)  AS win5d_pct,
       ROUND(100 * AVG(return_10d > 0), 1) AS win10d_pct,
       ROUND(AVG(return_5d), 2)          AS avg_r5d
FROM pattern_detection
GROUP BY pattern_type;

-- 3) 종목 편중 — 소수 종목이 감지를 독식하면 임계가 특정 변동성 프로파일에만 열려있다는 신호
--    (수급 캡 P1-6 의 '삼성전기 8/8행' 같은 위장 표본 재발 방지)
SELECT stock_code,
       MAX(stock_name) AS name,
       pattern_type,
       COUNT(*)        AS cnt,
       MIN(detected_date) AS first_seen,
       MAX(detected_date) AS last_seen
FROM pattern_detection
GROUP BY stock_code, pattern_type
ORDER BY cnt DESC
LIMIT 20;

-- 4) 감지 시 실측 파라미터 분포 — 임계와 실측이 얼마나 붙어있는지(조정 방향/여유폭 판단)
--    예: pullbackRvol p50 이 0.68 이면 0.7 임계가 간당간당(살짝만 올려도 감지 급증) —
--    params_snapshot 에 임계도 같이 저장돼 있어 조정 후에도 과거 행 판정 기준 재현 가능.
SELECT pattern_type,
       COUNT(*) AS n,
       ROUND(AVG(JSON_EXTRACT(params_snapshot, '$.atr14')), 2)        AS avg_atr,
       ROUND(AVG(JSON_EXTRACT(params_snapshot, '$.pullbackRvol')), 3) AS avg_pullback_rvol,
       ROUND(MAX(JSON_EXTRACT(params_snapshot, '$.pullbackRvol')), 3) AS max_pullback_rvol,
       ROUND(AVG(JSON_EXTRACT(params_snapshot, '$.convAvgRvol')), 3)  AS avg_conv_rvol,
       ROUND(MIN(JSON_EXTRACT(params_snapshot, '$.convAvgRvol')), 3)  AS min_conv_rvol,
       ROUND(AVG(JSON_EXTRACT(params_snapshot, '$.boxRange')), 2)     AS avg_box_range,
       ROUND(AVG(JSON_EXTRACT(params_snapshot, '$.dojiCount')), 1)    AS avg_doji_count
FROM pattern_detection
GROUP BY pattern_type;

-- 5) 평가 파이프라인 건강 — 미평가 적체(10거래일 안 지난 정상 대기 vs 45일 초과 give-up 의심)
SELECT CASE
           WHEN evaluated_at IS NOT NULL THEN 'evaluated'
           WHEN detected_date >= CURDATE() - INTERVAL 45 DAY THEN 'pending(정상 대기)'
           ELSE 'abandoned(give-up 창 초과 — 상폐/정지 의심)'
       END AS eval_status,
       COUNT(*) AS cnt
FROM pattern_detection
GROUP BY eval_status;
