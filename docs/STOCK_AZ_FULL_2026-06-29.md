# 주식 플랫폼 A–Z 전수 배치도 (2026-06-29)

> **생성**: 2026-06-29, 코드 직접 전수(Explore 3-레이어 매핑) 기준.
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
   [python-backend:8000]  pykrx (regime + chart)
        └──[redis] (py: 프리픽스)
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
| `SignalOutcomeService` | 시그널 적중률(19:30 배치, 3거래일 후, V30~V32 스냅샷), `getAccuracyByBand` |

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
| `AutoTradingBotService` | 모드(REAL/VIRTUAL), 5 @Scheduled 크론, 포지션추적, 재시작정합성 `reconcilePositionsWithKis`/`computeReconciliation`(경고만), killswitch |
| `RealTradeService` | KIS 실주문, 체결확인 `confirmFill`/`resolveFill`(미체결→포지션유지), KIS성공+DB실패→`triggerKillSwitchOnUncertainty` |
| `VirtualTradeService` / `BotPerformanceService` | 모의계좌 / 성과(MDD/Sharpe) |
| `PositionDropMonitorService` | 포지션 낙폭 감시(2분) |

### 4-4. 시장·섹터·수급
`SectorTradingService`(거래대금 실측만, `resolveAccumulatedValue` 폴백, 가짜값 금지) · `MarketTimingService`(ADR 20일, condition은 ADR만) · `InvestorTradeService`/`InvestorSurgeService`.

### 4-5. 외부연동 서비스
`KoreaInvestmentService`(KIS REST·OAuth·rate limiter·@Retry/@CircuitBreaker) · `KisWebSocketService`(실시간 틱) · `KisInvestorDataCollector` · `DartService` · `GeminiService` · `NaverSearchService`/`NewsService` · `TelegramNotificationService`(3채널) · **`MarketRegimeClient`/`ChartPatternClient`**(python 소비, best-effort).

### 4-6. 캐시·스케줄·기타
`CacheWarmupService`/`MarketCacheWarmerService`(워밍) · `RealTimeDataCache`(1분봉, synchronized 보호) · `SchedulerLockService`(fail-open) · `MorningBriefingService`(07:30) · `BacktestService` · `AiStrategySnapshotService` · `QuantScreenerService`/`QuantTaService` · `ChartPatternService`(자바 차트패턴 검출, python `ChartPatternClient`와 별개).

---

## 5. 스케줄러 / 일과 타임라인

스레드풀(`SchedulingConfig`): `taskScheduler`(매매·기본) · `cacheScheduler`(워밍) · `batchScheduler`(크롤·리포트), 각 16스레드. 락: `SchedulerLockService` fail-open(Redis SET NX EX, TTL<cron). **봇 크론은 락 미사용**(JVM 내 가드만, 단일 인스턴스 전제).

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
| 14:00 / 15:10 | 스윙 봇 중간점검 / 사이클 | `AutoTradingBotService` |
| 15:35 | 섹터 마감 정산 | `SectorTradingService` |
| 15:50 / 18:00 | KIS 투자자 데이터 수집/정산 | `InvestorTradeScheduler` |
| 16:30 | ADR 수집·국면 갱신 / DART 마감 | MarketTiming/Earnings |
| 16:45 | 마감 후 알림 | `StockAlertScheduler` |
| 19:30 | 시그널 평가(3거래일 후) | `SignalOutcomeService` |
| 20:05 / 20:10 | 발굴 5트랙 야간 / 복합신호 | Recommendation/MultiConviction |
| 23:00 | 재무 영속화 | `StockFinancialDataService` |
| 03:00 | 배치 정리 | `BatchJobCleanupService` |

> ⚠ 위 cron은 매핑 근사. **정확값은 각 서비스 `@Scheduled`가 출처.** 미적용(주석) 2건: 종가청산(2026-09 연장장 대비 재설계 필요), 저녁 sweep.

---

## 6. 엔티티 / 리포지토리 (도메인별 핵심)

- **종목/시세**: `StockMaster` · `StockPrice` · `StockPriceHistory` · `StockFinancialData` · `StockCatalyst`(V31)
- **추천/분석**: `RecommendationSnapshot`(점수·카테고리세부, growth -1=NA sentinel) · `AiStrategySnapshot` · `MarketIndicatorSnapshot`
- **매매/포지션**: `BotTradingPosition` · `BotConfig`(손절/익절%) · `VirtualAccount`/`VirtualPortfolio`/`VirtualTradeHistory` · `TradingKillSwitch` · `TradingAuditLog`
- **시그널/성과**: `SignalOutcome`(3일후 return + V30~V32 스냅샷, NULL=미수집) · `WeeklyTradingReport`
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
  regime_service.py            KOSPI(1001) 종가 vs MA60 + MA20 5일슬로프 → BULL/BEAR/SIDEWAYS, 1h 캐시
  chart_pattern_service.py     analyze_timing: 벌크 OHLCV(500cal일) → 6지표 결합 → 0~10, 30m 캐시
  sector_strength_service.py   섹터 동일가중 합성지수 vs KOSPI 상대강도
  cache_service.py             Redis(py: 프리픽스, best-effort)
app/indicators/  (순수함수 + pytest)
  moving_average(정배열) · disparity(60/240 이격도) · envelope(하단터치+위험필터)
  · support_rebound(중심선 반등) · box_breakout(A안 변동성박스) · sector_strength · timing_score
app/config.py          Settings(Redis) + ChartPatternConfig(모든 임계값 파라미터화 + merge override)
app/utils/korean_market.py     KST·장중판정·최근거래일·TTL
app/tests/test_indicators.py   pytest(python-backend 첫 테스트 인프라)
```
국면 규칙 v1: BULL=종가>MA60 AND MA20상승 / BEAR=종가<MA60 AND MA20하락 / else SIDEWAYS. 검증 데이터 전 임의변경 금지.

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
| **오늘(today)** | `TodayBriefingTab.vue` | 시장 한줄 · 매수후보(55컷 momentum) · **🪝 차트 타이밍 후보(검증 전 베타)** · 신뢰도 스트립 · 내 포지션 · 도구 바로가기 |
| **시장(market)** | 허브 인라인 + 서브탭 | 시장지도(`SectionMarketMap`)·섹터거래대금 / 서브: 수급·타이밍·뉴스·글로벌(embedded) |
| **발굴(discover)** | 허브 인라인 + 2단 서브탭 | 상단 **'덜 빠지는 섹터' 배지(베타)** + 리스트 5트랙 + 심화도구 |
| **매매(trade)** | `PaperTradingPage.vue`(관리자) | 모의·실전·봇성과·주간리포트 |

- **발굴 리스트 서브탭**(lazy, 택1): 💎저평가·🚀성장·📉낙폭과대·💰실적·🏦수급 (`ensureDiscoverListLoaded`).
- **발굴 심화도구 서브탭**: 종합(`SectionTotalRecommendation`)·AI전략·백테스트(`SectionBacktest`)·스크리너·퀀트TA(`SectionQuantTa`).
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
| **차트 타이밍(베타)** | TodayBriefingTab | `/recommendation/trend-pullback-top10` | ChartSignalController→ChartPatternClient | **python `/api/v2/chart/timing`**(pykrx) |
| **섹터강도(베타)** | 발굴 상단 배지 | `/recommendation/sector-strength` | ChartPatternClient | **python `/api/v2/chart/sector-strength`** |
| 발굴 5트랙 | 발굴 서브탭 | `/recommendation/{value…smartmoney}-top10` | RecommendationService | 재무/투자자/가격 |
| 종목 상세 | StockDetail | `/stock/{code}/quick·heavy·conclusion·catalyst` | StockDetail/Conclusion/Catalyst | 단일시세경로+KIS+Gemini |
| 매매 봇 | PaperTrading | `/paper-trading/bot/*`·`/real/*` | AutoTradingBot/RealTrade | KIS 실주문 |
| 시그널 검증 | (배치/조회) | `/signal-outcomes/accuracy-by-band` | SignalOutcomeService | signal_outcome |
| 시장국면 | (스냅샷 내부) | — | MarketRegimeClient | python `/regime/current` |

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
4. **멀티 인스턴스 미지원**: 봇 크론 fail-open 락 미사용(단일 인스턴스 전제). 확장 시 fail-closed 필수(VERIFICATION_BACKLOG P3-1).

---

## 15. 차트기법(신규, 2026-06-29) 배치

- **펨코 추세추종 기법을 momentum 스코어러와 분리된 별도 모듈로 통합.** 성격이 다른 두 신호:
  - **차트 타이밍**(정배열+60/240 이격도+엔벨로프 눌림목+박스, mean-reversion) → **'오늘' 탭 매수후보 아래 '검증 전 베타' 별도 섹션**(momentum 55컷 후보와 분리·**대체 아님**).
  - **섹터 상대강도**('덜 빠지는 섹터') → **'발굴' 탭 상단 상시 배지**(유니버스 필터).
- **박스 정량화 = A안(변동성 박스)**: box_len일 range_pct≤box_range_max → 돌파 → higher-low 눌림목 지지.
- **섹터지수 = 합성지수**(기존 `SectorStockConfig` 16섹터×구성원 평균, Java→python 전달). 타이밍 유니버스 = `getAllStockCodes()` ~134종목.
- **미검증**(`unverified=true`) → 봇/종합추천/매수후보 랭킹 편입 금지. 검증 = **VERIFICATION_BACKLOG P2-12**(적중률/MDD 백테스트) 통과 시 매수후보 타이밍으로 승격.
- 모듈: python `app/indicators/*`(+pytest) + Java `ChartPatternClient`(best-effort)/`ChartSignalRanker`(순수+테스트)/`ChartSignalController`. 프론트: `TodayBriefingTab`(타이밍)·`StockTradingDashboardV2`(섹터배지).

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

## 17. 관련 문서 인덱스

- `CLAUDE.md` — 작업 지침 + 불변식(1차 출처)
- `VERIFICATION_BACKLOG.md` — 검증/개선 티켓(P2-12 차트 타이밍 백테스트, P3-1 멀티인스턴스 락)
- `MARKET_INDICATORS_API.md` — 지표 API 레퍼런스
- `docs/STOCK_PLATFORM_GUIDE.md` — 화면→코드→DB 추적
- `docs/STOCK_AZ_FULL.md` — 2026-06-08판(stale, 본 문서가 대체)
- `docs/SYSTEM_OVERVIEW.md` · `docs/STOCK_PLATFORM_ONEPAGER.md` — 요약

> 본 문서는 매핑 시점(2026-06-29) 스냅샷. 정밀 cron/개수/필드는 코드가 출처이며, 산식·불변식은 CLAUDE.md를 따른다.
