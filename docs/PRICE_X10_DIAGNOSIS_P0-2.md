# P0-2 — ×10 가격 이상치 근본원인 진단·분류 (수정 아님, 진단 전용)

## 1. 출발점 확인: `/api/diagnostics/data` 에는 ×10 데이터가 없다

`GET /api/diagnostics/data` 는 **추천 스냅샷 / 시그널 아웃컴 카운트 메타데이터**만 노출한다
(`DiagnosticsController.data()`). 가격 ×10 관련 필드는 없다. (또한 현재 이 엔드포인트는 인증 토큰을
요구한다 — 무인증 호출 시 `resultCode 2003 Please check your Authorization`.)

→ **×10 증거가 남는 곳은 두 군데뿐:**
1. `StockPriceService.warnIfPriceOutlier` 의 ERROR 로그 (`[가격이상] ...`, KIS raw 필드 포함).
2. `stock_price` 테이블 — 가드가 **가격을 보정하지 않으므로** ×10 값이 그대로 적재된다.

## 2. 두 가설을 필드 관계로 구분한다

| 가설 | 판별 신호 | 분류 |
|---|---|---|
| **응답 자체가 ×N** (통합시세 UN 필드 규약 차이) | 현재가·고가·저가가 **함께** 스케일 → 응답 내부는 자기일관(현재가 ∈ [저가,고가]), 등락률 정상. 직전 정상가(DB median) 대비 비율만 ~10 | `BATCH_SCALED` |
| **현재가 필드 매핑 오류** | 고가·저가는 정상인데 현재가만 당일 밴드 밖 | `CURRENT_FIELD_OUTLIER` |
| 모호 | 밴드 정상인데 등락률만 초과 / 통배수+등락률 비정상 | `AMBIGUOUS` |

구현: `util/PriceOutlierDiagnostics.classify(cur, high, low, changeRate, dbAnchorPrev)` (순수 함수).
임계는 P0-1 가드와 동일(밴드 ±10%, 등락률 31%, 앵커 배수 5×/0.2×).

## 3. NXT/이중상장 군집 — 스키마 한계와 프록시

`stock_master` 에는 **NXT/이중상장 플래그가 없다**(컬럼은 `market` = KOSPI/KOSDAQ/KONEX/ETF 뿐).
NXT 는 종목 속성이 아니라 **거래 venue**이고, KIS 를 `UN`(KRX+NXT 통합)으로 호출 중이다.

→ NXT 관여 여부는 **`fetched_at` 세션 시간대**를 프록시로 추론한다:

| 세션 라벨 | 시각 | venue |
|---|---|---|
| `NXT_PREMARKET` | 08:00–09:00 | **NXT 단독** |
| `KRX_REGULAR` | 09:00–15:30 | KRX+NXT 중첩 |
| `KRX_CLOSE_AUCTION` | 15:30–15:40 | 종가단일가 |
| `NXT_AFTERHOURS` | 15:40–20:00 | **NXT 단독** |
| `OFF_HOURS` | 그 외 | — |

**판정 규칙:** ×10 이벤트가 `NXT_PREMARKET`/`NXT_AFTERHOURS` 에 몰리면 → 통합시세(UN) 필드 규약
차이(=NXT 관여) 강하게 의심. `KRX_REGULAR` 에 고르게 퍼지면 → venue 무관(필드 매핑/응답 일반 문제).

## 4. 실행법 — 실제 분류 결과 뽑기

### (a) DB 스캐너 (신규, 읽기 전용)
```
GET /api/diagnostics/price-scaling?hoursBack=720&maxEvents=200   (인증 토큰 필요)
```
`stock_price` 이력에서 종목별 median 대비 ×5+ 튄 행을 찾아 분류기로 가설 판정 + market·세션 집계.
응답 예시 구조:
```json
{ "success": true, "data": {
  "scannedRows": 0, "outlierEvents": 0, "distinctOutlierStocks": 0,
  "byMarket": {}, "bySession": {}, "byKind": {}, "events": [...], "note": "..." } }
```
> ⚠️ 이 엔드포인트는 **이번 변경에 새로 추가**되었으므로, 실행하려면 백엔드를 재배포해야 한다.
> 현재 떠 있는 인스턴스는 구버전이라 404. (서비스 로직: `PriceScalingDiagnosticService`)

### (b) 기존 로그 grep (재배포 없이 즉시)
```
grep "\[가격이상\]" app.log | grep "직전 저장가"          # DB 앵커 그물 발화 = 통배수 후보(BATCH)
grep "\[가격이상\]" app.log | grep "범위 밖"               # 밴드 그물 발화 = 현재가 단독(FIELD)
```
각 라인의 `KIS raw: stck_prpr/hgpr/lwpr/sdpr/prdy_ctrt` 를 `PriceOutlierDiagnostics.classify` 에
넣으면 동일 분류가 나온다. 시각은 라인 타임스탬프로 세션 버킷팅.

## 5. 산출물

- `util/PriceOutlierDiagnostics` — 두 가설 분류기 (순수, 테스트 `PriceOutlierDiagnosticsTest`).
- `service/PriceScalingDiagnosticService` — DB 스캔 + market/세션 군집 집계 (읽기 전용, 테스트 `PriceScalingDiagnosticServiceTest`).
- `GET /api/diagnostics/price-scaling` — 온디맨드 진단 엔드포인트.
- 가격 보정/수정 **없음** — `warnIfPriceOutlier` 본체 미변경.

## 6. 결론(데이터 대기) 후속

스캐너/로그로 실제 분포(byKind·bySession)를 뽑은 뒤:
- `BATCH_SCALED` 우세 + NXT 단독 구간 집중 → **응답 자체 ×N (UN 통합시세 필드)** 확정 → 별도 보정 티켓
  (예: NXT 단독 시간대만 `J`(KRX 단독)로 폴백 비교, 또는 통합시세 가격 필드 규약 재매핑).
- `CURRENT_FIELD_OUTLIER` 우세 → **파싱/필드 매핑** 확정 → 매핑 수정 티켓.

(근본 보정은 본 진단 결과 확인 후 별도 티켓 — P0-2 범위 아님.)
