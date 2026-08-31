# 운영 절차 — 스테일/동결 피드 점검 (P2-11)

`GET /api/diagnostics/stale-feeds` 로 **정규장에 가격이 동결된 종목**을 뽑아, 거래정지/상폐 동기화
(`StockStatusService`) 누락을 점검하는 운영 루틴. **관측·로깅 전용 — 가격/상태를 자동 변경하지 않는다.**
배경·진단 경위는 `docs/PRICE_X10_DIAGNOSIS_P2-11`(= 본 티켓 `VERIFICATION_BACKLOG.md` P2-11) 및
`docs/PRICE_X10_DIAGNOSIS_P0-2.md` §6 참조.

---

## 0. 무엇을 보는가

| 분류 | 의미 | 조치 방향 |
|---|---|---|
| `ACTIVE_FROZEN` | **활성** 종목인데 정규장(09:00~15:30)에 `tickThreshold` 틱 이상 가격이 고정 | **이상** — 거래정지/상폐인데 동기화에서 누락됐거나, 피드 스테일 의심 |
| `SUSPENDED_FROZEN` | 정지/상폐 종목의 동결 | **정상** — 무시(기대된 동작) |
| `corruptCtrt` | 등락률 `prdy_ctrt` 가 ±31% 초과(예: 900%) | 손상 필드 — KIS 응답/매핑 점검 대상 |

> 동결 판정은 **정규장 구간만** 본다(휴장/장외 동결은 정상). `ACTIVE_FROZEN` 핵심 질문:
> **"이 종목, 진짜 거래정지/상폐 아니냐? 그렇다면 `StockStatusService` 동기화가 왜 못 잡았나?"**

---

## 1. 호출 (운영, 인증 토큰 필요)

```bash
# 기본: 최근 30일(720h), 동결 임계 20틱(≈3분틱 1시간), 최대 200건
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://dhkim-lab.duckdns.org/api/diagnostics/stale-feeds?hoursBack=720&tickThreshold=20&maxEvents=200" \
  | jq .data
```

파라미터:
- `hoursBack` — 스캔 기간(시간). 720=30일. 장기 동결(상폐 누락)은 길게(예: `2160`=90일) 권장.
- `tickThreshold` — 정규장 연속 동일가 임계. 3분 틱 기준 `20`≈1시간. 저유동 종목 오탐을 줄이려면 ↑
  (예: 하루치 동결만 잡으려면 `130` ≈ 정규장 6.5h). 진짜 상폐 누락은 수백~수천 틱이라 임계에 둔감.
- `maxEvents` — 반환 상한.

### 응답 형태
```json
{
  "hoursBack": 720, "tickThreshold": 20, "scannedRows": 0, "distinctStocks": 0,
  "activeFrozen": 0, "suspendedFrozen": 0, "corruptCtrtRows": 0,
  "frozen": [ { "stockCode": "001230", "stockName": "...", "market": "KOSPI",
               "frozenTicks": 1200, "lastPrice": 11400, "lastAt": "...", "kind": "ACTIVE_FROZEN" } ],
  "corruptCtrt": [ { "stockCode": "011930", "changeRate": 900.00, "fetchedAt": "..." } ],
  "note": "..."
}
```

---

## 2. 판정 → 조치

### (A) `activeFrozen > 0` — 동기화 누락 점검
각 `ACTIVE_FROZEN` 종목코드에 대해:

1. **실제 상태 확인** — KRX/증권사에서 해당 종목이 거래정지·상폐·정리매매인지 확인.
2. **동기화 상태 확인** — `StockStatusService` 가 그 종목을 활성으로 들고 있는지:
   - 진단 데이터: `GET /api/diagnostics/data` 의 메타(또는 로그 `[종목상태] 동기화 완료`)로 마지막 동기화 시각 확인.
3. **분기**:
   - **실제 정지/상폐인데 활성으로 남아있음** → 동기화 누락. 수동 재동기화 트리거:
     `StockStatusService.scheduledSync()` 는 매일 08:30 cron. 즉시 반영이 필요하면 운영 재기동 또는
     관리자 트리거(있으면)로 `syncListedStocks()` 재실행. 소스는 KIS 종목마스터 파일(2026-08-31 교체 — KRX 경로는 死).
     ⚠ 재기동만 해도 부팅 동기화(`syncOnStartup`)가 돌아 즉시 반영된다.
   - **실제로는 정상 거래 종목인데 동결** → **피드 스테일**. KIS 시세 경로/`StockPriceService` 캐시
     (L1 Caffeine→L2 Redis) 신선도, `CacheWarmer`/스케줄러 동작 점검. (가격 보정 금지 — 원인 제거가 우선)

### (B) `corruptCtrtRows > 0` — 손상 등락률
- 해당 종목·시각의 KIS raw 를 로그(`[가격이상] ... prdy_ctrt=...`)에서 대조.
- 특정 종목/시간대(특히 NXT 단독 구간)에 몰리면 통합시세(UN) 필드 규약 의심 → P0-2 §7 후속 참고.
- **가격은 보정하지 않는다**(P0-1 불변식). 관측·원인 규명까지가 본 절차 범위.

### (C) 전부 0
- 동결/손상 없음. `note` 에 "없음" 안내. 정상.

---

## 3. DB 직접 점검 (엔드포인트 없이, 재배포 전)

엔드포인트 배포 전이면 동일 판정을 SQL 로 즉시 확인 가능. **정규장 꼬리 동결**(가장 단순·빠른 1차):

```sql
-- 최근 30일, 정규장(09:00~15:30) 구간에서 종목별 '직전 적재가와 동일' 비율이 높은 종목 후보
SELECT stock_code,
       COUNT(*) reg_ticks,
       COUNT(DISTINCT current_price) distinct_prices
FROM stock_price
WHERE fetched_at >= NOW() - INTERVAL 30 DAY
  AND TIME(fetched_at) >= '09:00:00' AND TIME(fetched_at) < '15:30:00'
GROUP BY stock_code
HAVING distinct_prices = 1 AND reg_ticks >= 20   -- 정규장 내내 단일가 = 동결 강력 의심
ORDER BY reg_ticks DESC;
```

손상 등락률:
```sql
SELECT stock_code, change_rate, fetched_at
FROM stock_price
WHERE fetched_at >= NOW() - INTERVAL 30 DAY
  AND ABS(change_rate) > 31
ORDER BY fetched_at DESC LIMIT 200;
```

> `docker exec myplatform-mariadb sh -c 'mariadb -umyplatform -p"$MYSQL_PASSWORD" myplatform -e "<SQL>"'`
> 로 실행. (한 줄 권장 — heredoc 들여쓰기 주의)

---

## 4. 한계 / 주의

- **정규장 한정**: 장외·휴장 동결은 정상이라 제외. 단일가/시간외 동결도 판정에서 빠짐.
- **저유동 오탐**: 임계가 낮으면 거래가 드문 정상 종목이 잡힐 수 있음 → `tickThreshold` 를 상황에 맞게.
- **`StockStatusService` 안전모드**: 동기화 전(활성목록 empty)에는 `isActive` 가 항상 true →
  이 시점 스캔은 전부 `ACTIVE_FROZEN` 으로 보일 수 있으니, 동기화 완료 후 호출할 것.
- **자동 조치 없음**: 본 절차는 *탐지·로깅*까지다. 거래정지 자동 반영/대체값 주입은 별도 티켓 결정 사항.

---

## 5. 관련 코드

- `util/StaleFeedDetector` — 꼬리 동결 길이(순수).
- `service/PriceScalingDiagnosticService.scanStaleFeeds` — 정규장 동결 감지 + 활성/정지 교차 + 손상 등락률.
- `controller/DiagnosticsController` — `GET /api/diagnostics/stale-feeds`.
- `service/StockStatusService` — 활성/정지 목록(`isActive`, `getSuspendedStocks`, 08:30 `scheduledSync`).
- 테스트: `StaleFeedDetectorTest`, `PriceScalingDiagnosticServiceTest`(P2-11 케이스), `StockPriceOutlierGuardTest`(저측/등락률 회귀).
