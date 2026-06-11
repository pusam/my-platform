# 주식 플랫폼 — Claude Code 작업 지침

한국 주식(KRX 정규장 + NXT 대체거래) 발굴/분석/모의·실전 자동매매 통합 개인 플랫폼.
Spring Boot(backend, 메인 API·스케줄러·매매봇) + FastAPI(python-backend, pykrx·Naver 크롤링·보조분석)
+ Vue 3/Vite(frontend) + MariaDB + Redis(L2) + KIS REST/WS + Gemini + DART + 텔레그램(3채널).
Docker Compose: nginx · backend(8080) · python-backend(8000) · mariadb(3306) · redis(6379).

---

## 빌드 / 테스트 명령

- **백엔드(Spring Boot)**: `./gradlew test`  ← 자동 검증 루프는 여기서 돈다
  - 특정만: `./gradlew test --tests "*StockDetailServiceTest"`
  - 빌드: `./gradlew build`
  - ⚠️ Maven이면 `./mvnw test`로 교체.
- **프론트(Vue/Vite)**: ✅ **vitest 셋업 완료** (`vitest.config.js` — vite.config 와 분리, jsdom + @vue/test-utils).
  - 테스트: `cd frontend && npm test` (= `vitest run`) / watch: `npm run test:watch`
  - 빌드: `cd frontend && npm run build` (lint 스크립트는 아직 없음)
  - 테스트 파일 규약: `src/**/*.{test,spec}.js`. 브라우저 API 스텁은 `vitest.setup.js`.
- **python-backend(FastAPI)**: ❌ **테스트 없음 확인(2026-06-10)** — 테스트 파일 0개, requirements 에 pytest 없음, pytest.ini/pyproject 없음. 테스트 도입 시 여기 갱신.

> 정리: **백엔드(`./gradlew test`) + 프론트(`npm test`) 모두 "구현→테스트" 루프 가동 가능.** python-backend 는 테스트 미도입.

## 작업 완료 기준 (반드시 지킬 것)
1. 버그 수정은 **재현 테스트(고치기 전엔 실패)부터** 작성한 뒤 구현한다.
2. 변경 후 해당 영역 테스트(백엔드는 `./gradlew test`)가 **전부 green일 때만** 완료. 실패하면 멈추지 말고 고치고 다시 돌린다.
3. **요청 범위 밖 리팩토링 금지.** 눈에 거슬려도 별도 티켓으로 제안만 한다.
4. 가격/점수/시그널 산식을 바꿀 땐 기존 회귀 테스트가 깨지지 않는지 확인한다.

---

## 절대 건드리면 안 되는 설계 불변식 (의도된 것 — "버그"로 오인해 통일하지 말 것)

### 1. 시세는 단일 경로
- 모든 화면(`getQuick`/`getHeavy`/목록)은 공용 `StockPriceService.getStockPrice()`를 경유한다.
- **병렬 시세 호출 경로를 새로 만들지 말 것.** 화면 간 가격 불일치는 여기서 갈라질 때 생긴다.

### 2. 시간대 경계는 의도적으로 분리되어 있음
- **표시/추천/수급/캐시워밍 = NXT 기준 08:00~20:00**
- **봇/섹터/정규장 판정 = KRX 기준 09:00~15:30(또는 15:40)**
- 이 둘을 하나로 "통일"하지 말 것. 경계 구간(08~09시, 15:30~20:00) 동작은 명세대로다.

### 3. 가격 이상치 가드는 로깅만, 보정 안 함
- `warnIfPriceOutlier`는 이상치를 **ERROR 로깅 + KIS raw 기록**만 하고 **가격은 미보정**이다(정상 종목 훼손 방지). 이 "미보정" 동작을 유지할 것.

### 4. 점수/시그널 산식 (기준값)
- 종합추천: 핵심 4카테고리(earnings/supplyDemand/technical/sectorMomentum) ×20 = raw80 → normalize 0~100, **validCount≥3**(coverage 75%) 이어야 채택.
- 임계: **STRONG_BUY ≥75 / BUY 55~74 / HOLD 40~54 / <40 제외**. total≥75 & valueStability≥12 → +2 보너스.
- 시그널 hit = **alpha_3d ≥ 0 AND pct_change_3d > 0** (3거래일), alpha 없으면 폴백 pct≥3%.
- 매매계획(결론카드 tradePlan): 손절/익절 % 는 **스윙 봇과 동기(-3%/+5%)**, "단기 강+밸류<4" 충돌 시 -2%/+3% 타이트. 봇 상수 바꾸면 `StockConclusionService.PLAN_*` 도 같이.
- `signal_outcome` 에 record 시점 스냅샷 누적: **V30 카테고리 점수 4종 + V31 재료(catalyst)**. 조건부 적중률(`/api/signal-outcomes/accuracy-by-band` — 점수구간/카테고리강세/재료방향별) 검증용. NULL=미수집(집계 제외) 의미 유지할 것.

### 4b. 재료(catalyst) 태그는 산식 미편입 (의도)
- 네이버 뉴스 → Gemini 분류(`StockCatalystService`) → `stock_catalyst` **일캐시(종목·일자 1회)**. 용도는 **배지 표시 + 시그널 스냅샷(검증)뿐** — 재료별 적중률이 데이터로 검증되기 전엔 점수 산식에 넣지 말 것.
- 알림: **신규 분류 시에만** 호재→시그널 채널 / 악재→리스크 채널 (캐시 히트 무알림 = 스팸 방지). 분류 실패(Gemini circuit open 등)는 **캐시 안 함** → 다음 기회 재시도.
- 모닝브리핑(07:30) 후행 워밍: BUY 컷(55) 이상 **상한 5종목** — Gemini quota 가드. 근거 없이 늘리지 말 것.

### 4c. "데이터 없음"을 그럴듯한 값으로 위장하지 않는다 (2026-06-11 점검에서 3건 제거)
- **체결강도**: 소스는 **체결 API(FHKST01010300, inquire-ccnl)의 `tday_rltv`** — 현재가 시세 API(FHKST01010100)엔 체결강도 필드가 없다(여기서 읽으려던 게 항상-100% 버그의 근원). 미수집이면 **null 유지** → 프론트 게이지가 '-' + 시간대별 안내 표시. **null→100(균형) 강제 변환 금지.** 봇 `isVolumeIncreasing` 도 이 값에 의존.
- **시장 진단 condition 은 ADR(20일) 기반만**. 당일 등락비(`applyDailyRatio`)는 dailyRatio 표시값만 채운다 — ADR 임계(120/80/60)로 당일 등락비를 판정해 condition 을 덮어쓰면 평범한 상승일도 장중 '과열'로 오판.
- **섹터 거래대금은 실측만**(`SectorTradingService.resolveAccumulatedValue`): KIS 누적거래대금 → 현재가×거래량 폴백, 둘 다 없으면 스냅샷 제외. 시총×0.1% 같은 임시값 생성 금지. 휴장일엔 3분 크론 early-return(가드만, cron 시각 불변) — 휴장일 표시는 on-demand 수집의 마지막 거래일 실측이 담당.
- **python-backend 도 동일 원칙** (2026-06-11 점검에서 5건 제거): 스크리너 가짜 펀더멘털(등락률 선형식 PER/ROE/PEG 생성), 연속매수일 날조(max(3,8-i)), adr=50 상수, Gemini aiScore 기본 50, 선물 price="0" — 전부 제거/None 화. python 스크리너는 **모멘텀 후보만**(dataBasis=MOMENTUM_ONLY), 실제 재무 스크리닝·연속매수·ADR 은 Java 가 정답 소스. 참고: python-backend 는 현재 활성 소비자 없음(프론트 v1 통합, nginx /api/v2 라우팅만 유지).
- 신규 코드도 같은 원칙: 결측은 null/생략으로 정직하게. (단, RecommendationSnapshot.growth 의 -1=NA 같은 명시적 sentinel 은 기존 규약 유지.)

### 5. 인프라 관련
- 스케줄러 락(`SchedulerLockService`)은 **fail-open** (Redis SET NX EX). TTL < cron 으로 누락 시 다음 cron 재시도. **단일 인스턴스 전제 — 매매봇(`AutoTradingBotService` 실주문)은 이 락 미사용(JVM 내 가드만). 멀티 인스턴스 확장 시 봇 크론에 fail-closed 락 필수**(fail-open으론 Redis 장애 시 중복 주문 못 막음).
- 봇은 **Clock 주입**으로 테스트 결정성 확보 — 시간 의존 로직에 `Clock`을 그대로 사용할 것.
- 캐시 계층 L1 Caffeine → L2 Redis(CacheWarmer 워밍) → L3 MariaDB. 워밍 잡은 `isMarketHours()` 밖이면 early-return. **단 시세(`StockPriceService.getStockPrice`)는 예외 — L1 로컬(ConcurrentHashMap) → DB(MariaDB)만, Redis 비경유**(시세 단일 경로 불변식). 전역 L2=Redis는 섹터/수급/AI전략 등 다른 도메인 캐시.
- cron 시각들은 튜닝된 값이다. 근거 없이 바꾸지 말 것.

---

## 코드 위치 힌트 (탐색 시작점)
- 점수: `RecommendationService`, 결론+매매계획: `StockConclusionService`, 체크리스트: `BuyChecklistService`
- 시그널 평가: `SignalOutcomeService` (19:30 배치, 3거래일 후) — 조건부 적중률 집계(`getAccuracyByBand`)도 여기
- 재료: `StockCatalystService` (V31, 네이버→Gemini→일캐시), API 는 `StockDetailController` `/api/stock/{code}/catalyst`
- 백테스트 API: `BacktestController` `/api/backtest/performance` (서비스는 기존 `BacktestService`)
- 모닝브리핑+재료워밍: `MorningBriefingService` (크론은 `StockAlertScheduler` 07:30)
- 시세: `StockPriceService`, KIS: `KoreaInvestmentService`
- 체결강도: `ScalpingAnalysisService` (ccnl 폴백 `getCcnlVolumePower`), 게이지: `VolumePowerGauge.vue`
- 시장 진단(ADR): `MarketTimingService`, 섹터 거래대금: `SectorTradingService`
- 봇: `AutoTradingBotService`, 성과: `BotPerformanceService`
- 스케줄: `SchedulingConfig`, 락: `SchedulerLockService`
- 프론트 시간대 판정: `frontend/src/.../StockTradingDashboardV2.vue` (663~673줄 부근)
- 최대 화면: `StockDetailDashboard.vue` (~4,707줄)

## 프론트 IA (P-IA 3단계, 2026-06-11)
- 주식 허브 = `StockTradingDashboardV2` 단일 화면, **GNB 4탭: 오늘/시장/발굴/매매** (`DashboardHeader.vue`). 레거시 경로(/sector, /news, /ai-strategy 등)는 main.js 에서 탭 쿼리로 redirect — **새 주식 화면(라우트)을 만들지 말고 탭/서브탭에 흡수할 것.**
- 기본 진입은 **'오늘' 탭**(`TodayBriefingTab.vue` — 시장 한줄·매수 후보(55점 컷)·신뢰도·포지션·도구 바로가기). `resolveInitialTab` 쿼리 없으면 today 고정 — 시각 기반 분기로 되돌리지 말 것. 탭 매핑 회귀 테스트: `StockTradingDashboardV2.ia.test.js`.
- 결론 카드(`StockConclusionCard.vue`): 매매계획(손절/목표가+MFE/MAE) · 점수대 적중률 · 재료 배지까지 표시. 데이터 없으면 각 블록 조용히 숨김(배지 생략)이 규약.
