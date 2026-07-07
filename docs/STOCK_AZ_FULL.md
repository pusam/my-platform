# 주식 플랫폼 A–Z 전수 배치도 (2026-06-29 생성 · **2026-07-02 갱신**)

> **생성**: 2026-06-29, 코드 직접 전수(Explore 3-레이어 매핑) 기준. **최종 갱신**: 2026-07-07(D)(**📔 수동 매매 저널 V43+V44 Phase 1~3**: 매수 신호 스냅샷·19:40 자동평가(signal_outcome 동일 잣대)·stats·섹터 집중 경고·프론트(모달/매매 탭/이력 마커) — §19 하단 세션 요약. 그 앞 (B): 표시 전용 3작업 + 기관 수급 키 오타 / ATR 세트 V42 §14-7 + 악재 조기경보).
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

### 1-3. DB 백업 체계 (`ops/backup/`, 2026-07-06)
- **`backup.sh`(매일 02:00 cron)**: `docker compose exec -T mariadb` 경유 `mariadb-dump --single-transaction --routines --triggers --all-databases | gzip`. 인증은 **컨테이너 내부 env 폴백**(`${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}`, MYSQL_PWD 로 argv 노출 회피) — 스크립트에 시크릿 없음. **무결성 검증**(`gzip -t` + 최소 1MB, §4c: 빈 백업이 조용히 쌓이는 것 방지) 통과분만 **로컬 14일**(`/var/backups/myplatform-db`, repo 밖) 보존 → **rclone `b2backup` remote 로 B2 업로드 + 원격 30일**(`delete --min-age 30d`). **실패 시에만** 텔레그램 리스크 채널 알림(성공 무알림).
- **`check_backup_age.sh`(주 1회, 일요일)**: B2 원격 최신 백업 mtime 이 48h 초과면 경고 — "실패 알림이 안 옴 ≠ 백업 성공"(backup cron 자체 사망 감지).
- **설치**: 리포에 스크립트+`ops/backup/README.md`(rclone/B2 설정·crontab·복구 리허설 절차) 커밋, **서버는 `git pull` 후 수동 설치**(cron 등록). B2 키는 미발급 상태여도 스크립트 완성 — 키는 서버 rclone config 에만 주입(로컬 백업은 키 없이도 정상). 복구 리허설 = 임시 `mariadb:11.2` 컨테이너 복원 후 `signal_outcome` 건수·최신일자 운영 대조.

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
  - `GET /judgment-board?scope=momentum|union` ⭐신규(2026-06-30 B안, 2026-07-01 union Phase2-A) 종합 판단 보드(매수후보 3계층 신호 비교; union=발굴 5트랙 합집합, 4-cat은 scoreMap lookup·없으면 "—"; 산식 무변경 조립). ⭐2026-07-07: 행에 signalTrackRecord(② 참고 — signal_outcome 90일 track/hit/avgAlpha, IN 절 1쿼리 일괄 집계·N+1 금지, n<3="—" 표본부족·정렬 항상 하단)
- **`ChartSignalController`** `/api/recommendation` *(신규, 차트기법)*
  - `GET /trend-pullback-top10` 차트 타이밍(정배열+눌림목, **검증 전 베타**)
  - `GET /sector-strength` 섹터 상대강도('덜 빠지는 섹터')
- **`QuantScreenerController`** `/api/quant-screener` 마법공식·턴어라운드·PEG

### 3-2. 종목 상세·시세
- **`StockDetailController`** `/api/stock`: `{code}/summary` `quick`(1단 3~5s) `heavy`(2단) `conclusion`(룰결론, ⭐2026-07-07 tradePlan 에 ATR14×2.5 참고 atrStopPct/atrTargetPct 병기 — util 재사용·PLAN_* 불변·null=미산출 §4c) `checklist`(5-factor) `catalyst`(V31 재료) `signal-history` ⭐신규(2026-07-07, `SignalHistoryService` — signal_outcome 90일 read-only 타임라인+요약, pending=평가 대기 구분 §4c). ⭐2026-07-07(C) `/api/analysis/diagnosis/{code}` 에 RVOL(V41, `RvolService.getRvolQuiet`, null=§4c)·연속순매수 병기(단일 경로·batchScores 무부담) → QuickSummaryBar 표시
- **`StockPriceController`** `/api/stock-price`: 현재가·히스토리·배치
- **`StockAnalysisController`** `/api/stock-analysis`: 기술지표·수급·투자자동향

### 3-3. 시그널·백테스트
- **`SignalOutcomeController`** `/api/signal-outcomes`: `accuracy` `accuracy-by-band`(V30~V32 조건부) `timeseries` `compare`(컷오프 전후)
- **`BacktestController`** `/api/backtest`: `performance`(적중률/평균손익/MDD/Sharpe, 비용차감)

### 3-4. 매매(봇·페이퍼)
- **`PaperTradingController`** `/api/paper-trading`: `account/*` `portfolio` `trades` `statistics` `bot/{status,config,toggle,performance}` `real/{account,position,trades,place-order}`(KIS)
- **`ManualTradeJournalController`** `/api/manual-journal` ⭐신규(2026-07-07(D), V43/V44): `POST /` 매수 기록(신호 스냅샷 자동, 소스 실패=null §4c) · `PUT /{id}/close` 매도(전량, realizedPct 확정) · `GET /`·`/{id}`·`/by-stock/{code}`(신호 이력 마커) · `/stats`(적중률·평균α·실현승률+RSI/재료 breakdown, n<10=insufficientSample) · `/sector-exposure?stockCode=`(동일 섹터 보유 경고 — 열린 저널+봇 포지션 read-only, 매핑 밖=mapped:false). 소유 검증 = principal username. **봇/주문 경로와 완전 분리(기록 전용)**

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
| `StockCatalystService` | 재료 태그(Gemini V31, 종목·일자 1회 캐시, 점수 미편입). ⭐2026-07-07 악재 저장 시점 훅 → CatalystRiskAlertService |
| `CatalystRiskAlertService` ⭐신규(2026-07-07) | 관심/보유(봇 포지션·KIS 실잔고) 악재 조기경보 — 관심=시그널 / 보유=시그널+리스크 병행, 종목×일자 1회 멱등(AlertHistory CATNEG_*), classify 추가 호출 0 |
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
| `ManualTradeJournalService` ⭐신규(2026-07-07(D)) | 수동 매매 저널(V43/V44) — recordBuy 시 신호 스냅샷 12필드+KOSPI bm 자동(best-effort, 실패=null §4c; 재료는 일캐시 read-only §4b, RSI는 diagnose 허용 결정), 19:40 평가 배치(hit=`SignalOutcomeService.isHit` 재사용 — signal_outcome 동일 잣대, 멱등 UPDATE), stats/섹터노출(봇 포지션 read-only §4d). 순수함수 assembleSnapshot/evaluate/computeStats/computeSectorExposure(테스트 有). **봇·주문 경로 무접촉** |

- **정규장 마감 강제청산(2026-06-29)**: `executeRegularSessionLiquidation`(15:20) — 봇이 포지션 들고 마감하는 오버나잇 노출 방지. `BotConfig.forceRegularSessionLiquidation`(기본 ON). 가드 = 리더 AND 봇활성 AND 미killed AND 설정ON AND 시각≥15:20 → `sellAllPortfolio("REGULAR_SESSION_CLOSE")`. NXT 연장장/종가단일가 청산은 후속(P2-13 → **진단 종결·§19 2026-07-06·2026-09-14 재개봉**).

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
| 19:40 ⭐ | 수동 저널 평가(매수 3거래일 후, 동일 잣대) | `ManualTradeJournalService` |
| 20:05 / 20:10 | 발굴 5트랙 야간 / 복합신호 | Recommendation/MultiConviction |
| **일 18:00** | **시그널 주간 예측력 측정**(카테고리×regime×밴드, SchedulerLock fail-open) | `SignalWeeklyReportService` |
| 23:00 | 재무 영속화 | `StockFinancialDataService` |
| 03:00 | 배치 정리 | `BatchJobCleanupService` |

> ⚠ 위 cron은 매핑 근사. **정확값은 각 서비스 `@Scheduled`가 출처.** 미적용(주석) 2건: 종가봇 매수/매도(`executeClosingBuyLogic`/`SellLogic`, 2026-09 연장장 대비 재설계 필요). ※ 오버나잇은 15:20 정규장 강제청산(2026-06-29)으로 1차 방어, 연장장 청산은 후속(P2-13 → **진단 종결·§19 2026-07-06**).

---

## 6. 엔티티 / 리포지토리 (도메인별 핵심)

- **종목/시세**: `StockMaster` · `StockPrice` · `StockPriceHistory` · `StockFinancialData` · `StockCatalyst`(V31)
- **추천/분석**: `RecommendationSnapshot`(점수·카테고리세부, growth -1=NA sentinel) · `AiStrategySnapshot` · `MarketIndicatorSnapshot`
- **매매/포지션**: `BotTradingPosition` · `BotConfig`(손절/익절%) · `VirtualAccount`/`VirtualPortfolio`/`VirtualTradeHistory` · `TradingKillSwitch` · `TradingAuditLog` · **`ManualTradeJournal`(V43+V44, 2026-07-07(D))** — 수동 매수/매도 + 매수 시점 스냅샷 12필드(점수4종·RSI·재료·RVOL·국면·ATR손절·5일등락, null=미수집 §4c) + bm_price_at_buy(KOSPI, V44) + 3거래일 평가(pct/alpha/hit, evaluatedAt null=대기). v1 전량 매도 가정. 봇 테이블과 완전 분리
- **시그널/성과**: `SignalOutcome`(3일후 return + V30~V32 스냅샷 + **V41(2026-07 이전 merge) `rvol_at_signal`**(당일 거래대금÷직전 20거래일 평균, `record()`가 best-effort 스냅샷 — 관심 쏠림날 적중률 사후검증용), NULL=미수집; **V36(2026-06-30) `uq_so_type_code_date` UNIQUE(signal_type,stock_code,signal_date)** — idx_so_type_date는 컬럼순서 달라 중복 아님, 유지) · `WeeklyTradingReport`(봇 매매 실적) · **`SignalWeeklyAccuracy`(V37, 2026-07-06 — 시그널 예측력 주간 스냅샷, week_start UNIQUE, report_json에 전체 크로스탭)**
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
| **DART** | `DartService`/`DartDisclosureMonitorService` | 공시(06/08/16:30) + 5분 모니터. ⭐2026-07-07 대상 = 실잔고>봇 포지션>관심(상한 30, rate 156회/일×30≈4.7k<10k) + 주요 공시(소송·계약해지 등) **중립 톤** 필터(§4c 악재 단정 금지) |
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
| **오늘(today)** | `TodayBriefingTab.vue` | 시장 한줄 · **🌙 간밤 미국장 tilt(2026-06-30)** · 매수후보(55컷 momentum) · **시간대신호(장전/장후)·실시간수급(장중)**(슬롯 #phase-signals, 발굴서 이동 2026-07-01) · **🪝 차트 타이밍 관찰(python timing, 접기)** · 신뢰도 · **관심종목(슬롯 #watchlist, 접힘 — ⭐2026-07-07(C) 행별 🎯목표 매수가 인라인 편집: `watchlistAPI.setAlert(id,price,'BELOW')` → 기존 `WatchlistService.checkWatchlistAlerts` 5분 크론이 도달 시 텔레그램. 백엔드·크론 기존재, 프론트 UI 만 보완 — 신규 알림 경로/Flyway 없음)** · 내 포지션 · 도구 |
| **시장(market)** | 허브 인라인 + 서브탭 | 시장지도(`SectionMarketMap`)·섹터거래대금 / 서브: 수급·타이밍·뉴스·글로벌(embedded) |
| **발굴(discover)** | 허브 인라인 + 2단 서브탭 | 상단 **'덜 빠지는 섹터' 배지(베타)** + 리스트 5트랙 + 심화도구 |
| **매매(trade)** | `PaperTradingPage.vue`(관리자) | 모의·실전·봇성과·주간리포트·**📔 수동 매매(2026-07-07(D), `ManualJournalSection` 자립 컴포넌트 — stats 카드+breakdown 칩+리스트(스냅샷 칩)+전량 매도 기록)** |

- **발굴 리스트 서브탭**(lazy, 택1): 💎저평가·🚀성장·📉낙폭과대·💰실적·🏦수급 (`ensureDiscoverListLoaded`).
- **발굴 심화도구 서브탭**: 종합(`SectionTotalRecommendation`)·**🧭 종합판단(`SectionJudgmentBoard`, B안 2026-06-30)**·AI전략·백테스트(`SectionBacktest`)·스크리너·퀀트TA(`SectionQuantTa`).
- **발굴 2단 네비 통합(2026-07-01)**: 목록 5트랙 + 심화도구 두 서브탭 바를 **둘 다 상단**에, `discoverGroup`('list'/'deep')로 선택 그룹 콘텐츠만 표시(심화 바가 긴 리스트에 묻히던 문제 해소). **기본 진입 = 🧭종합판단 보드**(상수 한 줄 변경 가능). 빈 보드 폴백(목록 발굴 안내). 위젯 9섹션 group 게이팅 + deep 기본 시 리스트 eager 로드 가드. 모바일=그룹라벨 숨김(가로스크롤).
- **역할 분리**: 모멘텀 종합추천(`getTop5`)은 오늘 탭 전용 — 발굴에 재추가 금지.

### 11-3. 종목 상세 (`views/StockDetailDashboard.vue`, ~4,700줄)
- 헤더: 복합신호 배지 + 단기/중장기 듀얼점수 + 현재가. ⭐2026-07-07 **보드↔상세 왕복 네비**: "◀ 이전 / 보드 N/M / 다음 ▶" — 종합판단 보드 행 클릭(새 탭)이 sessionStorage `judgmentBoard.nav` 로 종목 순서를 전달한 경우에만 표시(직접 진입 미표시, 새 라우트·쿼리 오염 없음. /stock/:code 컴포넌트 재사용이라 이동 시 명시적 재조회).
- 상단 카드: `StockConclusionCard`(결론·손절/목표+MFE/MAE·점수대 적중률·재료배지 + ⭐2026-07-07(C) **진입 위치 한 줄**(`entryPosition` — overheatPenalty 신호 3종을 **스냅샷 태그 재사용**(RSI/5일/볼린저, 재계산 없음)으로 세고 + 지지선 거리(`ChartPatternService.detectSupportResistance` 캐시 재사용): 2개↑=🔴과열/1개=🟡주의/0개&지지선+3%이내=🟢눌림/중립·결측=미렌더 §4c. 순수함수 `parseOverheat`/`classifyEntryPosition` 테스트) + ⭐2026-07-07 **ATR 참고 줄** "변동성(ATR) 기준 -X.X%/+Y.Y% · 백테스트 참고치·검증 전" amber 톤, null=줄 미렌더) + `QuickSummaryBar`(RSI/20일/외인/기관/리스크/AI + ⭐2026-07-07 **외인/기관 "N일 연속" 순매수 배지** — 2일↑만, 참고 톤, `diagnosis` supplyDemand.foreign/institutionBuyStreak, null=미표시 §4c).
- 본문: `StockBriefingHeadline`(행동권고) · `StockRiskCard`(DART+뉴스+AI).
- 심화(접기 `DetailSection` v-show 마운트 유지): Peer·VolumeProfile·SupportResistance·RelatedStocks·ChartPattern + ⭐2026-07-07 **`SignalHistorySection`**("📜 신호 이력" — signal_outcome 90일 타임라인, 요약을 제목에 병기, 평가 대기 구분 §4c, n=0 미렌더, 자체 fetch=heavy 계열; ⭐2026-07-07(D) **📔 내 매수/매도 마커 병기** — manual-journal by-stock, best-effort·0건 미표시, 저널만 있어도 섹션 렌더) + ⭐2026-07-07 **`CatalystHistorySection`**("📰 재료 이력" — stock_catalyst 30일 read-only 타임라인, 날짜별 등락률 병기, NONE 제외, classify 호출 없음 §4b, n=0 미렌더).
- ⭐2026-07-07(D) **📔 매수 기록 진입점 2곳**(새 라우트 없음): 결론카드 '📔 매수 기록' 버튼 + BuyChecklistModal 하단 버튼 → `ManualJournalModal`(현재가 `/stock/{code}` 프리필·수량·메모, 섹터 집중 경고 표시 — 경고만·mapped:false 미표시 §4c, 실주문 아님 명시).
- 듀얼스테이지: `quick`(3~5s) → `heavy`(risk/AI/peer, 캐시·lazy).

### 11-4. 컴포넌트 (`components/v2/*` 주식 도메인)
DashboardHeader · TodayBriefingTab · StockConclusionCard · QuickSummaryBar · StockBriefingHeadline · StockRiskCard · SignalHistorySection(⭐2026-07-07 신호 이력) · CatalystHistorySection(⭐2026-07-07 재료 이력) · ManualJournalModal/ManualJournalSection(⭐2026-07-07(D) 수동 저널) · StockSearchModal(Ctrl+K) · DetailSection · BuyChecklistModal · BacktestPerformancePanel · SectionBacktest · SectionTotalRecommendation · SectionQuantTa · SectionMarketMap · InvestorTrendTab · FundamentalDiagnosisPanel · PeerComparisonCard · VolumeProfileCard · SupportResistanceCard · RelatedStocksList · ChartPatternList · MagicFormulaSmartTable · BotPnlChart · TradingSafetyWidget(killswitch) · ForecastDetailModal.
공용: VolumePowerGauge(체결강도) · DataFreshness · NotificationBell · StockCodeInput 등.

### 11-5. API 레이어 (`utils/api.js`, axios `/api` + AT/RT 인터셉터)
- `recommendationAPI`: top5 · value/growth/oversold/earnings/smartmoney-top10 · **getTrendPullbackTop10** · **getSectorStrength**
- `stockDetailAPI`: getSummary/Quick/Heavy/Diagnosis/batchScores
- `paperTradingAPI`: account·portfolio·trades·bot·real·performance
- `manualJournalAPI` ⭐신규(2026-07-07(D)): list·listByStock·stats·recordBuy·close·sectorExposure
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
- **수급 캡(A안, P1-6, 2026-07-06)**: composite 총점 raw 합산에서만 `supplyDemand`를 min(sd,**10**)로 상한(역상관 방어). 표시값·validCount·분모·임계 불변, composite 경로 한정(5트랙·보드 무관), 가역 flag `recommendation.supply-demand-cap`. 캡값 재조정은 주간리포트 사후검증 대기. → CLAUDE.md §4.
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
5. **오버나잇 방어**: 정규장 마감 15:20 강제청산(`forceRegularSessionLiquidation` 기본 ON). 리더+killswitch 게이트 탑승. (NXT 잔여 갭 = P2-13 **진단 종결**, §19 2026-07-06 — 진입은 NXT 전면차단, 지정가 미체결 잔여만 수용 갭.)
6. **일일 손실 서킷브레이커(V38, 2026-07-06)**: 당일 봇 **실현손익 합산**(VirtualTradeHistory SELL 확정 기록만) ≤ -한도(기본 30만원, `bot_config` 전용 행) → **신규 진입만 차단·손절/청산 계속**(비대칭 핵심). 기존 -3% 자산 킬스위치(botActive=false=매도 관리까지 중단·평가액=수동매매 오염)와 별개 — 실현손실 기준·DB 영속·날짜 비교 자동 해제·ADMIN 수동 해제(`/bot/daily-loss-breaker/*`). judge 순수함수 **BLOCKED-before-null**(발동 후 DB 블립에도 차단 유지), trip=조건부 UPDATE 멱등(알림/감사 1회). 게이트 = 스캘핑(골든타임 틱당 1회)·스윙(runMode 스냅샷 후)·종가(방어적). → CLAUDE.md §4d.
7. **ATR 세트 — ATR×2.5 청산 + 리스크 균등 사이징(V42, 2026-07-07, VIRTUAL 전용·flag 가역)**: exit 백테스트(ATR×2.5 avgNet +2.10% vs 고정 -0.22%) 근거의 **실험 경로** — flag **`bot.atr-trading.enabled`(기본 OFF)**, **REAL 은 flag 무관 무조건 현행 2중 하드 가드**(`isAtrSetActive` 사이징 + `resolveSwingExitLevels` 청산). 공식: 수량 = riskBudget ÷ (진입가×손절폭%) — `PositionSizer.judge`(**수량 축소 전용, 항상 현행 이하 캡**), riskBudget = `bot_config` 'atr_trading' 행 오버라이드 → 브레이커 한도÷6(기본 5만); 스윙 청산 = 진입 시점 ATR14(Wilder, `AtrCalculator`) 스냅샷 고정(V42 영속·재시작 복원) × 2.5 손절 / ×5/3 익절(`AtrExitRule`), 스캘핑은 사이징만(청산 -1.2% 불변). **폴백 = ATR/입력 결측이면 그 종목 완전 현행**(§4c 확대 금지). 트레일링·타임컷·강제청산·모든 안전 게이트(리더/killswitch/브레이커/sanity) 불변 — 게이트 통과 후 마지막에 수량/청산폭만 결정. 적용값(수량·ATR·손절폭·riskBudget) = `TradingAuditLog`(triggeredBy=ATR_SIZING) 스냅샷. 검증: 포트폴리오 재생(2026-07-07) — **브레이커 가상 발동 0=0(동수)**, 총수익 -70.9%→+96.3%, MDD 72.7%→18.0%(proxy 신호셋·트레일링/쿨다운 미반영 한계). **REAL 확장 조건 = VERIFICATION_BACKLOG P2-17**(VIRTUAL 2주+ 실측). 설계: `docs/ATR_TRADING_SET.md`.

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
- **⚠ Flyway baseline-version=14 하이브리드 구조 (2026-07-06 발굴)**: 이 프로젝트의 마이그레이션은 **빈 스키마에 self-contained 하지 않다.** `V1__baseline.sql`=`SELECT 1;`(no-op)이고 베이스 테이블은 **레거시 `ddl-auto: update` 시절 Hibernate 가 생성**했다. Flyway 는 뒤늦게 도입되며 `application.yml` 에 **`baseline-version: 14` + `baseline-on-migrate: true`** → 운영은 **V1~V14 를 아예 실행하지 않고 V15+ 만 적용**한다. 그래서 다수 마이그레이션이 *어떤 마이그레이션도 CREATE 하지 않는* 엔티티/레거시 테이블을 ALTER·INDEX 한다(`bot_config` V33/34/38, `virtual_trade_history`·`stock_price` V20, `user_asset`·`finance_transactions`·`board` V25, `stock_short_data` V16 `RENAME`(IF EXISTS 없음), baseline=14 가 건너뛰는 `recommendation_snapshot` V22/29·`bot_trading_position` V21/23). **"빈 MariaDB→V1..V38" 은 첫 ALTER 에서 실패**하므로 마이그레이션 테스트는 운영을 재현한다.
- **마이그레이션 테스트**(`FlywayMigrationTest`, `@Tag("migration")`, 2026-07-06): 실제 **`mariadb:11.2`(Testcontainers)** 상에서 레거시 베이스 **최소 스텁**(`src/test/resources/db/testfixture/legacy_base_stub.sql` — 스키마 non-empty 화 → `baseline-on-migrate` 트리거)을 심고 Flyway 를 `baselineVersion=14` 로 **V15→V38 단독 실행**(컨텍스트 로드 없음, 스모크와 역할 분리). 검증: 적용 성공 + pending 없음(최신 도달) + **V36 회귀 가드**(`signal_outcome` UNIQUE `uq_so_type_code_date` 존재). 스텁은 *마이그레이션이 참조하는 컬럼만* 담은 최소 재현이라, 새 마이그레이션이 스텁에 없는 기존 컬럼을 참조해 깨지면 **스텁에 컬럼을 추가하라는 신호**(테스트 결함 아님). **로컬 실행**: Docker Desktop 실행 중 상태에서 `./gradlew :backend:migrationTest`(기본 `test` 태스크는 `migration` 태그 제외 → 스모크 빠른 피드백 보존). **CI**: ubuntu-latest 러너는 Docker 사전 설치(DinD 불필요) → `build-backend` 잡의 별도 step 으로 실행, 실패 시 `deploy` 잡 미도달(배포 차단).
- **프론트**: `cd frontend && npm test`(vitest) + `npm run build`.
- **python-backend**: `cd python-backend && pytest`(지표 + **백테스트(test_backtest 27건) + 지수소스 어댑터(test_index_source)**). **로컬엔 Python 인터프리터 없음(Store 스텁) → Docker 내 실행**(`docker compose run --rm python-backend pytest`).
- **CI/배포**(`.github/workflows/deploy.yml`): gradle 빌드 → 도커 이미지 → SSH 배포 → **post-deploy 헬스체크**(`curl /api/health` 80초 폴링, 실패 시 `docker compose logs backend` artifact). status=000 = 컨테이너 미기동(앱 500 아님). ⚠ 호스트 OOM/SSH 다운은 인프라 영역(컨테이너 死 ≠ 호스트 死) — docker-compose 메모리 합산 ~3.5GB, swap/RAM 여유 점검 권장.

---

## 18. 2026-06-29 세션 변경 요약 (봇 안전 6작업 + 스모크)

각 항목 독립 커밋. 불변식(시세경로·산식·차트분리·SchedulerLockService fail-open) 무변경.

1. **봇 리더 가드 fail-CLOSED** — `BotLeaderElectionService`(Redis 리스+하트비트). 봇 크론 6개 게이트. 멀티 인스턴스 중복 주문 차단. (P3-1 부분 해소)
2. **정규장 15:20 강제청산** — `BotConfig.forceRegularSessionLiquidation`(기본 ON)+Flyway V33, `executeRegularSessionLiquidation`. NXT 청산은 P2-13 후속 → **진단 종결·§19 2026-07-06**.
3. **python 가시성** — `PythonBackendHealthTracker`+`/api/diagnostics/python-health`+텔레그램, 차트 응답 `dataAvailable`.
4. **19:30 평가 멱등성** — 확인됨(pending 행 UPDATE, 중복 INSERT 없음). 코드변경 없음. record() DB unique 제약은 P3-2.
5. **tie-break ↔ 차트 타이밍 충돌** — `recommendationComparator` 경고주석(승격 시 이중작용 점검, P2-12 #3).
6. **growth/valueStability -1=NA 가드** — `verdictFor` `score<0→N/A`(NEGATIVE 오표시 버그 수정)+NA factor 숨김+경고주석. nullable 전환은 P3-3. **(2026-07-02) 종합판단 보드도 4카테고리(실적/기술/섹터/수급) 0점→-1(NA)을 '—'로 렌더** — 특히 **수급 -1 은 "순매도"가 아니라 순매수 신호 미포착(0점)**(`scoreSupplyDemand`는 가점만·감점 없음), toDto 가 0→NA(-1) 변환. 음수 점수 오해 방지(표시 전용, 산식 무관).
7. **(후속) `@SpringBootTest` 스모크 + 이중생성자 DI 버그 수정** — 스모크가 작업1·3의 `@Autowired` 누락(컨텍스트 기동 불가)을 즉시 검출 → `ChartPatternClient`/`BotLeaderElectionService` 운영 생성자에 `@Autowired` 추가.

신규 백로그: P2-13(NXT 청산 = **진단 종결·2026-09-14 재개봉**, §19 2026-07-06)·P3-2(signal_outcome unique)·P3-3(growth nullable).

---

## 19. 2026-06-30 ~ 07-02 세션 변경 요약 (차트 백테스트 + P0-pykrx + V36 + 종합판단 보드 + 재료 파이프라인 + Gemini rate + 발굴 축소)

각 항목 독립 커밋. 불변식(단일 시세경로·점수산식·차트분리·regime 규칙 v1·SchedulerLockService fail-open·봇 리더 fail-CLOSED) 무변경. "검증 안 된 신호는 표시 전용·산식 미편입" 원칙 유지.

### ⭐ 2026-07-07(B) 세션 — 표시 전용 read-only 3작업 (산식·봇·Flyway 무변경)
- **작업1 — 공매도 잔고 표시 = 死진단만(미구현)**: 정찰 결과 `short_selling_balance` 1·2차 소스 모두 死 — KRX(`data.krx.co.kr getJsonData MDCSTAT030100`)는 세션/OTP 미확립으로 본문 `"LOGOUT"`(날짜 무관), 네이버 fallback(`finance.naver.com/sise/sise_short_balance.naver`)은 **HTTP 404 페이지 폐지**. `collectShortSellingData()`는 매 실행 0건 → 신규 데이터 미유입. §4c(죽은 데이터 위장 표시 금지)에 따라 **표시 구현 안 함**, 진단 문서 `docs/SHORT_SELLING_DEAD_FEED_DIAGNOSIS.md`만 커밋(복구책: KRX OTP 2-스텝 or 정식 API + 네이버 URL 교체). ※ 로컬 mariadb 볼륨 empty(운영 DB 원격)라 마지막 적재일은 운영 확인 필요, 단 소스 死는 curl 실측 확정.
- **작업2 — 종목상세 📰 재료 이력**: `GET /api/stock/{code}/catalyst-history`(`CatalystHistoryService.assemble` 순수·테스트 3) — stock_catalyst 30일 **read-only**(신규 classify 없음 §4b), 날짜별 등락률 병기(StockPriceHistory, 없으면 null §4c), NONE 제외. 프론트 `CatalystHistorySection.vue`(SignalHistorySection 옆, DetailSection 접기, n=0 미렌더, vitest 5).
- **작업3 — 외인/기관 연속 순매수일 배지**: 순수함수 `InvestorBuyStreakCalculator`(거래일 캘린더 최신순 연속 BUY일, 5일 미만=null §4c, 테스트 6) + `InvestorBuyStreakService`(단일/배치, 보드는 IN절 4쿼리 N+1 없음). 종목상세 QuickSummaryBar "N일 연속"(2일↑, `/diagnosis` 컨트롤러 병기 — batchScores 무부담) + 종합판단 보드 ② 참고 "수급연속" 컬럼(외N·기M). streak5 = 백테스트 약한 양(+) 신호 → **참고 톤·unverified·산식 미편입**.
- **작업4 — 기관 수급 표시 키 오타 수정(후속 버그픽스)**: 백엔드 `StockDiagnosisDto.SupplyDemandDto` 직렬화 키를 코드로 확정(필드 `institutionNet5Days`, `@JsonProperty`·전역 naming-strategy 없음 → JSON 키 = `institutionNet5Days`). 프론트가 **`instNet5Days`(오타)** 로 읽어 `undefined` → 기관 5일 수급이 화면에서 누락되던 표시 버그. `QuickSummaryBar.vue`(기관 라벨/금액/색상 3곳) + `StockBriefingHeadline.vue`(`instNet` computed — 항상 null이라 "외인·기관 동반 매도" 경고·supplyPos에서 기관 무시)를 정정. 이미 정합이던 `FundamentalDiagnosisPanel`·`EarningsScreenerPage`·외국인(`foreignNet5Days`)은 무변경. 회귀 테스트: 실제 키만 채운 mock 가드 2건(구 오타면 실패). **표시 버그만** — 산식·백엔드 무변경.
- 검증: 백엔드 신규/변경 타겟 테스트 green + 전체 compile 성공(전체 :backend:test는 CI 게이트), 프론트 **171 vitest green + build 성공**. 새 주식 라우트 없음.

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
- 컬럼 3계층: **① 점수(검증/게이트** total/기술/실적/섹터테마) · **② 참고(미검증·점수 미편입** 차트타이밍/섹터강도/간밤미국장/**RVOL(V41)**/신호이력) · **③ 경고(수급 역상관 의심** ≥10, 표본작음 톤). 정렬·필터. **종합점수 산식 무변경(조립·표시 전용)**.
  - **RVOL(V41, `RvolService`)** = 당일 거래대금 ÷ 직전 20거래일 평균(종가×거래량 근사). 시세 **cache-only**(단일 시세경로), 20거래일 미만/캐시미스=**null(§4c)**. `getRvolBulk`(보드, 보드 조립 거래대금 재사용)·`getRvolQuiet`(단건). 미검증·랭킹/산식 미편입 — 배지 표시 전용. ⭐2026-07-07 종목상세 QuickSummaryBar 에도 "RVOL 2.3x" 병기(`/diagnosis` 단일 경로, batchScores 무부담).
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

### 2026-07-06 세션 — P1-6 예측력 측정 상설화(주간 자동 배치)

**"2-4주 뒤 수동 재측정"을 주간 자동 배치로 상설화** — 어떤 신호가 실제 수익을 내는지 측정하는 상시 피드백 루프. **종합점수 산식·가중치 무변경(측정 전용).** 4 독립 커밋:

- **측정 축 = 카테고리(4) × regime(BULL/BEAR/SIDEWAYS/UNKNOWN) × 점수밴드**, 지표 = 적중률/평균 alpha_3d/표본수. `SignalOutcomeService.aggregateCategories`(카테고리별 임계 실적≥20·수급≥15·기술≥13·섹터≥14, c85f304) + `aggregateBands` **재사용** — regime 버킷별로 재호출하는 **2D 파티션**(`WeeklyAccuracyAggregator`, 순수함수+테스트 11케이스). **regime_at_signal NULL = UNKNOWN 버킷 정직 분리**(pykrx 깨졌던 구간). `CategoryStat.avgAlpha` additive(측정 지표, 기존 테스트 무영향).
- **⚠ 2D 파티션 결정 근거**: 완전 3중 크로스탭(regime×카테고리×밴드, ≤64셀)은 현재 표본(n≈88)에선 **거의 전셀 표본부족이라 무의미**. `report_json`에 전체가 담기므로 **표본 축적 후 3D 집계 추가는 스키마 무변경으로 가능** — 그때 도입.
- **표본부족(§4c)**: 셀 n<10 = `insufficientSample=true` 명시 + n 병기(숨기지 않고 위장 금지). hitRate/alpha는 계산하되 신뢰 낮음 표기.
- **추세**: 이번 주 vs 누적 전체 델타(악화 감지, 양쪽 표본 충분할 때만). **누적 경고**: "수급 역상관 지속 N주째"(강세-수급 avg alpha<0 & n충분 = 주간 플래그 → 직전 스냅샷 연속 카운트).
- **영속화**: **별도 엔티티 `SignalWeeklyAccuracy`(V37, table `signal_weekly_accuracy`)** — WeeklyTradingReport(봇 매매 실적)와 도메인·수명주기 상이해 통합 대신 분리. 주 1행(week_start UNIQUE) UPSERT, report_json에 전체 크로스탭.
- **크론**: 일 18:00(batchScheduler) + `SchedulerLockService`(fail-open, 봇 크론 아님 — 더블런 시 같은 주 UPSERT 무해). 기존 §5 잡(19:30 MON-FRI 평가, 20:05 야간)과 무충돌. Clock 주입(주 경계 결정성). 텔레그램 모닝브리핑 채널 요약(카테고리별 적중률·전주 대비·경고).
- **API**: `GET /api/signal-outcomes/weekly-report`(최신) · `/weekly-report/history`(12주 추세) · `POST /api/admin/signal-outcomes/weekly-report/run`(ADMIN 수동). 집계는 `WeeklyAccuracyAggregator` 순수함수 분리+테스트, 서비스 오케스트레이션은 `SignalWeeklyReportServiceTest`(주 경계·격리 위임·UPSERT·스트릭).
- **가중치 재조정은 여전히 데이터 대기** — 이 작업은 측정 상설화까지. 국면별 표본 축적되면 P1-6 로드맵 A안(단조·유의한 것만 산식 합류) 재검토.

---

### 2026-07-06 세션 — P1-6 수급 역상관 방어(composite 수급 캡 10, A안)

측정 상설화(위)에 이어, **확정된 수급 역상관이 종합점수를 매일 오염시키는 것**을 가중치 재설계 없이 최소·가역으로 막음:

- **A안 = composite 총점 raw 합산에서만 `supplyDemand`를 min(sd,10) 캡**(`RecommendationService.cappedSupply` 순수함수). B안(수급 제외·3카테고리)은 validCount≥3이 100% 커버리지로 바뀌어 풀 게이트 붕괴 + 분모/임계 재유도 필요라 기각(구조 변경 = "가중치 보류" 취지 위배).
- **불변식**: 표시값(`dto.supplyDemand`)·validCount·정규화 분모(80)·임계(75/55) **전부 불변**. **composite 경로 한정**(`getNormalizedTotal`/`toDto`/`calculate()` 필터) — 5트랙 발굴(💰수급)·종합판단 보드 수급 표시(≥10 경고)는 무영향. **가역 flag** `recommendation.supply-demand-cap`(기본 10, 20↑/-1=비활성).
- **실데이터 검증(prod snapshot 30일, read-only 재채점, SANITY 재계산==저장 0 mismatch)**: 21배치/122연인원, **STRONG_BUY 8행 중 7행 강등 — 전부 삼성전기(009150), sd=20·비수급base 40~45 = 수급의존 SB 원형**(75~81→62~68 BUY). 강한 베이스 SB는 불변. **수급 분포 이분법(≤10 or 20) → 캡10≡12, 캡15는 4행만 → 캡 10 확정**. SB 표본 8행/1종목으로 작아 **`SignalWeeklyReportService` 주간 리포트에서 캡 전/후 성과 비교로 사후검증**(단서).
- 테스트 `RecommendationSupplyCapTest`(경계값·강등 75→62·강한베이스 유지·validCount 불변·가역). 기존 회귀(Score/Sort/Normalize) 무변경 green.

---

### 2026-07-06 세션 — 일일 손실 서킷브레이커 (V38, 봇 §4d-6)

VKOSPI 90대 고변동 국면 대비 — 연쇄 손절 시 출혈 확대를 막는 **장중 누적 실현손실 서킷브레이커**(기존에 없던 마지막 조각). 4 독립 커밋:

- **비대칭 차단**: 당일 봇 실현손익(확정 기록만, §4c) ≤ -한도(기본 300,000원, 사용자 확정) → **신규 진입만 차단**(스캘핑·스윙·종가 3경로 게이트), **손절/청산/모니터는 계속** — 기존 -3% 자산 킬스위치가 botActive=false 로 탈출까지 세우는 것과 대비, 브레이커가 먼저 걸려 -3% 전에 진입을 멈추는 설계.
- **% 대신 절대금액**: 분모(계좌 자산)가 평가액 기반·in-memory 재앵커·수동매매 공유 오염이라 기각.
- **Plan 검증에서 잡은 결함 3건 반영**: ① 'trading_bot' 행 load-modify-save(무 @Version) 병행 쓰기가 trippedDate 클로버 → **전용 행('daily_loss_breaker') + 조건부 UPDATE**(rowsAffected==1=최초 발동만 알림/감사, 멱등). ② **BLOCKED-before-null** judge 순서 — 발동 후 DB 블립에도 차단 유지(미발동만 fail-open+스로틀 알림). ③ VIRTUAL 계좌는 읽기 전용 조회(getOrCreate=쓰기 race 금지), 30초 캐시 기각(틱당 1회 직접 합산 — idx_vth_account_date 단일 집계).
- 해제 = 날짜 비교 자동(다음 거래일) + ADMIN `POST /api/paper-trading/bot/daily-loss-breaker/release`(감사+텔레그램). 설정 GET/PUT. status 사다리 `DAILY_LOSS_BREAKER`.
- 테스트: judge 경계값(등호 -limit·±1원·BLOCKED-before-null·자동 해제) + 서비스(TRIP 멱등·fail-open 예외·VIRTUAL 해석·release 멱등) 16케이스, 기존 `AutoTradingBotServiceTest` green(provider null=게이트 통과로 기존 동작 보존).
- **배포 후 확인**: 임계 1원 설정 → VIRTUAL 손절 1회 → 다음 틱 차단 로그·텔레그램 1회·audit 행 → release 재개 → 익일 자동 해제.

### 2026-07-06 세션 — P2-13(NXT 청산) 진단 종결 (구현 불가·수용 갭, 코드 무변경)

2026-09-14 거래시간 연장(NXT ~20:00) 전, "봇이 NXT 시간대(15:30~20:00)에 포지션을 보유할 수 있는가"를 코드로 확정. **진단 우선·성급한 구현 금지** 원칙 하에 **Option 1(문서화 종결)** 확정. 코드/안전장치/시간대 불변식 전부 무변경.

- **① 진입(HOLD 시작) = 전면 차단.** 15:20 이후·NXT에 신규 진입하는 스케줄 경로 없음: 스캘핑 매수 `executeScalpingBuyLogic`(cron `*/30 * 9-11` + 인코드 09:10~15:00 + `isMarketClosed()` 15:30 + REAL 즉시 return=VIRTUAL 전용) · 스윙 매수 `executeSwingBuyLogic`(cron `0 0 14` 단발) · 종가 매수 `executeClosingBuyLogic`(`@Scheduled` **주석 비활성**). → 봇은 NXT에서 절대 신규 보유를 만들지 않음.
- **② 유일한 HOLD 구멍 = 15:20~15:28 청산 실패 잔여.** `executeRegularSessionLiquidation`(cron `0 20-28 15`, 매분 9틱, 리더+설정ON(fail-safe ON)+botActive+미killed 게이트)이 봇소유∩KIS잔고를 **fire-and-forget 지정가 매도**(`confirmFill` 미사용). 잔류 판정 = **KIS 잔고 재조회 all-or-nothing**(봇소유 코드가 하나라도 남으면 3맵·`bot_trading_position` 전부 유지·`markLiquidatedToday()` 미도달 → 다음 분 재시도). **15:28 이후 재시도 없음** — 스캘핑/스윙 매도 cron(`8-19`)은 내부 `isMarketClosed()`(15:30) 선차단이라 NXT 매도 불가, 15:29 `warnIfLiquidationMissed`는 **알림뿐**, 잔여는 익일 정규장까지 방치(익일 재적격).
- **③ 진짜 NXT 청산 = 지금 구현·검증 불가.** 주문 계층에 **NXT/연장장 라우팅 없음**(`kisService.sellStock(code,qty,price)` 에 거래소/세션 파라미터 부재=기본 KRX 정규장). NXT 연장장 **2026-09-14 전 검증 불가** — 종가봇 재설계(`executeClosingBuyLogic/SellLogic`)와 **동일 전제**.
- **결정**: 정상경로(15:20~15:28 매분 재시도 + 15:29 알림)가 오버나잇 1차 방어를 커버. **잔여 갭**(지정가 미체결로 완청산 실패 → 익일까지 보유)은 **인지·알림·익일 회복(재시작 reconcile 로그)되는 수용된 저확률 갭**(REAL 노출은 스윙 14:00뿐, 스캘핑 VIRTUAL 전용). **재개봉 조건 = NXT 주문 라우팅 구현 + 2026-09-14 연장장(종가봇 재설계와 동반)**.
- **§16-2 시간대 분리 관계 정리**: 진입은 KRX 09:00~15:30 유지(불변). 방어적 **청산만** 향후 NXT로 확장 — "표시-NXT vs 봇-KRX 경계를 통일 금지"에 **위배 아님**(경계를 섞는 게 아니라 방어 청산 창을 넓히는 것).
- **Option 2(마지막 재시도 공격적 호가/시장가 하드닝) = 검토 후 기각**: 저확률 잔여 갭 대비 주문 semantics 변경 + 슬리피지 도입이 과대. **15:29 잔여 알림 실발생 빈도가 축적돼 근거가 생기면 재개봉.**
- **백로그 신규 2건(코드 미수정)**: **P2-13-a** — `AutoTradingBotService.java:216-223` 주석이 "매도 가드 08:00~20:00, cron binding"이라 서술하나 실제로는 두 매도 내부(line 1899·2780)의 `isMarketClosed()`(15:30)가 binding(08~20 윈도우는 15:30 이후 死코드) → NXT 배선 시 정정. **P2-13-b** — 지정가 청산 잔여 하드닝(Option 2, 위 기각 근거 하에 데이터 축적 시 재검토).

### 2026-07-06 세션 — 매크로 tilt (P3-7, 표시 전용·unverified·산식 미편입) + V39 일일 스냅샷

간밤 미국장 tilt(작업3)의 **패턴 복제** — 매크로 3축으로 RISK_ON/NEUTRAL/RISK_OFF 를 '오늘' 탭에 보조 표시(간밤 줄 아래 형제 줄 "🌐 매크로"). 차별점 = **판정값 일일 영속화**(간밤은 미영속): 주간 리포트에서 regime v1 대비 예측력 사후 측정이 승격 조건이라 기록 없으면 검증 불가.

- **3축 소스**: ① **VKOSPI = KIS 업종코드 `0503`** — 지수 마스터(idxcode.mst) 실물 확인으로 확정(`00503VKOSPI`, KOSPI `00001종합`→`0001` 동일 구조) → **기존 `getIndexDailyOhlcv("0503")` 재사용**(신규 TR·KRX 폴백 불필요. 운영 첫 응답 1회 확인 — 빈 응답이면 축 null 강등, §4c). ② **국고3년 = ECOS**(`EcosClient` 신규 — 817Y002/010200000, 기준금리 722Y001은 스냅샷 참고용) 20거래일 추세(bp). ③ **SOX 추세 = 자체 스냅샷 축적**(기존 Yahoo ^SOX 단건 재사용, 신규 소스 0) — 최신 live vs ~5거래일 전 자체 기록. **콜드스타트 ~5거래일 soxTrend=null = 의도된 웜업**(§4c, 축 제외로 자연 강등).
- **분류**(`MacroTiltService.classifyMacroRegime`, 순수+테스트 12케이스): VKOSPI≥30 → RISK_OFF 강제(간밤 VIX≥30 대칭) · 축별 투표(VKOSPI <18/≥25, 금리 ∓15bp, SOX ±3%) · null 축 투표 제외 · 합 ±2. **임계 전부 임시값.** 알려진 한계 2건 Javadoc 명시: **NEUTRAL 고착 비대칭**(ECOS 키 발급 전 금리 축 상시 null → RISK_ON 은 2축 동시 극단 필요. ±2를 가용 축 수로 스케일 금지 — 시계열 오염) · **금리 부호 양면성**(하락=완화 기대 vs 안전자산 쏠림 — 1순위 캘리브레이션 대상).
- **V39 `macro_tilt_snapshot`**(일 1행 UPSERT, V37 선례): tilt + **판정 입력 3종 재현용**(vkospi/ktb3y·rate_trend_bp/sox_level·sox_trend_pct) + **관측일 3종**(vkospi_date/rate_date/sox_asof — 08:15 스냅 시 VKOSPI=T−1·ECOS=T−1~2, 사후검증 신선도 필터) + **regime_v1 동시 스냅**(`getCurrentRegimeQuiet`) + drivers(사용자가 본 그대로). `MacroTiltScheduler` 08:15 크론(락 `macro.tilt-snapshot`, `macro.tilt.snapshot-enabled` 기본 ON). **단일 compute 경로** — 표시 API(`GET /api/macro-tilt`, 30분 캐시)와 스냅샷이 같은 계산 사용(어긋나면 "본 tilt"≠"검증되는 tilt").
- **ECOS 키 설정 절차(미발급 상태로 완성 — 키 없이 금리 축만 null)**: ① https://ecos.bok.or.kr 인증키 발급 → ② 서버 `.env` 에 `ECOS_API_KEY=<키>` → ③ compose backend `environment:` 이중배선은 커밋됨(`${ECOS_API_KEY:-}`) → ④ **backend recreate 필요**(`docker compose up -d --force-recreate --no-deps backend` — env_file 은 생성 시점 고정, §4b 함정 동일). 검증: `/api/macro-tilt` drivers 에 "국고3년" 등장.
- **함정 방어**(EcosClient): 키가 **URL path 포함** → 실패 로그에 URI 미출력(유출 방지) · **INFO-200/100 = HTTP 200 + RESULT body** → 조용히 빈 리스트(키 발급 전 ERROR 스팸 방지) · **%→bp ×100 은 명명 순수 헬퍼**(`trendBp`)+테스트(단위 무음 버그 가드) · §16-10 URI.
- **불변식**: python regime v1·추천 산식·봇 **절대 미편입**(표시+스냅샷만) · 어휘 RISK_ON/OFF ≠ BULL/BEAR(의도적 분리) · `unverified=true`. 승격 조건 = **P3-7**(주간 리포트 기준 v1 대비 유의한 추가 예측력 확인 시 regime 보조 입력 후보로 재검토).
- **배포 후 확인**: 08:15 스냅샷 1행 생성 · VKOSPI 0503 첫 실응답 · 키 미발급 상태에서 ERROR 로그 0.

---

### 2026-07-07 세션 — ATR 세트 봇 통합(V42, Phase 0~3) + 표시 전용 3작업(신호 이력·ATR 참고치·보드↔상세 네비)

**전반: ATR 세트(ATR×2.5 청산 + 리스크 균등 사이징) 봇 통합** — Phase 0(현행 매핑 문서화, 코드 무변경) → Phase 1(`AtrCalculator`/`PositionSizer`/`AtrExitRule` 순수함수, 미배선) → Phase 2(봇 배선, **VIRTUAL 전용·flag `bot.atr-trading.enabled` 기본 OFF·REAL 2중 하드 가드**, V42) → Phase 3(포트폴리오 레벨 재생 백테스트 — 브레이커 가상 발동 0=0 동수 확인). 상세 = **§14-7**·`docs/ATR_TRADING_SET.md`·`SIGNAL_VALIDATION_2026-07.md` Phase 3-b. REAL 확장 조건 = P2-17(VIRTUAL 2주+ 실측).

**후반: 이미 쌓인 데이터의 화면 노출 3작업** — 전부 **표시 전용 read-only**(산식·임계·봇·시세경로·Flyway 무변경), 각 독립 커밋:

1. **종목별 신호 이력**(`661f857`): `SignalHistoryService` 신규 — `signal_outcome` 최근 90일 read-only 타임라인+요약(평가 n·적중·평균 α), **평가 전 행 = pending(평가 대기) 구분**(§4c 미평가≠미스). 순수함수 `assemble` 분리+테스트. `GET /api/stock/{code}/signal-history`. 종목상세 심화 `SignalHistorySection`(요약을 접기 제목에 병기, n=0 미렌더, 자체 fetch=quick 무지연). 종합판단 보드 행에 trackCount/Hit/avgAlpha 병합 — **IN 절 1쿼리**(`aggregateTrackRecordByCodes`, N+1 금지), '이력' 컬럼(② 참고, "3/5 · α+1.2%") + 정렬(적중률 desc, **n<3 표본부족 "—" 항상 하단**).
2. **ATR 참고 손절/목표**(`77bafb4`): `StockConclusionService.buildTradePlan` 이 기존 `AtrCalculator`/`AtrExitRule` **재사용**(신규 구현 없음, 봇과 동일 소스·40행) → `TradePlan.atrStopPct/atrTargetPct` **병기만**(PLAN_* 산식·기존 필드 불변, ATR 미산출=null). 결론카드 손절/목표 아래 amber '관찰' 톤 줄("백테스트 참고치 · 검증 전 — 기본 계획 아님", null=미렌더).
3. **보드↔상세 왕복 네비**(`f1b4c06`, 순수 프론트): 보드 행 클릭 → 표시 순서 코드 리스트를 sessionStorage `judgmentBoard.nav` 저장 + `/stock/{code}` **새 탭**(복사본 상속 — 새 라우트·쿼리 오염 없음, 팝업 차단 시 같은 탭 폴백). 상세 헤더 "◀ 이전 / 보드 N/M / 다음 ▶" — **보드 진입 시에만** 표시(직접 진입 미표시). `/stock/:code` 는 컴포넌트 재사용 라우트라 이동 시 명시적 재조회(router.replace + searchStock).

검증: 백엔드 전체 `./gradlew test -PskipFrontend` + 프론트 vitest 160 + build green. 신규 테스트 = `SignalHistoryServiceTest`·`JudgmentBoardServiceTest`(trackRecord)·`StockConclusionServiceTest`(ATR 3케이스)·`SignalHistorySection.test.js`·보드/결론카드 테스트 확장.

---

### 2026-07-07 세션(후반) — 악재 조기경보: 관심/보유 종목 갭 해소 (기존 파이프라인 조립, 산식 무변경)

"관심종목·봇 포지션에 악재가 떠도 아무도 모르는" 갭을 기존 파이프라인(뉴스 크롤→Gemini 재료 분류→텔레그램 + DART 모니터) **조립**으로 해소. classify 추가 호출 0·분류 일캐시(§4b) 불변·Gemini RateLimiter(4.5s) 경유·보드 read-only 불변. 3 독립 커밋:

1. **워밍 대상 확장**(`de5e9ef`): `CatalystWarmingService` 대상 = **관심(watchlist 활성 전체) > 보유(봇 포지션+KIS 실잔고) > 발굴 5트랙**(라운드로빈), 전체 **40컷**(`mergeWarmTargets` 순수함수, 컷 로그 가시화). ⚠ **장중 추가 워밍 미도입**(근거 코드 주석): §4b 일캐시라 08:00 분류 종목은 장중 전부 캐시 히트 = 재분류 불가 → 실익 없음(quota 는 가능: 40종목 ≤8콜 ≈ 직렬화 36초). 장중 커버는 DART 5분 모니터+온디맨드 담당 → **08:00 1회 유지**.
2. **악재 즉시 알림**(`ddf6efa`): `CatalystRiskAlertService` — 재료가 **악재(NEGATIVE)로 저장되는 시점 훅**(단건·배치 공통, 워밍 notify=false 에서도 발동 = 08:00 워밍이 밤사이 악재 선제 포착). 대상(관심/봇 포지션/KIS 실잔고 10분 캐시)이면 **관심=시그널 채널 / 보유=시그널+리스크 병행**(긴급도↑), 종목명·요약·대표 뉴스 제목/링크 1건. **중복 방지 = AlertHistory(CATNEG_코드_일자) 종목×일자 1회 멱등**. 대상 악재는 기존 일반 악재 알림 억제(리스크 채널 이중 발송 방지), 비대상은 기존 동작 보존. 판정 `decide`/`alertKey`/메시지 순수함수+테스트. ⚠ 테스트 함정: ObjectProvider 다중 주입은 타입 소거로 `@InjectMocks` 오배선 → 수동 생성 필수.
3. **DART 관심종목 필터**(`c6162c5`): `DartDisclosureMonitorService` 대상 = 기존 '실잔고만' → **실잔고>봇 포지션>관심 확장**(`mergeTargetNames` 순수함수, **상한 30** — rate 근거: 실행 156회/일×30종목 ≈ 4,700콜 < DART 한도 10,000). 기존 DANGER 분류(유상증자·거래정지 등) **재사용** + 보조 **주요 공시 키워드**(소송·계약해지·영업정지·손해배상 — '정정'은 기재정정 스팸이라 의도적 제외) = **'📌 주요 공시' 중립 톤**(§4c 키워드만으론 악재 단정 금지, 직접 확인 유도). 중복 방지 = 기존 dartSeen(rceptNo 3일 TTL) 공용.

**Gemini quota 계산(작업1 결정 근거 요약)**: 무료 flash-lite ≈15 RPM, 전역 RateLimiter 4.5s 직렬화 ≈13 RPM. 워밍 40종목 = 최대 8콜(5청킹) ≈ 36초 점유(1×/day 08:00). 기존 소비자 = 모닝브리핑 워밍 07:30 ≤5콜 · AI전략 9/12/15시 · 상세 온디맨드 — 직렬화가 버스트를 큐잉하므로 RPM 은 안전. 장중 워밍을 안 하는 이유는 quota 가 아니라 **일캐시 구조상 무의미**(위 1).

---

### 2026-07-07(D) 세션 — 📔 수동 매매 저널(V43+V44, Phase 1~3 완료)

봇/시그널은 추적되나 **사용자 본인 수동 매매는 기록이 없던** 갭 해소 — 매수 순간 신호 스냅샷 + 3거래일 자동 평가로 "내 매매 적중률"을 `signal_outcome` 과 **같은 잣대**(hit=α≥0 & 상승, `isHit` 재사용)로 측정. **봇·VirtualTradeHistory·주문 경로 완전 무접촉(기록 전용)**, 산식·임계 무변경. 인계 문서 `docs/HANDOFF_JOURNAL.md`. 커밋: Phase1 `58b968d`+`a2b0e23`(전 세션) → Phase2 `ba675a78` → Phase3 `a0217149`(백)+`90e688d9`(프론트).

1. **Phase 1**: V43 `manual_trade_journal` + 엔티티/리포지토리 + `ManualTradeJournalService`(recordBuy 스냅샷 12필드 자동 — 각 소스 best-effort 실패=null §4c, 재료 read-only §4b) + `/api/manual-journal` CRUD(소유 검증). v1 = 전량 매도 가정.
2. **Phase 2**: V44 `bm_price_at_buy`(매수 시점 KOSPI — alpha용) + **19:40 평가 배치**(멱등 UPDATE, 시세 미확보=skip 재시도) + `GET /stats`(적중률·평균α·실현승률 + RSI70/재료 breakdown, 표본0=null·n<10=insufficientSample §4c).
3. **Phase 3**: 섹터 집중 경고 API(열린 저널+봇 포지션 read-only §4d, 매핑 밖=mapped:false, **경고만·차단 없음**) + 프론트 — `ManualJournalModal`(진입점 2곳: 결론카드·체크리스트 모달, 새 라우트 없음) · 매매 탭 '📔 수동 매매'(`ManualJournalSection`) · `SignalHistorySection` 내 매수/매도 마커 병기.

검증: 백엔드 전체 test + 컨텍스트 스모크 green, 프론트 vitest 181 + build green. migrationTest(V43/V44)는 CI 게이트(로컬 Docker 미기동). 순수함수 테스트 = assembleSnapshot/fiveDayReturn/realizedPct/evaluate/computeStats/computeSectorExposure + ManualJournalModal.test.js.

---

## 20. 관련 문서 인덱스

- `CLAUDE.md` — 작업 지침 + 불변식(1차 출처)
- `VERIFICATION_BACKLOG.md` — 검증/개선 티켓: P2-12 차트 백테스트(**승격불가 기록**)·P2-13 NXT청산(**진단 종결·2026-09-14 재개봉**)·P3-1 멀티인스턴스 락(부분해소)·**P3-2 signal unique(V36 해소)**·P3-3 growth nullable·**P0-pykrx(KIS 지수전환 해소)**·**P3-4 ticker_list reconstructed**·**P3-5 간밤 미국장 tilt 캘리브레이션**·**P1-6 4카테고리 적중률 캘리브레이션(★수급 역상관 확정)**·**P2-14 종합 판단 보드(B안, Phase1+2-A 완료)**·**P2-15 차트신호/종합 중복 통합(2단계)**·**P2-16 섹터강도 perf(병렬+워밍, 해소)**·**P2-CAT1 재료 배치 프롬프트(N종목 1콜=RPM↓)**·**P2-CAT2 Gemini 소비자 우선순위(재료>AI전략)**·**P2-CAT3 보드 재료 일괄 워밍(rate 게이트)**·**P3-7 매크로 tilt 캘리브레이션/승격(V39 스냅샷 축적 중)**
- `MARKET_INDICATORS_API.md` — 지표 API 레퍼런스
- (2026-07-06 정리) 구 주식 문서 5종(STOCK_PLATFORM_GUIDE·구 STOCK_AZ_FULL·SYSTEM_OVERVIEW·STOCK_PLATFORM_ONEPAGER·STOCK_SYSTEM_DOCUMENTATION)은 본 문서로 통합·삭제. 이제 주식 정본은 본 문서 단일.

> 본 문서는 2026-06-29 생성 · **2026-07-07(D) 갱신**(§19 하단 = 07-07(D) 세션: **📔 수동 매매 저널 V43+V44 Phase 1~3** — 매수 스냅샷·19:40 자동평가·stats·섹터경고·프론트 3면. 그 앞 07-07(B): 표시 전용 3작업 + 기관 수급 키 오타 정정 / 07-07: ATR 세트 V42·신호 이력/보드↔상세 네비). 정밀 cron/개수/필드는 코드가 출처이며, 산식·불변식은 CLAUDE.md를 따른다.
