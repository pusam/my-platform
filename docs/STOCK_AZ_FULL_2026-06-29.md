# 주식 플랫폼 A–Z 전수 배치도 (2026-06-29 생성 · **2026-06-30 갱신**)

> **생성**: 2026-06-29, 코드 직접 전수(Explore 3-레이어 매핑) 기준. **최종 갱신**: 2026-06-30(차트 백테스트·P0-pykrx KIS 지수전환·V36 unique·간밤 미국장 tilt 반영 — §20 세션 요약).
> **위치**: `docs/STOCK_AZ_FULL_2026-06-29.md` — 기존 `docs/STOCK_AZ_FULL.md`(2026-06-08판, GNB 3탭·차트기법 누락)을 **대체**한다.
> **출처 원칙**: 불변식·산식은 `CLAUDE.md`가 1차 출처. **정밀 cron 시각/엔티티·컨트롤러 개수는 코드가 출처**(아래 수치는 매핑 시점 근사) — 변경 시 코드 우선.
> 한국 주식(KRX 정규장 + NXT 대체거래) 발굴/분석/모의·실전 자동매매 통합 개인 플랫폼.

---

## 0. 한 줄 정의 & 스택

Spring Boot(backend, 메인 API·스케줄러·매매봇) + FastAPI(python-backend, pykrx 보조분석) + Vue 3/Vite(frontend) + MariaDB + Redis + KIS REST/WS + Gemini + DART + 텔레그램(3채널).

```
사용자(브라우저/Flutter WebView)
        │ HTTPS
     [nginx]  TLS 종단·라우팅·SPA 정적
     /  │  \
 /api/* │  \ /api/v2/*
        │   \
   [backend:8080]──┬──[mariadb:3306]  L3 영속
   Spring Boot 4.0 ├──[redis:6379]    L2 캐시+JWT+락
        │ 내부호출 └──외부: KIS/DART/Gemini/Naver/Telegram
   [python-backend:8000]  pykrx(종목 OHLCV·chart) + regime/sector
        └──[redis] (py: 프리픽스)
        └──지수(KOSPI)는 Java(KIS 일봉) 경유 — pykrx 지수 엔드포인트 깨짐(2026-06-30, §20)
```

---

## 1. 인프라 (Docker Compose 6서비스)

| 서비스 | 이미지 | 포트(호스트:컨테이너) | 의존 | 헬스체크 | 메모리 |
|---|---|---|---|---|---|
| **nginx** | nginx:1.25-alpine | 0.0.0.0:80,443 | backend, python-backend | wget /health | — |
| **backend** | Spring Boot 4.0 (temurin:17-jre) | 127.0.0.1:8080 | mariadb, redis | curl /api/health | 1536M |
| **python-backend** | python:3.12-slim + uvicorn(2 workers) | 127.0.0.1:8000 | redis | curl /api/v2/health | 512M |
| **mariadb** | mariadb:11.2 | 127.0.0.1:3306 | — | innodb_initialized | 1G |
| **redis** | redis:7-alpine | 127.0.0.1:6379 | — | redis-cli ping | — |
| **certbot** | certbot/certbot | — | — | — | — |

- DB/Redis는 **loopback만 노출**(127.0.0.1), 외부는 nginx 443만.
- 로깅: 전 서비스 json-file max-size=50m/max-file=5/compress(디스크 오버플로 방지).
- 타임존: 전 서비스 Asia/Seoul.

### 1-1. nginx 라우팅 (`nginx/*.conf`)
```
HTTP 80
  /health                      → 200 "ok"
  /.well-known/acme-challenge/ → certbot webroot
  /*                           → 301 https

HTTPS 443  (TLS 1.2+, HSTS, CSP, X-Frame DENY)
  /api/sse/*       → backend:8080   (SSE: buffering off, 600s, HTTP/1.1)
  /api/v2/*        → python-backend:8000  (resolver 127.0.0.11, 120s)
  /api/auth/login  → backend  (limit_req login_zone 10r/m burst5)
  /api/auth/signup → backend  (limit_req signup_zone 5r/m burst2)
  /api/*           → backend:8080  (limit_conn 50, 120s)
  /actuator        → 404 (외부 차단)
  /assets/*        → 정적 (1y immutable)
  /index.html      → no-cache
  /*               → try_files → /index.html (SPA)
```

### 1-2. 빌드/배포
- **Gradle 멀티모듈**(`settings.gradle`): `backend`(Spring Boot 4.0) · `frontend`(Vue/Vite) · `jwt-redis`(인증) · `core`(공용).
- **프론트 빌드 통합**: `buildFrontend`(npm install+build) → `copyFrontend` → `backend/src/main/resources/static`. `-PskipFrontend`로 생략 가능.
- backend 주요 의존: web·data-jpa·security·actuator·mail·cache·Caffeine·Redis·Batch·Springdoc·Flyway(mysql scheme)·POI·ta4j·jsoup·WebAuthn4j·Resilience4j(KIS/DART/Gemini circuit breaker).
- Dockerfile: backend(temurin:17, MaxRAMPercentage=75%, 비루트 spring), python(python:3.12-slim, uvicorn 2 workers).

---

## 2. 백엔드 (Spring Boot) 패키지 구조

`com.myplatform.backend` (메인 앱, 약 396 Java 파일):

| 패키지 | 역할 | 규모(근사) |
|---|---|---|
| `controller` | REST 엔드포인트 | ~50 컨트롤러 |
| `service` | 비즈니스 로직 | ~95 서비스 |
| `entity` / `repository` | JPA 엔티티/리포 | ~48 쌍 |
| `dto` | 전송 객체 | 70+ |
| `config` | 스프링 설정 | ~14 |
| `scheduler` | 크론 오케스트레이터 | 6 |
| `aspect` / `listener` / `exception` / `util` | AOP·기동훅·예외·유틸 | — |

보조 모듈: `jwt-redis`(JwtTokenProvider·JwtAuthenticationFilter·RedisTokenService·EntryPoint), `core`(ApiResponse·GlobalExceptionHandler·DateTime/Json/String util).

---

## 3. 컨트롤러 전수 (도메인별)

### 3-1. 발굴·추천·차트
- **`RecommendationController`** `/api/recommendation`
  - `GET /top5` 모멘텀 종합추천(오늘 탭 전용)
  - `GET /value-top10` 저평가(PBR·ROE·부채·흑자)
  - `GET /growth-top10` 성장(매출·이익성장+PEG)
  - `GET /oversold-top10` 낙폭과대(RSI과매도+MA20낙폭+반등)
  - `GET /earnings-top10` 실적(흑자전환·이익급증)
  - `GET /smartmoney-top10` 수급(외국인·기관 순매수)
  - `GET /strong-value-frequency` STRONG_BUY+강가치 빈도(phase35 검증)
  - `GET /judgment-board` ⭐신규(2026-06-30, B안) 종합 판단 보드(매수후보 3계층 신호 비교, 산식 무변경 조립)
- **`ChartSignalController`** `/api/recommendation` *(신규, 차트기법)*
  - `GET /trend-pullback-top10` 차트 타이밍(정배열+눌림목, **검증 전 베타**)
  - `GET /sector-strength` 섹터 상대강도('덜 빠지는 섹터')
- **`QuantScreenerController`** `/api/quant-screener` 마법공식·턴어라운드·PEG

### 3-2. 종목 상세·시세
- **`StockDetailController`** `/api/stock`: `{code}/summary` `quick`(1단 3~5s) `heavy`(2단) `conclusion`(룰결론) `checklist`(5-factor) `catalyst`(V31 재료)
- **`StockPriceController`** `/api/stock-price`: 현재가·히스토리·배치
- **`StockAnalysisController`** `/api/stock-analysis`: 기술지표·수급·투자자동향

### 3-3. 시그널·백테스트
- **`SignalOutcomeController`** `/api/signal-outcomes`: `accuracy` `accuracy-by-band`(V30~V32 조건부) `timeseries` `compare`(컷오프 전후)
- **`BacktestController`** `/api/backtest`: `performance`(적중률/평균손익/MDD/Sharpe, 비용차감)

### 3-4. 매매(봇·페이퍼)
- **`PaperTradingController`** `/api/paper-trading`: `account/*` `portfolio` `trades` `statistics` `bot/{status,config,toggle,performance}` `real/{account,position,trades,place-order}`(KIS)

### 3-5. 시장·섹터·수급
- **`MarketTimingController`** `/api/market-timing`(ADR 과열/공포)
- **`SectorTradingController`** `/api/sector-trading`(섹터 거래대금·상위)
- **`InvestorTradeController`** `/api/investor-trade`(외국인/기관/프로그램)

### 3-6. 리스크·안전·관심
- **`RiskController`** `/api/risk` · **`TradingSafetyController`** `/api/trading-safety`(킬스위치) · **`WatchlistController`** `/api/watchlist`

### 3-7. 데이터·외부·알림·시스템
- News·AiStrategy·Telegram·Sse·Admin·Kis·Diagnostics·Health, 그리고 ExchangeRate/Gold/Silver/Oil/GlobalFutures/GlobalMarket(상품·글로벌).
- **`MarketIndexController`** `/api/market/index` ⭐신규(2026-06-30): `GET /kospi-daily?days=130` — KIS 일봉 KOSPI 종합지수(0001) 공급. **permitAll**(공개 시장데이터). python regime/sector 가 내부 소비(pykrx 지수 대체, §20). 응답 `{success, data:[{date,open,high,low,close}], count}`.
- **`GlobalFuturesController`** `/api/global-futures`: `/quotes` `/quotes/{symbol}` `/kospi-impact`(0~100 개장 영향예측) + **`GET /overnight-us`** ⭐신규(2026-06-30): 간밤 미국장 보조 tilt(BULL/NEUTRAL/BEAR, 미검증, '오늘' 탭 참고).
- 인증/콘텐츠: Auth·User·Board·File·PasswordReset·Webauthn·Notification.
- 개인기능(주식 외): Asset·Finance·Car·Diet·Exercise·Export.

---

## 4. 서비스 (도메인별)

### 4-1. 추천·결론·체크리스트
| 서비스 | 책임 |
|---|---|
| `RecommendationService` | 5트랙 선별, 점수정규화(raw80→0~100, validCount≥3), 스냅샷, tie-break 비교자, 과열/신규진입 감점 |
| `StockConclusionService` | 결론 4단계(STRONG_BUY/BUY/HOLD/WAIT) + 매매계획(`PLAN_*` 손절-3%/익절+5%) |
| `BuyChecklistService` | 5-factor 하드룰 → 권고 |
| `StockCatalystService` | 재료 태그(Gemini V31, 종목·일자 1회 캐시, 점수 미편입) |
| `SignalOutcomeService` | 시그널 적중률(19:30 배치, 3거래일 후, V30~V32 스냅샷), `getAccuracyByBand`. **V36(2026-06-30)**: `record()` INSERT 를 `insertOutcomeIsolated`(`@Transactional REQUIRES_NEW`, selfProvider 프록시)로 격리 + `DataIntegrityViolationException` benign 처리 — `(signal_type,stock_code,signal_date)` UNIQUE 경합 패자가 호출부 tx 무오염. bm(alpha)은 KIS 지수 현재가(`getIndexPrice 0001`)라 pykrx 무관 |

### 4-2. 시세·기술·체결
| 서비스 | 책임 |
|---|---|
| `StockPriceService` | **단일 시세 경로**(L1 로컬+DB, Redis 비경유) |
| `StockDetailService` | quick/heavy 2단 로딩 |
| `TechnicalIndicatorService` | RSI/MACD/볼린저 |
| `ScalpingAnalysisService` | 체결강도(ccnl `tday_rltv`, 게이지) |

### 4-3. 봇·실주문 (CLAUDE.md §4d 안전식)
| 서비스 | 책임 |
|---|---|
| `AutoTradingBotService` | 모드(REAL/VIRTUAL), **6 @Scheduled 크론**(전략5 + 정규장 마감 강제청산), 포지션추적, 재시작정합성 `reconcilePositionsWithKis`/`computeReconciliation`(경고만), killswitch |
| `BotLeaderElectionService` ⭐신규(2026-06-29) | **멀티 인스턴스 리더 선출(fail-CLOSED)**: Redis 리스(`SET NX EX bot:leader`)+10s 하트비트(TTL 30s). 봇 크론 6개가 `isLeaderForBot()` 통과해야 주문 → 리더 1개만. Redis 장애 시 주문 중단(SchedulerLockService fail-open과 정반대). `bot.leader-election.enabled`(기본 true). **2개 생성자 → 운영 생성자 `@Autowired` 필수**(누락 시 컨텍스트 기동 불가, ApplicationContextSmokeTest 가드) |
| `RealTradeService` | KIS 실주문, 체결확인 `confirmFill`/`resolveFill`(미체결→포지션유지), KIS성공+DB실패→`triggerKillSwitchOnUncertainty` |
| `VirtualTradeService` / `BotPerformanceService` | 모의계좌 / 성과(MDD/Sharpe) |
| `PositionDropMonitorService` | 포지션 낙폭 감시(2분) |

- **정규장 마감 강제청산(2026-06-29)**: `executeRegularSessionLiquidation`(15:20) — 봇이 포지션 들고 마감하는 오버나잇 노출 방지. `BotConfig.forceRegularSessionLiquidation`(기본 ON). 가드 = 리더 AND 봇활성 AND 미killed AND 설정ON AND 시각≥15:20 → `sellAllPortfolio("REGULAR_SESSION_CLOSE")`. NXT 연장장/종가단일가 청산은 후속(P2-13).

### 4-4. 시장·섹터·수급
`SectorTradingService`(거래대금 실측만, `resolveAccumulatedValue` 폴백, 가짜값 금지) · `MarketTimingService`(ADR 20일, condition은 ADR만) · `InvestorTradeService`/`InvestorSurgeService` · **`GlobalFuturesService`**(Yahoo 선물·VIX·F&G, `getKospiImpactAnalysis` 0~100 개장 영향예측) · **`OvernightUsMarketService`** ⭐신규(2026-06-30): 간밤 미국장 보조 tilt — 순수 `classifyOvernight(es,nq,sox,vix)`(임계 임시값: 3지수 평균 ±0.6%, VIX 20/25/30, SOX −2%), GlobalFuturesService Yahoo 재사용(ES/NQ/**^SOX**/VIX). **regime 산식 미편입·`unverified=true`**(표시 전용, P3-5 캘리브레이션).

### 4-5. 외부연동 서비스
`KoreaInvestmentService`(KIS REST·OAuth·rate limiter·@Retry/@CircuitBreaker; 지수: 현재가 `getIndexPrice`(FHPUP02100000)·**일봉 `getIndexDailyOhlcv`(FHPUP02120000)** ⭐신규(2026-06-30, regime/sector용, 순수 파서 `parseIndexDaily` — ⚠ TR 은 `FID_INPUT_DATE_1`=앵커로 직전 100건 반환이라 DATE_1=오늘으로 둠)) · `KisWebSocketService`(실시간 틱) · `KisInvestorDataCollector` · `DartService` · `GeminiService` · `NaverSearchService`/`NewsService` · `TelegramNotificationService`(3채널) · **`MarketRegimeClient`/`ChartPatternClient`**(python 소비, best-effort) · **`PythonBackendHealthTracker`** ⭐신규: python 호출 소스별(regime/chart-timing/chart-sector) 성공·실패·연속실패 집계, 연속 3회 실패→텔레그램 리스크 1회. `/api/diagnostics/python-health` 노출(조용히 죽는 best-effort 가시화).

### 4-6. 캐시·스케줄·기타
`CacheWarmupService`/`MarketCacheWarmerService`(워밍) · `RealTimeDataCache`(1분봉, synchronized 보호) · `SchedulerLockService`(fail-open) · `MorningBriefingService`(07:30) · `BacktestService` · `AiStrategySnapshotService` · `QuantScreenerService`/`QuantTaService` · `ChartPatternService`(자바 차트패턴 검출, python `ChartPatternClient`와 별개).

---

## 5. 스케줄러 / 일과 타임라인

스레드풀(`SchedulingConfig`): `taskScheduler`(매매·기본) · `cacheScheduler`(워밍) · `batchScheduler`(크롤·리포트), 각 16스레드. 락: `SchedulerLockService` fail-open(Redis SET NX EX, TTL<cron). **봇 크론은 SchedulerLockService 미사용** — 대신 **`BotLeaderElectionService` 리더 게이트(fail-CLOSED, 2026-06-29)**로 멀티 인스턴스 중복 주문 차단(리더 하트비트는 10s `@Scheduled`).

| 시각(Seoul) | 잡 | 출처 |
|---|---|---|
| 06:00 | 종목마스터 갱신 | `KrxStockMasterSeeder` |
| 07:30 | 모닝브리핑(텔레그램)+재료워밍(상한5) | `MorningBriefingService` |
| 08:00 | 발굴 일일·섹터 리셋·DART 개장 | Recommendation/Sector/Earnings |
| 08:30 | 마법공식·턴어라운드 알림·재무 프리페치 | QuantScreener/Financial |
| 08:50 | 캐시 프리워밍 | `CacheWarmupService` |
| 09~11 매30초 | 스캘핑 진입(모의) | `AutoTradingBotService` |
| 08~19 매15초 | KIS 포지션 정합성 | `AutoTradingBotService` |
| 08~19 매30초 | 포지션 모니터(killswitch 체크) | `AutoTradingBotService` |
| 09~15 매3분 | 섹터 거래대금 | `SectorTradingService` |
| 09~15 매2분 | 포지션 낙폭 감시 | `PositionDropMonitorService` |
| 08~17 매15분 | 뉴스 크롤 | `NewsService` |
| 08~19 매5분 | DART 실시간 모니터 | `DartDisclosureMonitorService` |
| 11:30/14:00/17:00 | TOP5 갱신 | `RecommendationService` |
| 14:00 / 15:10 | 스윙 봇 중간점검 / 스캘핑 청산 | `AutoTradingBotService` |
| **15:20** ⭐ | **정규장 마감 강제청산**(리더+killswitch 게이트, 설정 ON 시) | `AutoTradingBotService` |
| 상시 10초 | 봇 리더 리스 하트비트 | `BotLeaderElectionService` |
| 15:35 | 섹터 마감 정산 | `SectorTradingService` |
| 15:50 / 18:00 | KIS 투자자 데이터 수집/정산 | `InvestorTradeScheduler` |
| 16:30 | ADR 수집·국면 갱신 / DART 마감 | MarketTiming/Earnings |
| 16:45 | 마감 후 알림 | `StockAlertScheduler` |
| 19:30 | 시그널 평가(3거래일 후) | `SignalOutcomeService` |
| 20:05 / 20:10 | 발굴 5트랙 야간 / 복합신호 | Recommendation/MultiConviction |
| 23:00 | 재무 영속화 | `StockFinancialDataService` |
| 03:00 | 배치 정리 | `BatchJobCleanupService` |

> ⚠ 위 cron은 매핑 근사. **정확값은 각 서비스 `@Scheduled`가 출처.** 미적용(주석) 2건: 종가봇 매수/매도(`executeClosingBuyLogic`/`SellLogic`, 2026-09 연장장 대비 재설계 필요). ※ 오버나잇은 15:20 정규장 강제청산(2026-06-29)으로 1차 방어, 연장장 청산은 후속(P2-13).

---

## 6. 엔티티 / 리포지토리 (도메인별 핵심)

- **종목/시세**: `StockMaster` · `StockPrice` · `StockPriceHistory` · `StockFinancialData` · `StockCatalyst`(V31)
- **추천/분석**: `RecommendationSnapshot`(점수·카테고리세부, growth -1=NA sentinel) · `AiStrategySnapshot` · `MarketIndicatorSnapshot`
- **매매/포지션**: `BotTradingPosition` · `BotConfig`(손절/익절%) · `VirtualAccount`/`VirtualPortfolio`/`VirtualTradeHistory` · `TradingKillSwitch` · `TradingAuditLog`
- **시그널/성과**: `SignalOutcome`(3일후 return + V30~V32 스냅샷, NULL=미수집; **V36(2026-06-30) `uq_so_type_code_date` UNIQUE(signal_type,stock_code,signal_date)** — idx_so_type_date는 컬럼순서 달라 중복 아님, 유지) · `WeeklyTradingReport`
- **시장/투자자**: `MarketDailyStatus`(ADR/condition) · `InvestorIntradaySnapshot` · `InvestorDailyTrade` · `EarningsDisclosure` · `ShortSellingBalance` · `AlertHistory`
- **인증/유저**: `User` · `EmailVerificationToken` · `PasswordResetToken` · `WebauthnCredential`/`WebauthnChallenge`
- **상품**: `GoldPrice`/`SilverPrice`/`OilPrice`, 배치추적 `BatchJobExecution`

---

## 7. 캐시 3계층 + 단일 시세경로

```
L1 Caffeine (로컬, 20+ named, TTL 30s~1h)
  stockPrice 3m·sectorTrading 5m·stockSearch 1m·chartPatterns 30m(4prefix×200)
  stockDetail{Financial 10m, Risk 3m, Chart 2m, Ai 15m}·gold/silver·week52…
L2 Redis (cache.redis.enabled=true; 섹터/수급/AI전략 도메인, JSON 직렬화)
L3 MariaDB (캐시 miss fallback)
```
- **⚠ 시세 예외(불변식)**: `StockPriceService.getStockPrice()`는 L1 로컬(ConcurrentHashMap)+DB만, **Redis 비경유**. 모든 화면의 유일한 시세 경로.
- **워밍**: `CacheWarmupService`(08:50) + `MarketCacheWarmerService`(fixedDelay 30s/1m/2m/5m), `isMarketHours()`(NXT 08~20) 밖이면 early-return.

---

## 8. 외부 연동

| 대상 | 클래스 | 비고 |
|---|---|---|
| **KIS REST** | `KoreaInvestmentService` | 현재가 FHKST01010100, 체결강도 FHKST01010300(ccnl `tday_rltv`), 투자자 FHKST20061000, 체결조회 TTTC0081R, 주문 TTTC/TTTD. OAuth·rate limiter 3단계·circuit breaker |
| **KIS WS** | `KisWebSocketService` | 실시간 틱(1초) |
| **DART** | `DartService`/`DartDisclosureMonitorService` | 공시(06/08/16:30) |
| **Gemini** | `GeminiService` | 재료분류 V31·AI분석(9/12/15시)·circuit open 시 캐시 안 함 |
| **Naver** | `NaverSearchService`/`NewsService` | 뉴스·자동완성 |
| **Telegram** | `TelegramNotificationService` | 3채널: 모닝브리핑 / 신호·매매 / 리스크 |
| **python** | `MarketRegimeClient`·`ChartPatternClient` | best-effort, 1h 캐시, 미가용 시 null/미수집 |

---

## 9. 인증/보안 (jwt-redis)

- **AT 15분 / RT 7일**, Redis 저장. `JwtAuthenticationFilter`는 `validateAccessToken`(type 검사) — RT를 Authorization으로 통과 못 하게(보안). 레거시 type=null 허용.
- **프론트 라우터 가드**: AT 만료라도 RT 있으면 세션 유지(API 401 인터셉터 자동갱신). "15분 뒤 풀림" 버그 방지.
- **로그아웃**: 서버 Redis AT/RT 삭제(멱등) + 프론트 best-effort `POST /api/auth/logout`.
- `RealTimeDataCache.updateMinuteBar`: `synchronized(bars)`로 동시 틱 IndexOutOfBounds 방지.

---

## 10. python-backend (FastAPI, pykrx)

```
app/main.py            라우터 3 등록(health/regime/chart), lifespan Redis
app/routers/
  health.py            GET /api/v2/health
  regime.py            GET /api/v2/regime/current
  chart_patterns.py    POST /api/v2/chart/timing · /sector-strength  (신규)
app/services/
  regime_service.py            KOSPI 종가 vs MA60 + MA20 5일슬로프 → BULL/BEAR/SIDEWAYS, 1h 캐시.
                               ⭐2026-06-30: 데이터 소스 pykrx get_index_ohlcv → **fetch_kospi_daily(Java KIS 일봉)**
                               로 교체(pykrx 지수 깨짐, §20). classify_regime·국면 v1·테스트 불변.
  chart_pattern_service.py     compute_timing(공용): 벌크 OHLCV(500cal일, get_market_ohlcv) → 6지표 결합 → 0~10, 30m 캐시
  sector_strength_service.py   섹터 동일가중 합성지수 vs KOSPI 상대강도. ⭐2026-06-30: _market_return 도 fetch_kospi_daily 경유
  cache_service.py             Redis(py: 프리픽스, best-effort)
app/utils/index_source.py  ⭐신규(2026-06-30)  fetch_kospi_daily(days)=Java GET /api/market/index/kospi-daily
                               → pykrx 동형 DataFrame(오름차순, '종가' 등). to_dataframe 순수(+test_index_source.py). 실패 시 None.
app/backtest/  ⭐신규(2026-06-30, P2-12 차트 백테스트)
  cost.py             수수료0.03%+세금0.18% + 슬리피지0.15%(가격적용) net_return_pct
  metrics.py          is_hit(SignalOutcome 미러)·aggregate·portfolio_mdd(K슬롯 현실MDD)·per_trade_stats·spearman
  chart_backtest_service.py  point-in-time 재생(df.loc[:D] look-ahead 차단, 진입 D+1 시가/청산 +3거래일 종가),
                             reconstruct_universe(pykrx ticker_list — 현재 깨짐 P3-4)
app/routers/chart_backtest.py  POST /api/v2/chart/backtest (온디맨드, 승격판단 데이터만)
app/indicators/  (순수함수 + pytest)
  moving_average(정배열) · disparity(60/240 이격도) · envelope(하단터치+위험필터)
  · support_rebound(중심선 반등) · box_breakout(A안 변동성박스) · sector_strength · timing_score
app/config.py          Settings(Redis) + ChartPatternConfig(모든 임계값 파라미터화 + merge override)
app/utils/korean_market.py     KST·장중판정·최근거래일·TTL
tests/  pytest: test_indicators.py · **test_backtest.py**(27건) · **test_index_source.py** ⭐2026-06-30
환경변수: **JAVA_BACKEND_URL=http://backend:8080**(index_source가 Java 호출). requests(pykrx 전이의존, 명시 추가).
```
국면 규칙 v1: BULL=종가>MA60 AND MA20상승 / BEAR=종가<MA60 AND MA20하락 / else SIDEWAYS. 검증 데이터 전 임의변경 금지.
> ⚠ **pykrx 지수·ticker_list 엔드포인트는 2026-06-30 현재 KRX 포맷변경으로 전구간 빈값**(종목 OHLCV는 정상). 지수는 KIS로 우회 완료, ticker_list(reconstructed 백테스트 전용)는 P3-4 잔여. 종목마스터는 KRX KIND HTML이라 무관.

---

## 11. 프론트엔드 (Vue 3 / Vite)

### 11-1. 라우팅 (`src/main.js`)
- 허브: **`/stock-dashboard`** → `StockTradingDashboardV2`(쿼리 `tab`/`sub`로 탭 제어). 상세: **`/stock/:code`** → `StockDetailDashboard`.
- 레거시 redirect: `/sector→?tab=market` · `/news→?tab=market&sub=news` · `/investor*→?tab=market&sub=investor` · `/ai-strategy→?tab=discover&sub=ai-strategy` · `/earnings-screener→?tab=discover&sub=screener` · `/paper-trading→?tab=trade`(admin) · `/gold|silver|oil→/global-futures`.
- **새 주식 라우트 만들지 말 것** — 탭/서브탭에 흡수.
- 가드: AT유효 또는 RT존재 → 통과. 401 인터셉터가 `/auth/refresh` 큐로 자동갱신.

### 11-2. 허브 GNB 4탭 (`components/v2/DashboardHeader.vue`)

| 탭(key) | 렌더 | 내용 |
|---|---|---|
| **오늘(today)** | `TodayBriefingTab.vue` | 시장 한줄 · **🌙 간밤 미국장 tilt(미검증 참고, 2026-06-30)** · 매수후보(55컷 momentum) · **🪝 차트 신호 관찰(백테스트 부진·관찰용·접기 기본, 2026-06-30)** · 신뢰도 스트립 · 내 포지션 · 도구 바로가기 |
| **시장(market)** | 허브 인라인 + 서브탭 | 시장지도(`SectionMarketMap`)·섹터거래대금 / 서브: 수급·타이밍·뉴스·글로벌(embedded) |
| **발굴(discover)** | 허브 인라인 + 2단 서브탭 | 상단 **'덜 빠지는 섹터' 배지(베타)** + 리스트 5트랙 + 심화도구 |
| **매매(trade)** | `PaperTradingPage.vue`(관리자) | 모의·실전·봇성과·주간리포트 |

- **발굴 리스트 서브탭**(lazy, 택1): 💎저평가·🚀성장·📉낙폭과대·💰실적·🏦수급 (`ensureDiscoverListLoaded`).
- **발굴 심화도구 서브탭**: 종합(`SectionTotalRecommendation`)·**🧭 종합판단(`SectionJudgmentBoard`, B안 2026-06-30)**·AI전략·백테스트(`SectionBacktest`)·스크리너·퀀트TA(`SectionQuantTa`).
- **발굴 2단 네비 통합(2026-07-01)**: 목록 5트랙 + 심화도구 두 서브탭 바를 **둘 다 상단**에, `discoverGroup`('list'/'deep')로 선택 그룹 콘텐츠만 표시(심화 바가 긴 리스트에 묻히던 문제 해소). **기본 진입 = 🧭종합판단 보드**(상수 한 줄 변경 가능). 빈 보드 폴백(목록 발굴 안내). 위젯 9섹션 group 게이팅 + deep 기본 시 리스트 eager 로드 가드. 모바일=그룹라벨 숨김(가로스크롤).
- **역할 분리**: 모멘텀 종합추천(`getTop5`)은 오늘 탭 전용 — 발굴에 재추가 금지.

### 11-3. 종목 상세 (`views/StockDetailDashboard.vue`, ~4,700줄)
- 헤더: 복합신호 배지 + 단기/중장기 듀얼점수 + 현재가.
- 상단 카드: `StockConclusionCard`(결론·손절/목표+MFE/MAE·점수대 적중률·재료배지) + `QuickSummaryBar`(RSI/20일/외인/기관/리스크/AI).
- 본문: `StockBriefingHeadline`(행동권고) · `StockRiskCard`(DART+뉴스+AI).
- 심화(접기 `DetailSection` v-show 마운트 유지): Peer·VolumeProfile·SupportResistance·RelatedStocks·ChartPattern.
- 듀얼스테이지: `quick`(3~5s) → `heavy`(risk/AI/peer, 캐시·lazy).

### 11-4. 컴포넌트 (`components/v2/*` 주식 도메인)
DashboardHeader · TodayBriefingTab · StockConclusionCard · QuickSummaryBar · StockBriefingHeadline · StockRiskCard · StockSearchModal(Ctrl+K) · DetailSection · BuyChecklistModal · BacktestPerformancePanel · SectionBacktest · SectionTotalRecommendation · SectionQuantTa · SectionMarketMap · InvestorTrendTab · FundamentalDiagnosisPanel · PeerComparisonCard · VolumeProfileCard · SupportResistanceCard · RelatedStocksList · ChartPatternList · MagicFormulaSmartTable · BotPnlChart · TradingSafetyWidget(killswitch) · ForecastDetailModal.
공용: VolumePowerGauge(체결강도) · DataFreshness · NotificationBell · StockCodeInput 등.

### 11-5. API 레이어 (`utils/api.js`, axios `/api` + AT/RT 인터셉터)
- `recommendationAPI`: top5 · value/growth/oversold/earnings/smartmoney-top10 · **getTrendPullbackTop10** · **getSectorStrength**
- `stockDetailAPI`: getSummary/Quick/Heavy/Diagnosis/batchScores
- `paperTradingAPI`: account·portfolio·trades·bot·real·performance
- `sectorAPI`·`marketAPI`·`investorAPI`·`quantTaAPI`·`screenerAPI`·`tradingIndicatorAPI`·`watchlistAPI`·`riskAPI`·`newsAPI`·`aiStrategyAPI`·`tradingSafetyAPI`·`shortSellingAPI`·`telegramAPI` 외 30+.
- 인터셉터: 요청 AT 주입 / 응답 401 → RT 갱신(큐로 중복 refresh 방지).

### 11-6. 유틸·컴포저블·빌드
- `auth.js`(TokenManager/UserManager) · `marketFormatters` · `toast`(싱글톤) · `nativeBridge`(Flutter WebView) · `webauthn` · `lazyObserver`.
- `useAutoRefresh`(폴링+카운트다운+pauseWhenHidden) · `useMarketStatus`(crash/ADR/VIX) · `useChartCalculations`(RSI/MA/볼린저).
- 빌드: Vite + PWA(autoUpdate, /api는 NetworkOnly), manualChunks(vendor-vue/chart/http/editor). 테스트: vitest+jsdom+@vue/test-utils (17 파일). dev proxy `/api→localhost:8080`.

---

## 12. 주식 기능 end-to-end 흐름

| 기능 | 프론트 | → Java 엔드포인트 | → 서비스 | → 소스 |
|---|---|---|---|---|
| 매수후보(오늘) | TodayBriefingTab | `/recommendation/top5` | RecommendationService | KIS+DART+재무 |
| **차트 신호 관찰(부진·관찰용)** | TodayBriefingTab | `/recommendation/trend-pullback-top10` | ChartSignalController→ChartPatternClient | **python `/api/v2/chart/timing`**(pykrx 종목 OHLCV) |
| **섹터강도(베타)** | 발굴 상단 배지 | `/recommendation/sector-strength` | ChartPatternClient | **python `/api/v2/chart/sector-strength`** |
| 발굴 5트랙 | 발굴 서브탭 | `/recommendation/{value…smartmoney}-top10` | RecommendationService | 재무/투자자/가격 |
| 종목 상세 | StockDetail | `/stock/{code}/quick·heavy·conclusion·catalyst` | StockDetail/Conclusion/Catalyst | 단일시세경로+KIS+Gemini |
| 매매 봇 | PaperTrading | `/paper-trading/bot/*`·`/real/*` | AutoTradingBot/RealTrade | KIS 실주문 |
| **간밤 미국장 tilt(베타)** | TodayBriefingTab | `/global-futures/overnight-us` | OvernightUsMarketService | Yahoo(ES/NQ/^SOX/VIX) |
| 시그널 검증 | (배치/조회) | `/signal-outcomes/accuracy-by-band` | SignalOutcomeService | signal_outcome |
| 시장국면 | (스냅샷 내부) | — | MarketRegimeClient | python `/regime/current` → **KIS 일봉(`/api/market/index/kospi-daily`)** |

---

## 13. 점수 / 시그널 산식 (CLAUDE.md §4 — 변경 시 회귀 확인)

- **종합추천**: 핵심 4카테고리(earnings/supplyDemand/technical/sectorMomentum)×20 = raw80 → normalize 0~100, **validCount≥3**(75% 커버리지).
- **임계**: STRONG_BUY≥75 / BUY 55~74 / HOLD 40~54 / <40 제외. total≥75 & valueStability≥12 → +2(정렬용, 등급변경 없음).
- **시그널 hit** = alpha_3d≥0 AND pct_change_3d>0 (3거래일), alpha 없으면 pct≥3% 폴백.
- **매매계획**(`StockConclusionService.PLAN_*`) = 봇 동기 손절-3%/익절+5%, "단기강+밸류<4" 충돌 시 -2%/+3% 타이트.
- **과열 페널티**(`overheatPenalty`, 단일출처): RSI 70/75/80→−3/−5/−8, 5일누적 15/20/30%→−3/−5/−8, 볼린저 상단 −3.
- **tie-break**(`recommendationComparator`): 점수desc → delta desc → changeRate **asc**(덜 오른 종목 우선, 추격완화).
- **BULL 가산 단일화**: `scoreSectorMomentum` +4 floor만, `applyRegimeWeights` BULL 승수 1.0(이중가산 제거).
- **신규진입 감점**: 어제 풀 밖 + 5일 급등 → technical −5, 임계 BULL 25% / 그 외 15%.
- **재료(V31)**: 산식 미편입(배지+스냅샷 검증용). **차트 타이밍/섹터강도**: 미검증, 산식·랭킹 미편입.

---

## 14. 봇 안전장치 (CLAUDE.md §4d)

1. **재시작 정합성**: REAL 모드 KIS 실잔고 vs 봇 포지션 대조 → orphaned/untracked **로그+텔레그램 경고만**(자동정정 금지, 수동매매 공유 가능). `computeReconciliation` 순수함수.
2. **매도 체결확인**: 지정가 부분/미체결 가능 → `resolveFill`로 확정 미달이면 포지션 유지(다음 사이클 재시도). 조회실패=UNKNOWN=현행 제거(안전 기본값). `confirmFill`은 `@Transactional(NOT_SUPPORTED)`(폴링이 DB 트랜잭션 미점유).
3. **KIS 주문성공 + 로컬 DB 저장 실패 = 즉시 killswitch**(KIS 비멱등, 재시도/롤백 금지). killswitch는 DB 기반(재시작 유지).
4. **멀티 인스턴스 — 봇 크론 리더 게이트(fail-CLOSED, 2026-06-29 부분 해소)**: `BotLeaderElectionService`로 리더 1개만 주문, Redis 장애 시 주문 중단. killswitch와 독립(둘 다 통과해야 주문). 잔여(P3-1): RealTradeService 멱등키/부분청산 가드 미구현.
5. **오버나잇 방어**: 정규장 마감 15:20 강제청산(`forceRegularSessionLiquidation` 기본 ON). 리더+killswitch 게이트 탑승.

---

## 15. 차트기법(신규, 2026-06-29) 배치

- **펨코 추세추종 기법을 momentum 스코어러와 분리된 별도 모듈로 통합.** 성격이 다른 두 신호:
  - **차트 타이밍**(정배열+60/240 이격도+엔벨로프 눌림목+박스, mean-reversion) → **'오늘' 탭 매수후보 아래 '검증 전 베타' 별도 섹션**(momentum 55컷 후보와 분리·**대체 아님**).
  - **섹터 상대강도**('덜 빠지는 섹터') → **'발굴' 탭 상단 상시 배지**(유니버스 필터).
- **박스 정량화 = A안(변동성 박스)**: box_len일 range_pct≤box_range_max → 돌파 → higher-low 눌림목 지지.
- **섹터지수 = 합성지수**(기존 `SectorStockConfig` 16섹터×구성원 평균, Java→python 전달). 타이밍 유니버스 = `getAllStockCodes()` ~134종목.
- **미검증**(`unverified=true`) → 봇/종합추천/매수후보 랭킹 편입 금지. 검증 = **VERIFICATION_BACKLOG P2-12**(적중률/MDD 백테스트).
- **⭐백테스트 결과(2026-06-30) = 승격불가**: deployed 16섹터/646신호 → hitRate **30.8%** · Sharpe **0.08** · 점수분해 **역상관**(score1 37.8% > score5 26.7% — 필터로 못 살림) · 현실 K=10 MDD 28.6%(순차풀베팅 99.4%는 아티팩트). alpha/reconstructed는 pykrx 깨짐으로 미산출이나 폴백조차 31%라 결론 불변. **베타 유지(`unverified` 그대로)** — 승격 보류.
- 모듈: python `app/indicators/*`(+pytest) + Java `ChartPatternClient`(best-effort)/`ChartSignalRanker`(순수+테스트)/`ChartSignalController`. 프론트: `TodayBriefingTab`(타이밍)·`StockTradingDashboardV2`(섹터배지).
- **가시성(2026-06-29)**: 차트 응답에 **`dataAvailable`** — 빈 결과가 '신호 없음'인지 'python 다운'인지 구분(프론트 "분석서버 일시 미가용" 표기). 호출 헬스는 `PythonBackendHealthTracker` + `/api/diagnostics/python-health` + 연속실패 텔레그램 알림.

---

## 16. 핵심 불변식 총정리 (건드리지 말 것)

1. **단일 시세경로** `StockPriceService.getStockPrice()` (병렬 경로 신설 금지).
2. **시간대 분리** — 표시/추천/수급/워밍 NXT 08~20 · 봇/섹터/정규장 KRX 09~15:30(40).
3. **가격 이상치 = 로깅만, 미보정**(`warnIfPriceOutlier`).
4. **점수/시그널 산식 기준값** (§13) — 임계·단계 합치거나 되돌리지 말 것.
5. **차트 기법 = 발굴/매수후보 스코어러 항상 분리**, momentum에 욱여넣지 말 것.
6. **결측은 정직하게 null/생략**(체결강도·condition·섹터거래대금·차트신호 — 가짜값 금지).
7. **인프라**: 스케줄러 락 fail-open(봇 제외), 캐시 L1→L2→L3(시세만 Redis 비경유), cron 시각 튜닝값.
8. **인증**: 필터는 AccessToken만, 라우터가드 RT 보존, authAPI는 apiClient 경유.
9. **프론트 IA**: 단일 허브 4탭, 새 주식 라우트 금지(탭/서브탭 흡수), 오늘=모멘텀/발굴=다각도 선별.

---

## 17. 테스트 / CI 인프라

- **백엔드**: `./gradlew test`. 단위(Mockito) 다수 + **`ApplicationContextSmokeTest`(`@SpringBootTest`, 2026-06-29 신규)** — 이 프로젝트 **첫 @SpringBootTest**. H2 인메모리 + Flyway off + 더미 시크릿(`application-test.yml`)으로 외부 인프라 없이 전체 컨텍스트를 eager 로드 → **"앱 부팅 깨짐"(빈 와이어링/설정 오류)을 CI 에서 잡는다.** (그간 맹점 — 단위테스트만으론 "기동 실패"를 못 잡음.)
  - 순수함수 + 테스트 패턴: `recommendationComparator`·`computeOversoldScoreParts`·`resolveFill`·`computeReconciliation`·`BotLeaderElectionService.decideLeadership`·`shouldForceLiquidate`·`verdictFor`·`PythonBackendHealthTracker`·**`parseIndexDaily`(KIS 지수)**·**`classifyOvernight`(간밤 미국장)** 등.
  - ⚠ **다중 생성자 빈은 운영 생성자에 `@Autowired` 필수**(테스트용 생성자 추가 시) — 누락하면 컨텍스트 기동 불가. 스모크 테스트가 이 회귀를 가드.
- **프론트**: `cd frontend && npm test`(vitest) + `npm run build`.
- **python-backend**: `cd python-backend && pytest`(지표 + **백테스트(test_backtest 27건) + 지수소스 어댑터(test_index_source)**). **로컬엔 Python 인터프리터 없음(Store 스텁) → Docker 내 실행**(`docker compose run --rm python-backend pytest`).
- **CI/배포**(`.github/workflows/deploy.yml`): gradle 빌드 → 도커 이미지 → SSH 배포 → **post-deploy 헬스체크**(`curl /api/health` 80초 폴링, 실패 시 `docker compose logs backend` artifact). status=000 = 컨테이너 미기동(앱 500 아님). ⚠ 호스트 OOM/SSH 다운은 인프라 영역(컨테이너 死 ≠ 호스트 死) — docker-compose 메모리 합산 ~3.5GB, swap/RAM 여유 점검 권장.

---

## 18. 2026-06-29 세션 변경 요약 (봇 안전 6작업 + 스모크)

각 항목 독립 커밋. 불변식(시세경로·산식·차트분리·SchedulerLockService fail-open) 무변경.

1. **봇 리더 가드 fail-CLOSED** — `BotLeaderElectionService`(Redis 리스+하트비트). 봇 크론 6개 게이트. 멀티 인스턴스 중복 주문 차단. (P3-1 부분 해소)
2. **정규장 15:20 강제청산** — `BotConfig.forceRegularSessionLiquidation`(기본 ON)+Flyway V33, `executeRegularSessionLiquidation`. NXT 청산은 P2-13 후속.
3. **python 가시성** — `PythonBackendHealthTracker`+`/api/diagnostics/python-health`+텔레그램, 차트 응답 `dataAvailable`.
4. **19:30 평가 멱등성** — 확인됨(pending 행 UPDATE, 중복 INSERT 없음). 코드변경 없음. record() DB unique 제약은 P3-2.
5. **tie-break ↔ 차트 타이밍 충돌** — `recommendationComparator` 경고주석(승격 시 이중작용 점검, P2-12 #3).
6. **growth/valueStability -1=NA 가드** — `verdictFor` `score<0→N/A`(NEGATIVE 오표시 버그 수정)+NA factor 숨김+경고주석. nullable 전환은 P3-3.
7. **(후속) `@SpringBootTest` 스모크 + 이중생성자 DI 버그 수정** — 스모크가 작업1·3의 `@Autowired` 누락(컨텍스트 기동 불가)을 즉시 검출 → `ChartPatternClient`/`BotLeaderElectionService` 운영 생성자에 `@Autowired` 추가.

신규 백로그: P2-13(NXT 청산)·P3-2(signal_outcome unique)·P3-3(growth nullable).

---

## 19. 2026-06-30 세션 변경 요약 (차트 백테스트 + P0-pykrx + V36 + 간밤 미국장)

각 항목 독립 커밋. 불변식(단일 시세경로·점수산식·차트분리·regime 규칙 v1·SchedulerLockService fail-open·봇 리더 fail-CLOSED) 무변경. "검증 안 된 신호는 표시 전용·산식 미편입" 원칙 유지.

### 작업0 — python pytest Docker 검증
- Dockerfile `COPY tests/`+`pytest.ini` 누락 수정 → 차트 지표 18 green. (로컬 Python 없어 Docker 내 실행 확정.)

### 작업1 — 차트 타이밍 백테스트(P2-12) → **승격불가 결론**
- 신규 모듈 `app/backtest/*`(cost·metrics·chart_backtest_service) + `routers/chart_backtest.py`(`POST /api/v2/chart/backtest`, 온디맨드). `compute_timing` 추출해 **프로덕션과 동일 신호 재생**(단일 출처).
- 3대 함정 방어: **look-ahead**(`df.loc[:D]` ≤D / 평가 `df.loc[>D]` disjoint + assert) · **진입 D+1 시가/청산 +3거래일 종가** · **비용**(수수료0.03%+세금0.18% + 슬리피지0.15% 가격적용) · **생존편향**(deployed + reconstructed). hit=SignalOutcome 미러.
- **결과**: hitRate 30.8% · avgNet +0.53% · Sharpe 0.08 · winRate 51% · profitFactor 1.25, **점수분해 역상관**(고점수일수록 적중률↓), 현실 K=10 MDD 28.6%(순차풀베팅 99.4%=아티팩트 폐기). → **베타 유지, 매수후보 미승격**(P2-12 문서화).
- **UI 후속(화면 정합)**: '오늘' 탭 섹션을 **'차트 타이밍 매수 후보' → '🪝 차트 신호 관찰'**로 중립화 — timingScore(N/10) **미표시**(수익과 역상관이라 오해 방지), 배너에 **실측("적중률 31%·점수–수익 무관·매수 신호 아님")**, **접기 기본**(우선순위↓). CLAUDE.md 불변식 문구도 '검증 전 베타'→'검증완료·부진·관찰용'으로 갱신(되돌림/승격 금지 명시).

### P0-pykrx — pykrx 지수·ticker_list 깨짐 → **KIS 일봉 전환**(운영 regime 복구)
- **진단**: pykrx 1.0.45 `get_index_ohlcv('1001')`·`get_market_ticker_list()` **날짜무관 전구간 0건**(KRX 포맷변경). 종목 OHLCV는 정상. 표면증상 `KeyError:'지수명'`은 shim으로 막아도 빈값 → fetch 자체 문제.
- **영향**: regime(V32 `regime_at_signal` NULL 누적 — backfill 불가) · sector_strength 배지 · 백테스트 alpha. **라이브 추천 점수는 무영향**(RecommendationService 자체 sector-momentum regime). **종목마스터 무영향**(KRX KIND HTML).
- **수정(Option B)**: Java `KoreaInvestmentService.getIndexDailyOhlcv(0001, days)`(TR FHPUP02120000, 진짜 종합지수) + `MarketIndexController GET /api/market/index/kospi-daily`(permitAll). python `regime_service`·`sector_strength_service`가 `app/utils/index_source.fetch_kospi_daily`(Java 호출, pykrx 동형 DataFrame)로 소비. classify_regime·국면 v1·테스트 불변(§10·§4c 보존).
- **트랩 교정**: ① 컴파일 — 주석에 `*/`(필드명 `bstp_nmix_*/...`)가 javadoc 닫아 빌드깨짐(인코딩 오인했으나 실원인 `*/`). ② SecurityConfig permitAll에 `/api/market/index/**` 추가. ③ KIS 지수 TR 날짜앵커 — `DATE_1`=기준일로 직전 100건 반환(종목 TR과 반대)이라 `DATE_1=start`면 4개월 전에서 끝남 → **`DATE_1=end(오늘)`로 스왑**. **운영검증 OK**(regime asOf=당일·실값·BULL, 캐시 클리어 후).
- **잔여**: ticker_list(reconstructed 백테스트 전용) = **P3-4**.

### 작업2 — signal_outcome UNIQUE (V36, P3-2 해소)
- **V36 마이그레이션**: 자기조인 DELETE(최소 id 보존, 원자적) → `ADD CONSTRAINT uq_so_type_code_date UNIQUE(signal_type,stock_code,signal_date)`. 엔티티 `@UniqueConstraint`(idx_so_type_date는 컬럼순서 달라 중복 아님→유지).
- `record()` INSERT를 `insertOutcomeIsolated`(`@Transactional REQUIRES_NEW`, selfProvider 프록시)로 격리 + `DataIntegrityViolationException` benign → 경합 패자가 호출부 tx 무오염. 사전 감사 SELECT/사후 `SHOW CREATE TABLE` 권장.

### 작업3 — 간밤 미국장 국면 보조 tilt (미검증, '오늘' 탭)
- `GlobalFuturesService`에 **^SOX** 추가(Yahoo, marketState=None 가능 → 등락률+VIX만 사용). `OvernightUsMarketService.classifyOvernight(es,nq,sox,vix)` 순수(임계 임시값: 3지수 평균 ±0.6% / VIX 20·25·30 / SOX −2%) + `GET /api/global-futures/overnight-us`. 프론트 `TodayBriefingTab` "🌙 간밤 미국장" 독립 줄(regime과 분리).
- **불변식**: tilt는 python regime/추천 산식 **입력 미편입**(별개 표시), `unverified=true`(봇/추천 미편입). 캘리브레이션 = **P3-5**.

신규/해소 백로그: **P0-pykrx**(해소, 잔여 P3-4) · **P3-4**(ticker_list reconstructed) · **P3-5**(간밤 미국장 tilt 캘리브레이션) · **P3-2 해소**(V36) · **P2-12**(차트 백테스트 = 승격불가 기록).

---

### 세션 후반 — 4카테고리 진단(P1-6) + 종합 판단 보드(B안, P2-14)

차트타이밍 31% 교훈("검증 안 된 지표 합산 = 좋은 신호 망침")을 **종합점수 자체 4카테고리에도 적용** — prod `signal_outcome`(n=88) 실측:
- **기술 ✅ 예측력**(강세 57%>약세 43%, +13.9%p) / **섹터(AI테마 14점) ✅ 강예측**(65%·+6.86%, `≥15` 측정임계가 sweet spot 놓침 → `≥14` 재설정 필요) / **실적 = 게이트+20점 약변별** / **수급 ❌ 역상관 단조 확정**(0-4=67%→15+=35%, 평균수익 7.61→0.38).
- 측정버그: `aggregateCategories` 단일 임계 15는 카테고리별 분포(실적8~20·섹터0~14) 무시 → 카테고리별 임계 분리 필요. **당장 가중치 변경 보류**(표본 작음, regime 분리 불가) → 데이터 축적 후 재측정([P1-6]).

**B안 종합 판단 보드 Phase 1**(그 교훈을 구조로 — 검증된 것만 점수, 미검증은 표시만):
- `GET /api/recommendation/judgment-board` + `JudgmentBoardService`(순수 `assembleRows`/`parseSectorRel`+테스트) + `JudgmentBoardDto`. 프론트 `SectionJudgmentBoard.vue`(발굴 심화 '🧭 종합판단').
- 컬럼 3계층: **① 점수(검증/게이트** total/기술/실적/섹터테마) · **② 참고(미검증·점수 미편입** 차트타이밍/섹터강도/간밤미국장) · **③ 경고(수급 역상관 의심** ≥10, 표본작음 톤). 정렬·필터. **종합점수 산식 무변경(조립·표시 전용)**.
- Phase1 = momentum 후보(getTop5)만 — 단 `getTop5`가 **validCount≥3(75% 커버리지) + 정규화≥55** 이중 게이트라 풀이 얇음(카테고리 sparse). **Phase 2(발굴 5트랙 union, 비-momentum 4카테고리 재점수)** = 비교 대상 확보용 필수 → 다음 세션 실데이터 보고 범위 결정([P2-14]).

---

## 20. 관련 문서 인덱스

- `CLAUDE.md` — 작업 지침 + 불변식(1차 출처)
- `VERIFICATION_BACKLOG.md` — 검증/개선 티켓: P2-12 차트 백테스트(**승격불가 기록**)·P2-13 NXT청산·P3-1 멀티인스턴스 락(부분해소)·**P3-2 signal unique(V36 해소)**·P3-3 growth nullable·**P0-pykrx(KIS 지수전환 해소)**·**P3-4 ticker_list reconstructed**·**P3-5 간밤 미국장 tilt 캘리브레이션**·**P1-6 4카테고리 적중률 캘리브레이션(★수급 역상관 확정)**·**P2-14 종합 판단 보드(B안, Phase1 완료)**
- `MARKET_INDICATORS_API.md` — 지표 API 레퍼런스
- `docs/STOCK_PLATFORM_GUIDE.md` — 화면→코드→DB 추적
- `docs/STOCK_AZ_FULL.md` — 2026-06-08판(stale, 본 문서가 대체)
- `docs/SYSTEM_OVERVIEW.md` · `docs/STOCK_PLATFORM_ONEPAGER.md` — 요약

> 본 문서는 2026-06-29 생성 · **2026-06-30 갱신**(§19 세션 반영). 정밀 cron/개수/필드는 코드가 출처이며, 산식·불변식은 CLAUDE.md를 따른다.
