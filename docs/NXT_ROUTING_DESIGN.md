# DESIGN — NXT/연장장 주문 라우팅 + 방어 청산 + 종가봇 재설계

> 2026-07-08 세션 산출물. **2026-09-14 KRX 거래시간 연장(NXT ~20:00) 대응 준비.**
> 전제 문서: STOCK_AZ_FULL §14·§16·§19(P2-13 진단 종결 2026-07-06), CLAUDE.md §4d, VERIFICATION_BACKLOG P2-13.
>
> **원칙**: 오늘 작업 = 구현+테스트까지. **활성화는 9/14 이후**(연장장 실검증 불가) — 신규 경로는 전부 **flag 기본 OFF**,
> OFF 시 도달 불가. P2-13 "수용 갭" 결정 자체는 유지 — 이 작업은 갭을 없애는 게 아니라 **9/14 이후 없앨 수단을 준비**하는 것.

---

## 0. 현행 주문 계층 실측 매핑 (Phase 0, 2026-07-08 코드 기준, 파일:라인)

### 0.1 KIS 주문 리프 (`KoreaInvestmentService`)
| 요소 | 실측 | 라인 |
|---|---|---|
| 매수 | `buyStock(code,qty,price)` → `placeOrder(...,"TTTC0802U","buy")`, `@CircuitBreaker(kisApi)`, rateLimiter CRITICAL·3retry | L1236-1240 |
| 매도 | `sellStock(code,qty,price)` → `placeOrder(...,"TTTC0801U","sell")`, 동일 | L1259-1263 |
| 주문 바디 | `CANO`·`ACNT_PRDT_CD`·`PDNO`·**`ORD_DVSN="00"`(지정가)**·`ORD_QTY`·`ORD_UNPR`(setScale 0 DOWN) | L1293-1299 |
| URL | `/uapi/domestic-stock/v1/trading/order-cash`, `hashkey` 헤더 필수 | L1290, L1301-1309 |
| 거래소/세션 파라미터 | **없음** — 바디에 거래소구분·SOR·연장장 필드 부재 = 기본 KRX 정규 연속세션 | (부재) |
| 실패 처리 | `HttpStatusCodeException`→ body `rt_cd` 파싱해 "명시적 거부" 반환(killswitch 미발동) / 그 외 → null(불확실=상위 killswitch) | L1334-1354 |

> **모든 주문은 지정가(`ORD_DVSN=00`)** — 시장가 경로 자체가 없음(P2-13-b 기각 근거 유지). NXT 라우팅은 이 바디에
> **거래소구분 필드 추가**가 필요하나 — **⚠ KIS 문서 확인 필요**(§0.4).

### 0.2 주문 게이트 순서 (`RealTradeService`, 신규 경로도 전부 통과 필수 — §4d)
- **BUY** `executeBuy` L241-378: `validateTradeInput` → `safetyService.checkBuy`(killswitch+일매수한도) → `getBalanceInfo(true)`(실시간 KIS 잔고, null=중단) → 가용현금 체크 → **`orderIntentService.tryAcquire(BUY)` 멱등키(fail-CLOSED)** → `auditService.start` → `kisService.buyStock` → 불확실/DB저장실패=killswitch. 봇 층에서 `passesPriceSanity`(진입가 sanity)가 BUY 후보에만 선적용.
- **SELL** `sell` L385-530: `validateTradeInput` → `isRealTradingConfigured` → `getBalanceInfo(true)` → 보유수량 체크 → `safetyService.checkSell`(killswitch만·손절 항상가능) → **`sellInflightService.tryAcquire` in-flight 마커(V45, fail-OPEN)** → `auditService.start` → `kisService.sellStock` → 불확실/DB실패=killswitch → `finally release`.
- **일일손실 브레이커**(`entryBlockedByDailyLossBreaker`)는 **봇 진입 경로**(scalping/swing buy)에서 선체크 — 매도/청산엔 미적용(비대칭, §4d-6).

### 0.3 청산·스케줄 실측
| 경로 | cron | 상태 | 비고 |
|---|---|---|---|
| `executeRegularSessionLiquidation` | `0 20-28 15 * * MON-FRI` | ACTIVE | 리더→`shouldRunLiquidationWindow`(15:20~15:28)→봇소유∩KIS잔고 `sellPortfolioMatching`(지정가, **confirmFill 미사용**)→잔고 재조회 all-or-nothing→빈=`markLiquidatedToday`, 잔여=다음분 재시도 |
| `warnIfLiquidationMissed` | `0 29 15 * * MON-FRI` | ACTIVE | 15:29 RISK 알림만(주문 없음) |
| `executeScalpingBuyLogic` | `*/30 * 9-11 * * MON-FRI` | ACTIVE | 09:45~10:30 골든윈도우 + `isMarketClosed()`(15:30). **REAL 하드 비활성**(VIRTUAL 전용) |
| `executeSwingBuyLogic` | `0 0 14 * * MON-FRI` | ACTIVE | 14:00 단발, `isMarketClosed()` |
| `executeClosingBuyLogic` | `// @Scheduled(0 15 15 ...)` | **주석 비활성** | 리더 게이트·`passesPriceSanity` 결여(재설계 필요) |
| `executeClosingSellLogic` | `// @Scheduled(*/30 * 8-19 ...)` | **주석 비활성** | 리더 게이트·SELL 멱등키 결여 |

- `isMarketClosed()` (L3689): **09:00~15:30 KST** 경계(주말·공휴일 포함). Clock 주입(`LocalTime.now(clock)`)으로 테스트 결정성.
- **15:30 상한이 이중 가드**: 각 매도 내부가 `isMarketClosed()`(15:30)를 08:00~20:00 윈도우 체크보다 **먼저** 호출 → 08~20 윈도우는 15:30 이후 死코드. `FORCE_LIQUIDATION`(15:20~15:28)만 `isMarketClosed` 우회하는 유일 경로.

### 0.4 ⚠ NXT 주문 파라미터 — **KIS 문서 확인 필요(추측 파라미터 금지, §4c)**
- 현행 시세는 `FID_COND_MRKT_DIV_CODE=UN`(KRX+NXT 통합)으로 이미 NXT 커버(조회 전용). **주문(order-cash) 바디엔 거래소 구분 필드가 없다.**
- 넥스트레이드(NXT) 개장 후 KIS OpenAPI order-cash 에 **거래소구분/SOR 필드**가 추가된 것으로 알려짐 — 후보 `EXCG_ID_DVSN_CD`(값 예: `KRX`/`NXT`/`SOR`) — **그러나 2026-07-08 시점 리포지토리·설정·주석에 근거 없음, 웹 검색으로도 공식 스펙 미확인.** 
- **결정**: 파라미터 **이름·값을 코드에 하드코딩하지 않는다.** application.yml 에 `kis.order.nxt-exchange-*` placeholder 로 externalize(기본 공란) → **미설정 시 NXT 주문 경로는 예외 던지며 거부(fail-CLOSED)**. 9/14 전 KIS Developers 포털(`apiportal.koreainvestment.com` order-cash 스펙)·GitHub `koreainvestment/open-trading-api` 로 **실제 필드명/값 확정 후 설정 주입**.

---

## 1. 세션 모델 & 라우팅 (Phase 1)

### 1.1 `OrderSession` (신규 top-level enum, `util/OrderSession.java`)
```
REGULAR       — KRX 정규 연속세션(현행, 09:00~15:30). 기존 모든 호출부 기본값.
NXT_EXTENDED  — NXT 연장 세션(15:30~20:00, 9/14 이후). 방어 청산 한정.
```

### 1.2 순수 라우터 (`util/OrderSessionRouter.resolveOrderSession`, 테스트 대상)
```
resolveOrderSession(LocalTime now, boolean nxtRoutingEnabled, OrderSession requested) -> OrderSession
  requested == REGULAR/null           -> REGULAR
  requested == NXT_EXTENDED:
    !nxtRoutingEnabled                 -> REGULAR  (flag OFF 강제 다운그레이드 — 호출부가 WARN, §4c 조용한 다운그레이드 금지)
    now ∈ (15:30, 20:00]               -> NXT_EXTENDED
    그 외 시각                          -> REGULAR  (정규장 시간엔 정규 라우팅이 맞음)
```
- 상수 `REGULAR_CLOSE=15:30`, `NXT_EXTENDED_CLOSE=20:00`.
- **경계 테스트**: 15:30 정각(REGULAR)·15:31(NXT)·20:00 정각(NXT)·20:01(REGULAR)·flag OFF(NXT 요청→REGULAR).

### 1.3 KIS 리프 오버로드 (기존 시그니처 보존)
- `buyStock(code,qty,price)` / `sellStock(code,qty,price)` — **무수정, 내부적으로 REGULAR 세션**으로 `placeOrder` 호출 → 바디 바이트 현행 동일.
- 신규 `buyStock(code,qty,price,OrderSession)` / `sellStock(...,OrderSession)` — 둘 다 `@CircuitBreaker(kisApi)`(신규 경로도 CB 보호).
- `placeOrder(...,OrderSession requested)`:
  1. `effective = resolveOrderSession(kstNow, nxtRoutingEnabled, requested)`.
  2. `requested==NXT_EXTENDED && effective==REGULAR` → **WARN**("NXT 요청이 flag OFF/시간 밖으로 REGULAR 강등").
  3. `effective==REGULAR` → **바디 현행 그대로**(NXT 필드 미추가 = 바이트 동일).
  4. `effective==NXT_EXTENDED` → `nxt-exchange-param-name/value` 설정돼 있으면 바디에 추가, **없으면 예외 throw(주문 거부, fail-CLOSED)** — §0.4.
- flag `bot.nxt-routing.enabled` = `@Value("${bot.nxt-routing.enabled:false}")`(atr-trading 패턴 동일, 기본 false).

---

## 2. NXT 방어 청산 (Phase 2)

### 2.1 목적
P2-13 유일 오버나잇 갭 = **15:20~15:28 지정가 미체결 잔여**(익일까지 보유). 9/14 후 NXT 창(15:30~20:00)에서
이 잔여를 **재청산**할 수단. 진입은 불건드림(§16-2) — **방어적 청산 창 확대만**.

### 2.2 `executeNxtLiquidationRetry` (신규)
- `@Scheduled(cron = "0 35-55/5 15-19 * * MON-FRI")` 성격 = **15:35~19:55 매 5분**(정규 청산 15:28 종료 후 ~ NXT 종료 직전).
- **가드(전부 AND)**: `botLeader.isLeaderForBot()`(fail-CLOSED) AND `botActive` AND `!killSwitchTriggered` AND `bot.nxt-liquidation.enabled`(기본 false) AND `bot.nxt-routing.enabled`(ON) AND **당일 청산 잔여 존재**(`!isLiquidatedToday()` 이며 봇소유∩KIS잔고 non-empty).
- **대상**: `botOwnedCodes ∩ getPortfolio()`(수동/untracked 제외, 기존 `sellPortfolioMatching` 재사용) — **SELL 게이트 전부 경유**(killswitch·`sellInflightService`·audit), **지정가**(시장가 금지). NXT 세션으로 라우팅(`OrderSession.NXT_EXTENDED`).
- 잔여 판정·`markLiquidatedToday` 는 정규 청산과 **동일 로직 재사용** — NXT 창에서 완청산되면 마킹.
- 순수 창 판정 `shouldRunNxtLiquidation(now, flags, hasResidual)` 분리 + 경계 테스트.

### 2.3 게이트 관통 배선 (SELL 경로에 세션 전달)
- `RealTradeService.sell(code,qty,price)` 무수정 → 신규 `sell(code,qty,price,OrderSession)` 오버로드(게이트 동일, 마지막 `kisService.sellStock`에 세션 전달). 기존 sell = REGULAR 위임(현행 보존).
- `TradeService` 인터페이스 + `VirtualTradeService`(세션 무시 — 가상 원장, KIS 미경유) 오버로드.

### 2.4 P2-13-a 주석 처리
- **이미 정정됨**: `AutoTradingBotService.java:232-242` 주석은 현재 "실제 binding=isMarketClosed()(15:30)"로 정정 완료(2026-07-06 진단 이후 반영). → 이번엔 **오서술 정정이 아니라, NXT 배선 시 이 이중가드가 어떻게 풀리는지**(NXT 청산은 `isMarketClosed` 우회 별도 경로임을) 주석에 **추가**.

---

## 3. 종가봇 재설계 (Phase 3, 주석 비활성 유지)

- `executeClosingBuyLogic`/`executeClosingSellLogic` 을 **연장장 전제로 재작성**:
  - **진입(§16-2 불변)**: KRX 정규장 내에서만(NXT 진입 **하드코딩 금지**). 종가 단일가 매수 시각을 설정(`bot.closing.buy-time` 등)으로 이동 가능하게.
  - **결여 게이트 배선**: 리더(`isLeaderForBot`)·`passesPriceSanity`·killswitch·일일손실 브레이커·(매도)SELL 멱등키(`sellInflightService`) 전부 추가.
  - `@Scheduled` 은 **계속 주석**(9/14 활성화 시 한 줄 해제). 순수 판정 분리+테스트.
- 활성화 절차는 §5.

---

## 4. 불변식 준수 체크(위반 시 중단)
1. flag OFF = 바이트 현행 동일. 신규 경로 flag OFF 시 도달 불가 ✔(REGULAR 위임·enabled 가드).
2. §4d 전 게이트 무손상 — 신규 경로도 동일 게이트 관통(우회 신설 금지) ✔(RealTradeService.sell 오버로드가 게이트 재사용).
3. KIS 비멱등 — 재시도/롤백 추가 금지, KIS성공+DB실패=killswitch 신규 경로 동일 ✔.
4. §16-2 봇 진입 KRX 09:00~15:30 유지 ✔(청산 창만 확대, 진입 무변경·종가봇 진입 KRX 하드코딩).
5. P2-13 "수용 갭" 결정 유지 — 9/14 전엔 flag OFF 로 현행 그대로 ✔.

---

## 5. 9/14 활성화 절차 (사람이 확정 — 오늘 작업 범위 밖)
1. **KIS NXT order-cash 거래소구분 필드 확정** — 이름·값(§0.4). `apiportal.koreainvestment.com` order-cash 스펙 / GitHub `koreainvestment/open-trading-api`.
2. 서버 `.env`/application.yml 에 `kis.order.nxt-exchange-param-name`·`-value` 주입(compose backend `environment:` 이중배선 §4b) → **backend recreate**.
3. `bot.nxt-routing.enabled=true` + `bot.nxt-liquidation.enabled=true`.
4. 종가봇 재활성 시 `@Scheduled` 주석 해제 + 종가 단일가 시각 설정.
5. **소액 실검증**(단일 종목·소량)으로 NXT 주문 접수/체결 로그 1회 확인 후 정상 운용.
