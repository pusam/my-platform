# 주식 플랫폼 A–Z 전수 배치도 (2026-06-29 생성 · **2026-07-02 갱신**)

> **생성**: 2026-06-29, 코드 직접 전수(Explore 3-레이어 매핑) 기준. **최종 갱신**: 2026-06-30(차트 백테스트·P0-pykrx KIS 지수전환·V36 unique·간밤 미국장 tilt 반영 — §20 세션 요약).
> **위치**: `docs/STOCK_AZ_FULL.md` — 주식 플랫폼 **유일 정본**. 구 문서(2026-06-08 GNB 3탭판 AZ_FULL·GUIDE·ONEPAGER·SYSTEM_OVERVIEW, 03-09 STALE DOCUMENTATION)는 2026-07-06 정리하며 이 문서로 통합·삭제.
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
  - `GET /judgment-board?scope=momentum|union` ⭐신규(2026-06-30 B안, 2026-07-01 union Phase2-A) 종합 판단 보드(매수후보 3계층 신호 비교; union=발굴 5트랙 합집합, 4-cat은 scoreMap lookup·없으면 "—"; 산식 무변경 조립)
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
  sector_strength_service.py   섹터 동일가중 합성지수 vs KOSPI 상대강도. ⭐2026-06-30: _market_return fetch_kospi_daily 경유.
                               ⭐2026-07-01: 멤버 OHLCV fetch 순차→ThreadPool 8워커 병렬+dedup(_fetch_returns_parallel,
                               t134 7.8→1.2s, 8s 타임아웃 해소). 산식 불변. Java 워밍=MarketCacheWarmerService.warmSectorStrength.
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
| **오늘(today)** | `TodayBriefingTab.vue` | 시장 한줄 · **🌙 간밤 미국장 tilt(2026-06-30)** · 매수후보(55컷 momentum) · **시간대신호(장전/장후)·실시간수급(장중)**(슬롯 #phase-signals, 발굴서 이동 2026-07-01) · **🪝 차트 타이밍 관찰(python timing, 접기)** · 신뢰도 · **관심종목(슬롯 #watchlist, 접힘)** · 내 포지션 · 도구 |
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
| **차트 타이밍 관찰(python, 부진·관찰용)** | TodayBriefingTab | `/recommendation/trend-pullback-top10` | ChartSignalController→ChartPatternClient | **python `/api/v2/chart/timing`**. ⚠ 발굴 '📐 차트 패턴'(Java ChartPatternService, 기하학 패턴)은 **별개 엔진**(P2-15) |
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
10. **외부 HTTP URL 인코딩 — 이미 인코딩된 URL 은 `URI` 로 넘긴다(String 금지).** `RestTemplate.exchange/postForEntity`·`WebClient` 는 **String URL 을 URI 템플릿으로 보고 재인코딩**(% → %25)해 **이중 인코딩**한다. `UriComponentsBuilder...encode(UTF_8).build().toUri()`(URI 반환) → 인자로 URI. (2026-07-01 네이버 검색이 이 함정으로 한글 이중 인코딩 → 무관 뉴스 → 재료 100% NONE. **다른 외부 API 연동 시 동일 함정 주의**.)
11. **결측을 그럴듯한 값으로 위장 금지(§4c)의 "조용한 죽음" 재확인** — 소스 다운 시 "빈 결과=정상 없음"으로 **캐시**하면 장애가 은폐된다(재료 7일 NONE). 소스 미가용(`isAvailable()==false`)이면 **캐시 말고 null 반환** → 복구 시 자동 재시도.

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
6. **growth/valueStability -1=NA 가드** — `verdictFor` `score<0→N/A`(NEGATIVE 오표시 버그 수정)+NA factor 숨김+경고주석. nullable 전환은 P3-3. **(2026-07-02) 종합판단 보드도 4카테고리(실적/기술/섹터/수급) 0점→-1(NA)을 '—'로 렌더** — 특히 **수급 -1 은 "순매도"가 아니라 순매수 신호 미포착(0점)**(`scoreSupplyDemand`는 가점만·감점 없음), toDto 가 0→NA(-1) 변환. 음수 점수 오해 방지(표시 전용, 산식 무관).
7. **(후속) `@SpringBootTest` 스모크 + 이중생성자 DI 버그 수정** — 스모크가 작업1·3의 `@Autowired` 누락(컨텍스트 기동 불가)을 즉시 검출 → `ChartPatternClient`/`BotLeaderElectionService` 운영 생성자에 `@Autowired` 추가.

신규 백로그: P2-13(NXT 청산)·P3-2(signal_outcome unique)·P3-3(growth nullable).

---

## 19. 2026-06-30 ~ 07-02 세션 변경 요약 (차트 백테스트 + P0-pykrx + V36 + 종합판단 보드 + 재료 파이프라인 + Gemini rate + 발굴 축소)

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

### 2026-07-01 세션 — 종합판단 Phase 2-A + 발굴 UI 정리 + 섹터강도 perf

- **발굴 UI 정리(진단 기반)**: ① **2단 상단 네비 통합**(목록/심화 두 서브탭 바 상단에, `discoverGroup`로 콘텐츠 단일화, 심화 바 버림 해소, 기본=🧭종합판단 보드). ② **목록 슬림화(A)** — 시간대신호·실시간수급·관심종목을 **오늘 탭으로 이동**(Vue 슬롯 `#phase-signals`/`#watchlist`, hub scope라 데이터·CSS 유지), 관심종목·차트신호 종목 **기본 접힘**. 발굴 목록 적층 1,630→~880px. (2단계 중복 통합=P2-15.) **⚠ 이 2단 네비/목록 5트랙은 2026-07-02 "종합판단 중심 축소"로 프론트 숨김됨 — 아래 07-02 블록 참조(코드 보존).**
- **종합판단 보드 Phase 2-A(P2-14)**: 발굴 5트랙 union — **재점수 없이 momentum `scoreMap` lookup**(`RecommendationService.categoryScoreSnapshot()`). `getBoard(scope=union)` 수집+dedup+출처태그 병합+lookup(없으면 `scored=false`="—"). `GET /judgment-board?scope=union`. 프론트 "발굴 트랙 포함" 토글(lazy)+출처칩+"—" muted+union 통계. **⚠ scoreMap universe=AI/실적/수급 seed**라 순수 저평가/성장주는 "—"(정직). 필터 "기술 강" 임계 **≥13**(실데이터 max 14).
- **섹터강도 perf(P2-16, at-risk 해소)**: t134≈7.8s(순차 134 fetch, Java 8s 타임아웃 헤드룸~0) → **(2) python `_compute` ThreadPool 8워커 병렬+dedup**(산식 불변, t134→~1.2s) + **(1) Java 워밍**(`MarketCacheWarmerService.warmSectorStrength` 20분, `ChartPatternClient.getSectorStrength(forceRefresh)`). unverified·§4c 무변경.
- **차트신호 "중복" 재검토(P2-15, 종결)**: 코드 매핑 결과 **3 surface = 3 독립 엔진(중복 아님)** — ① 발굴 **'📐 차트 패턴'**(Java ChartPatternService, 기하학) · ②③ **'🪝 차트 타이밍'**(오늘+종합판단, python compute_timing) · 🎯종합(composite 5/5 랭킹, 종합판단과 다른 엔진). **삭제·은퇴 없이 네이밍만 구분**('패턴'=Java/'타이밍'=python). 검증된 고유 기능 보존. 🎯종합=상위호환 아님(어제 오판 정정). **⚠ 🎯종합은 2026-07-02 발굴 축소로 프론트 nav 에서 숨김(코드·엔진 보존, 은퇴 아님) — 아래 07-02 블록.**

---

### 2026-07-01 오후 ~ 07-02 세션 — 재료 파이프라인 3중 장애(교훈) + Gemini 무료 rate + 보드 3축/축소

**재료(catalyst) 7일 연속 100% NONE — 3중 장애가 겹쳐 있었다. 한 층 고치면 다음 층이 드러남.** "뭐 했다"보다 재발 방지 관점:

1. **"조용한 죽음" 패턴 (§4c·§16-11)** — 뉴스 소스(네이버) 다운 시 "뉴스 0건"을 **NONE(재료 없음)으로 일캐시**하면 7일간 은폐된다(에러 안 뜸, 배지만 빔, 스케줄은 정상 도니 더 안 보임). **구조적 방지 = §4c 하드닝**: `naver.isAvailable()==false` → `getCatalyst` **null 반환(캐시 안 함)** → 소스 복구 시 즉시 재분류. 결측을 그럴듯한 값(NONE)으로 위장 금지의 재확인. 재현 테스트 `StockCatalystServiceTest`.
2. **인코딩 2층 함정 (§16-10)** — `NaverSearchService.buildSearchUrl` 을 단일 인코딩으로 고쳐도 **`RestTemplate.exchange(String)` 이 URL 을 URI 템플릿으로 보고 재인코딩**(% → %25) → 한글 이중 인코딩 → 네이버가 무관 뉴스 반환 → 종목명 필터 전부 탈락 → NONE. **해결: `buildSearchUrl` 이 `URI` 반환**(String 금지, RestTemplate 이 URI 는 재인코딩 안 함). ⚠ **딴 외부 API 연동도 같은 함정** → 불변식화(§16-10). 회귀 테스트(query=%EC…, %25 없음). 진단: 컨테이너 curl 로 단일 vs 이중 인코딩 응답 비교.
3. **env_file 단일 의존 위험** — backend 가 `NAVER_CLIENT_ID/SECRET`(및 GEMINI)을 `env_file:.env` 로만 받으면, env_file 주입은 **컨테이너 '생성' 시점 고정** → `.env` 에 키 추가 후 `restart` 만 하면 반영 안 됨(=키 미주입, isAvailable=false). **표준: compose `environment:` 에 `${..}` 로 명시 이중배선**(매 `up` 재평가). 진단은 **로그 → DB → 컨테이너 printenv/curl** 로 층층이(배선→인코딩→§4c).

**Gemini 무료 티어 quota — 병목은 RPM(요청 수), 캐싱 아닌 배치가 정답.** 인코딩 수정 후 뉴스는 들어오나 429 로 분류 저장 실패(재료·AI전략 동시 저하). 완화(무료 유지):
- 모델 **`gemini-2.0-flash`(종료) → `gemini-2.5-flash-lite`**(무료 ≈15 RPM, `${GEMINI_API_URL}` 오버라이드).
- **전역 rate 직렬화** `GeminiService.RateLimiter`(synchronized `acquire()` + 슬롯 예약, 간격 4.5초 ≤~13 RPM) — 이전 `enforceRateLimit` 은 volatile check-then-act(비원자적)라 동시 호출자(재료·AI전략·StockDetail) 버스트가 통과 → 429. **503(구글 과부하)은 재시도 자동복구**, 429는 rate 제한 방어 → 재료+AI전략 둘 다 정상.
- ⚠ **프롬프트 프리픽스 캐싱은 토큰 비용만↓(RPM 무관)** — 무료 병목엔 무효. RPM 실질 감축 = **배치 프롬프트(N종목 1콜)** 가 정답(후속 P2-CAT1). 우선순위(재료>AI전략)=P2-CAT2, 보드 워밍=P2-CAT3(rate 게이트 경유).

**종합판단 보드 3축(매매 맥락, 표시 전용·산식/시세경로 무관)**: ① 재료 배지(호재🔥/악재⚠️/중립 회색·아이콘無 — 시세 초록/빨강과 색 구분), ② 현재가/등락률(캐시 as-of ≤30분·**라이브 아님** 경고), ③ 거래대금(§4c 실측→현재가×거래량 폴백→null). 보드는 재료 캐시 **read-only**(classify 금지). 4카테고리 -1(NA)→'—' 렌더(§13/435). "발굴 트랙 포함" 토글 상태 localStorage 유지, 차트타이밍 상시 안내(참고·역상관·순위 아님).

**발굴 탭 종합판단 중심 축소** — **왜**: 종합판단 union 이 5트랙(저평가/성장/낙폭/실적/수급)을 이미 흡수(한 보드 비교) → 목록 5트랙 중복. **어떻게**: **삭제가 아니라 프론트 nav 숨김** — `discoverListVisible=false`(목록 5트랙) + `discoverSubTabs` 축소(🎯종합·AI전략·스크리너·퀀트TA 제거), `resolveInitialDiscoverGroup` 항상 'deep'. **렌더 블록/임포트/로더/딥링크(`?sub=`) 백엔드·컴포넌트 전부 보존**(복구: 플래그+resolver+subtabs 되살리기 한 줄들). 발굴 노출 = **🧭종합판단 + 백테스트뿐**. 빈-보드 폴백도 숨긴 '목록 탭'→'발굴 트랙 포함' 토글로 유도.

백로그 신규: **P2-CAT1**(재료 배치 프롬프트 N종목 1콜=RPM 실질↓)·**P2-CAT2**(Gemini 소비자 우선순위 재료>AI전략)·**P2-CAT3**(보드 종목 일괄 워밍, rate 게이트). Gemini quota=**해소**(무료 유지, 유료 안 감).

---

### 2026-07-02 세션 — 재료 배치·보드 워밍 3종(P2-CAT1/CAT3) + P1-6 측정 버그

무료 티어 quota 안정(전날) 위에 재료 커버리지를 넓히고, 재측정 전제를 고침. 전부 표시/측정·quota 관점, 라이브 산식 무변경.

- **재료 배치 분류(P2-CAT1, `cf29fc3`)**: `StockCatalystService.classifyBatch` — N종목 뉴스를 1 프롬프트(code 키 JSON 배열)로 묶어 **Gemini 1콜/배치**(RPM 실질 1/N, 무료 병목의 정답=캐싱 아닌 호출수 감축). 5씩 청킹·캐시히트 스킵. **개별 폴백**(유효 원소만 저장, 실패=미캐시 재시도, N개 단건 폴백 안 함). 단건 getCatalyst=on-demand 유지, 워밍만 배치 전환. 로그 실제 콜 수 정직화(`9b9d1d4`).
- **보드 union 일괄 워밍(P2-CAT3, `710ddd9`)**: `CatalystWarmingService` — 발굴 5트랙 상위 25종목 재료를 배치로 미리 분류(보드 "—" 채움). `@Scheduled` 08:00 주중 + 수동 트리거 `POST /api/admin/catalyst/warm-union`(ADMIN). classifyBatch 위라 25종목≈5콜(quota 안전). **라운드로빈 인터리브(`b5f2aad`)**: value-first 순차면 급등주(낙폭·수급)가 25칸 밖으로 컷 → round r=각 트랙 r번째로 5트랙 top5 균등+소진 롤오버(척도 상이라 통합정렬 안 함).
- **보드 재료 최근 2일 표시(`4ffdf00`)**: 보드가 오늘자만 읽어 워밍 대상 밖 종목이 매일 "—"로 깜빡 → **최근 2일 중 최신** 표시 + `catalystAgeDays`("어제") 경과 표기(§4c 낡음 위장 방지, 2일↑ 제외). §4b Gemini 일캐시 분류는 불변, **표시 날짜창만** 확장. 선정 다양화(오늘자 확보)+최근2일(백업) 두 겹 방어.
- **P1-6 측정 버그 해소(`c85f304`)**: `SignalOutcomeService.aggregateCategories` 단일 임계 15 → **카테고리별**(n=88 실측 근거: 실적≥20·수급≥15·기술≥13·섹터≥14). 섹터(max14→≥15 강세 0건)·실적(19/20 뭉침) 오측정 해소 → **2-4주 뒤 국면별 재측정의 전제 충족**(측정 전용, 라이브 산식 무관). 가중치 변경은 데이터 대기.

---

### 2026-07-06 세션 — ×10 가격 버그 진단 종결 + 봇 진입 sanity 가드

**진단(Phase 1, `PRICE_X10_DIAGNOSIS_P0-2.md` §8)**: 3-트랙 경로 전수 재매핑 — 파싱 전 경로(REST `getBigDecimalValue`/네이버 `parsePrice`/WS `parseDecimalSafe`)에 ×10 산술 **0건**, WS 틱은 in-memory만(DB 미저장), `RealTimeDataCache`는 orphan. **핵심 구조: 현재가=UN(통합) vs 일봉 히스토리=J(KRX 단독) 소스 분리** — 재발 시 UN 응답 규약 문제로 즉시 좁혀지는 판별 구조. 운영 실측 최신 증거는 §6(06-04, 90일 0건) — **재현 불가·원인 미확정으로 §4c대로 파싱 수정 없음**(위장 금지), 재스캔 SQL/grep은 §8(b)에 준비(운영 실행 대기).

**방어선(Phase 3)**: `util/PriceSanityGuard.judge()`(순수함수+`PriceSanityGuardTest` 13케이스) + `AutoTradingBotService.passesPriceSanity()` — 스캘핑·스윙 진입 직전, **전일 종가 대비 ±50% 초과 시 해당 종목 진입 차단 + 리스크 채널 알림(종목별 10분 스로틀)**. 앵커=`StockPriceHistory` 최신 종가(**J 소스라 UN 통배수 오염과 독립** — KIS 역산 prdy_vrss는 같이 스케일돼 무력이라 금지). 앵커 결측/0/4일 초과=UNKNOWN=통과(§4c: 결측 근거 차단 금지). §16-3 비충돌(가격 미보정, 주문만 차단). VIRTUAL/REAL 공통.

---

## 20. 관련 문서 인덱스

- `CLAUDE.md` — 작업 지침 + 불변식(1차 출처)
- `VERIFICATION_BACKLOG.md` — 검증/개선 티켓: P2-12 차트 백테스트(**승격불가 기록**)·P2-13 NXT청산·P3-1 멀티인스턴스 락(부분해소)·**P3-2 signal unique(V36 해소)**·P3-3 growth nullable·**P0-pykrx(KIS 지수전환 해소)**·**P3-4 ticker_list reconstructed**·**P3-5 간밤 미국장 tilt 캘리브레이션**·**P1-6 4카테고리 적중률 캘리브레이션(★수급 역상관 확정)**·**P2-14 종합 판단 보드(B안, Phase1+2-A 완료)**·**P2-15 차트신호/종합 중복 통합(2단계)**·**P2-16 섹터강도 perf(병렬+워밍, 해소)**·**P2-CAT1 재료 배치 프롬프트(N종목 1콜=RPM↓)**·**P2-CAT2 Gemini 소비자 우선순위(재료>AI전략)**·**P2-CAT3 보드 재료 일괄 워밍(rate 게이트)**
- `MARKET_INDICATORS_API.md` — 지표 API 레퍼런스
- (2026-07-06 정리) 구 주식 문서 5종(STOCK_PLATFORM_GUIDE·구 STOCK_AZ_FULL·SYSTEM_OVERVIEW·STOCK_PLATFORM_ONEPAGER·STOCK_SYSTEM_DOCUMENTATION)은 본 문서로 통합·삭제. 이제 주식 정본은 본 문서 단일.

> 본 문서는 2026-06-29 생성 · **2026-07-02 갱신**(§19 = 06-30~07-02 세션 반영: 종합판단 보드·재료 파이프라인 3중 버그·Gemini 무료 rate·발굴 축소). 정밀 cron/개수/필드는 코드가 출처이며, 산식·불변식은 CLAUDE.md를 따른다.
