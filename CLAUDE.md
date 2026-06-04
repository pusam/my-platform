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
- **python-backend(FastAPI)**: 테스트 설정 확인 후 명령 기입(있으면 `cd python-backend && pytest`).

> 정리: **백엔드(`./gradlew test`) + 프론트(`npm test`) 모두 "구현→테스트" 루프 가동 가능.** python-backend 만 미확인.

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

### 5. 인프라 관련
- 스케줄러 락(`SchedulerLockService`)은 **fail-open** (Redis SET NX EX, 멀티인스턴스). TTL < cron 으로 누락 시 다음 cron 재시도하는 구조.
- 봇은 **Clock 주입**으로 테스트 결정성 확보 — 시간 의존 로직에 `Clock`을 그대로 사용할 것.
- 캐시 계층 L1 Caffeine → L2 Redis(CacheWarmer 워밍) → L3 MariaDB. 워밍 잡은 `isMarketHours()` 밖이면 early-return.
- cron 시각들은 튜닝된 값이다. 근거 없이 바꾸지 말 것.

---

## 코드 위치 힌트 (탐색 시작점)
- 점수: `RecommendationService`, 결론: `StockConclusionService`, 체크리스트: `BuyChecklistService`
- 시그널 평가: `SignalOutcomeService` (19:30 배치, 3거래일 후)
- 시세: `StockPriceService`, KIS: `KoreaInvestmentService`
- 봇: `AutoTradingBotService`, 성과: `BotPerformanceService`
- 스케줄: `SchedulingConfig`, 락: `SchedulerLockService`
- 프론트 시간대 판정: `frontend/src/.../StockTradingDashboardV2.vue` (663~673줄 부근)
- 최대 화면: `StockDetailDashboard.vue` (~4,707줄)
