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

## 6. 실측 결과 (2026-06-04, 운영 DB 90일) — ×10 미발생 확정

운영 `stock_price` 90일 전수를 직접 조회해 확정함.

**결정적 단일 스캔** (윈도우·앵커 불필요 — 현재가가 자기 당일 밴드 밖인지만 봄):
```sql
SELECT COUNT(*) FROM stock_price
WHERE fetched_at >= NOW() - INTERVAL 90 DAY
  AND high_price > 0 AND low_price > 0
  AND (current_price < low_price*0.9 OR current_price > high_price*1.1);
-- 결과: 0
```

| 분포 | 결과 |
|---|---|
| **CURRENT_FIELD_OUTLIER** (현재가 단독 밴드 밖) | **0 건** |
| **BATCH_SCALED** (응답 전체 ×N) | **0 건** (자기일관 위반/배수 스파이크 없음) |
| bySession / byMarket | 해당 없음 (분류 대상 0) |

→ **"현재가 ×10" 오염은 현재 운영 데이터에서 미발생.** P0-1/P0-2가 쫓던 현상은 재현되지 않음
(과거 일시적 이슈였거나 가드 추가 이후 소멸).

**주의 — MIN 앵커 스캔의 함정**: `MIN(90일)` 을 앵커로 쓰면, 종목당 단 하나의 저측 글리치 행이
앵커를 망가뜨려 정상 고가 행 수천 개가 false positive 로 잡힌다. 실제 확인된 부수 현상은
×10 과 무관한 별개 이슈였다:
- **동결/스테일 피드** (예: 001230 이 11,400 에 수주간 고정 — 상폐/정지 의심)
- **저측 글리치** (단발 ×0.2 수준 행)
- **손상 등락률 필드** (예: 011930 prdy_ctrt=900.00%)

→ 위 3종은 별개 티켓 후보. **P0-1 가드는 로깅 전용 안전망으로 유지하면 충분** — 동결/랠리 종목에서
DB앵커 그물이 의심 로깅을 해도 가격 미보정이라 무해.

## 7. (보류) 근본 보정 후속

만약 향후 재발해 `BATCH_SCALED`/`CURRENT_FIELD_OUTLIER` 가 다시 검출되면:
- `BATCH_SCALED` 우세 + NXT 단독 구간 집중 → **응답 자체 ×N (UN 통합시세 필드)** → NXT 단독 시간대만
  `J`(KRX 단독) 폴백 비교 또는 통합시세 가격 필드 규약 재매핑.
- `CURRENT_FIELD_OUTLIER` 우세 → **파싱/필드 매핑** → 매핑 수정.

(현재는 미발생이므로 근본 보정 불필요 — 재발 시 별도 티켓.)

## 8. 2026-07-06 재점검 — 경로 전수 재매핑 + 봇 방어선 추가

### (a) 코드 경로 전수 재매핑 (3-트랙 병렬 조사) — ×10 산술 유입 지점 없음 재확인

| 경로 | 소스 | 시장구분 | 목적지 | ×10 유입 가능성 |
|---|---|---|---|---|
| 현재가 REST | FHKST01010100 (`getStockPriceInternal`) | **UN 고정**(종목별 분기 없음) | `stock_price` + priceCache + UI | **응답 자체 ×N 만 가능** (파싱 무결) |
| 현재가 네이버 폴백 | m.stock.naver `closePrice` | — | 동일 | 낮음 (`parsePrice` 콤마 제거만) |
| 일봉 히스토리 | `getDailyOhlcv` | **J (KRX 단독)** | `stock_price_history` | 낮음 — **UN 오염과 격리된 독립 소스** |
| WS 체결틱 | H0STCNT0 | KRX | `RealtimePriceBus`(in-memory) | **DB 미저장** — 영속 오염과 무관. 필드 인덱스 규약 일치 |
| 전종목 크롤 | Jsoup 재무 | — | `stock_financial_data` | 무관(가격 테이블 아님) |

- 파싱 전 경로(`getBigDecimalValue` L1173 / `parsePrice` L1018 / WS `parseDecimalSafe`)는 `new BigDecimal(콤마제거)` 뿐 —
  `multiply/divide 10`, `movePointLeft/Right`, `scaleByPowerOfTen` 가격 적용 **0건** (서비스 패키지 전수 grep).
- `RealTimeDataCache` 는 호출부 없는 orphan (×10 무관).
- **핵심 구조 확인: 현재가=UN vs 히스토리=J 소스 분리.** 재발 시 ×10 이 현재가에만 나타나고 차트(히스토리)는
  정상이라면 UN 응답 규약 문제로 즉시 좁혀진다. 역으로 이 분리 덕에 히스토리 종가는 봇 가드의 유효한 앵커가 된다(아래 (c)).

### (b) 실측 증거 — 운영 재스캔 완료(2026-07-06): **전부 0건, §6 결론 재확정**

| 스캔 | 기간 | 결과 |
|---|---|---|
| `stock_price` 밴드 스캔 (현재가가 당일 [저가×0.9, 고가×1.1] 밖) | 30일 | **0건** |
| `stock_price_history` 전일 대비 5×/0.2× 점프 (J 소스 대조군) | 60일 | **0행** |
| `[가격이상]`/`[가격이상-진단]` 로그 grep | 보존분* | **무발화** |

*로그 보존은 당일 재배포(컨테이너 recreate)로 짧음 — 판정은 컨테이너 수명과 무관한 DB 증거(30/60일)가 결정적.
사용한 명령:

```sql
-- 밴드 스캔(30일): stock_price 현재가가 자기 당일 밴드 밖 (§6 결정적 스캔과 동일)
SELECT COUNT(*) FROM stock_price
WHERE fetched_at >= NOW() - INTERVAL 30 DAY AND high_price>0 AND low_price>0
  AND (current_price < low_price*0.9 OR current_price > high_price*1.1);

-- 히스토리 전일 대비 5×/0.2× 점프(60일): J 소스 대조군 — 0건 + stock_price N건이면 UN 가설 확정
SELECT h1.stock_code, h1.trade_date, h1.close_price, h2.close_price AS prev_close,
       ROUND(h1.close_price/h2.close_price,2) AS ratio
FROM stock_price_history h1
JOIN stock_price_history h2 ON h2.stock_code=h1.stock_code
 AND h2.trade_date=(SELECT MAX(trade_date) FROM stock_price_history
                    WHERE stock_code=h1.stock_code AND trade_date<h1.trade_date)
WHERE h1.trade_date >= CURDATE()-INTERVAL 60 DAY
  AND (h1.close_price>=h2.close_price*5 OR h1.close_price<=h2.close_price*0.2);
```
```bash
docker compose logs backend --since 720h | grep "\[가격이상\]"        # 3중 그물 발화
docker compose logs backend --since 720h | grep "\[가격이상-진단\]"   # UN vs J raw 대조
```
(또는 기배포 `GET /api/diagnostics/price-scaling?hoursBack=720` — bySession 으로 NXT 군집 판정.)

### (c) 판정 — **재현 불가 최종 확정(2026-07-06 재스캔 0건)**. §4c 원칙대로 "고쳤다"고 위장하지 않음

- 파싱 계층 수정 **없음** — 앱 코드에 결함 산술이 없고, 운영 실측 최신 증거(§6)가 0건이므로 고칠 대상이 없다.
  재발 시 §7 분기(BATCH_SCALED → UN 규약 재매핑 / CURRENT_FIELD_OUTLIER → 필드 매핑 수정)를 그대로 따른다.
- 대신 **방어선 추가(2026-07-06)**: 봇 진입 가격 sanity 가드 — `util/PriceSanityGuard.judge()`(순수함수,
  `PriceSanityGuardTest`) + `AutoTradingBotService.passesPriceSanity()`(스캘핑·스윙 진입 직전).
  - **전일 종가 대비 ±50% 초과 → 해당 종목 진입 차단 + 텔레그램 리스크 알림(종목별 10분 스로틀).**
  - 앵커 = `StockPriceHistory` 최신 종가(**J 소스라 UN 오염과 독립** — KIS 응답 역산(prdy_vrss)은 통배수
    오염 시 같이 스케일돼 무력이므로 금지). 앵커 결측/0/4일 초과 노후 = UNKNOWN = 통과(§4c).
  - §16-3(이상치 로깅만·미보정)과 비충돌 — 가격은 안 고치고 **주문만 차단**. 1차 탐지망은 여전히
    `warnIfPriceOutlier` ERROR 로그.
