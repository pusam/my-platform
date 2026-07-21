# 주식 플랫폼 — Claude Code 작업 지침

한국 주식(KRX 정규장 + NXT 대체거래) 발굴/분석/모의·실전 자동매매 통합 개인 플랫폼.
Spring Boot(backend, 메인 API·스케줄러·매매봇) + FastAPI(python-backend, **pykrx 보조분석 전용** — 2026-06-11 재편)
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
- **python-backend(FastAPI)**: ✅ **pytest 도입(2026-06-29)** — 차트기법 지표 순수함수용. 테스트: `cd python-backend && pytest`(`pytest.ini` pythonpath=., `tests/test_indicators.py`). 그 외 도메인(regime 등)은 여전히 테스트 미도입.

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
- **수급 역상관 방어 — composite 총점 수급 캡(A안, P1-6, 2026-07-06, `RecommendationService.cappedSupply`)**: prod 실측(n=88)에서 수급 점수 단조 역상관 확정(0-4=67%→15+=35%). 가중치 재설계 대신 **최소·가역 방어** — composite 총점 raw 합산에서만 `supplyDemand`를 **min(sd, 10)** 로 상한. **불변식**: ① **표시값(`dto.supplyDemand`)·`validCount`·정규화 분모(80)·임계(75/55) 전부 불변** — 오직 raw 기여만 캡. ② **composite 경로 한정**(`getNormalizedTotal`/`toDto`/`calculate()` 필터) — 5트랙 발굴(💰수급 등)은 `getNormalizedTotal` 미사용이라 무영향(의도적 수급 랭킹 보존). ③ 종합판단 보드 수급 표시(≥10 경고)는 category 값 사용이라 무영향. ④ 가역 flag `recommendation.supply-demand-cap`(기본 10, 20↑/-1=비활성). 실측: 30일 SB 8행 중 7행(전부 삼성전기 009150·sd20·비수급base 40~45=수급의존 원형) 강등, 수급 분포 이분법(≤10 or 20)이라 캡10≡12. **캡값 재조정은 `SignalWeeklyReportService` 주간 리포트의 캡 전/후 성과 비교 대기**(SB 표본 8행/1종목으로 작음). 캡을 5트랙/보드 표시로 확대하거나 무캡 복귀 금지(근거는 주간 데이터).
- 임계: **STRONG_BUY ≥75 / BUY 55~74 / HOLD 40~54 / <40 제외**. total≥75 & valueStability≥12 → +2 보너스.
- 시그널 hit = **alpha_3d ≥ 0 AND pct_change_3d > 0** (3거래일), alpha 없으면 폴백 pct≥3%.
- 매매계획(결론카드 tradePlan): 손절/익절 % 는 **스윙 봇과 동기(-3%/+5%)**, "단기 강+밸류<4" 충돌 시 -2%/+3% 타이트. 봇 상수 바꾸면 `StockConclusionService.PLAN_*` 도 같이.
- `signal_outcome` 에 record 시점 스냅샷 누적: **V30 카테고리 점수 4종 + V31 재료(catalyst) + V32 시장 국면(regime) + V41 RVOL + V46 변동성국면(vol_regime) + V49 종목 추세채널 + V50 KOSPI 지수(0001) 추세채널(방향/위치/폭)**. 조건부 적중률(`/api/signal-outcomes/accuracy-by-band`) 검증용. NULL=미수집(집계 제외) 의미 유지할 것. 전부 **측정 전용 — 산식/봇/추천/regime 미편입**(P2-12 교훈).
  - **V50 지수 채널 소비자 제약(불변식)**: `KospiChannelService.currentKospiChannel()`(6h 캐시)는 **오직 `SignalOutcomeService.record()` 에서만 호출한다.** 보드/화면 등 **장중 실행 소비자를 붙이지 말 것** — 장중 호출이 미확정 당일봉(forming bar)으로 6h 캐시를 채우면 그 뒤 record 가 미확정 채널을 읽게 됨(캐시가 "시그널 기록 시점"의 일관된 상태만 담아야 함). 폭(width_pct)은 이상치 1봉이 폭을 좌우해(고저가 최대이탈 평행 채널) 사후 "폭 N% 이하 창" 필터 재집계용 — 지우지 말 것.
  - **지수 축 유효표본 = distinctDays 불변식(P3-11)**: regime/vol_regime/지수채널은 **지수 축**(같은 날 전 시그널이 동일값=비독립)이라 표본충분 판정은 **고유 signal_date 수(distinctDays≥10)** 로 한다 — 행 수(totalSignals)로 되돌리지 말 것(§4c 위장). 카테고리/재료(V30/V31)는 종목 축이라 행 수 유지. **P2-18(VKOSPI 게이트 승격)이 `volRegimeGroups` distinctDays 에 의존**.
- **과열(추격) 페널티 — `RecommendationService.overheatPenalty()` 단일 출처, 단계화(phase 38)**: RSI 70/75/80 → −3/−5/−8, 5일 누적 15/20/30% → −3/−5/−8, 볼린저 상단 돌파 −3. BULL 강세장에도 적용(섹터 가산과 별개). 임계 올리거나 단계 합치지 말 것 — "이미 많이 오른 종목" 추격 방지가 목적.
- **발굴 TOP10 정렬 tie-break(`recommendationComparator`) = 점수 desc → delta(오늘−어제) desc → changeRate asc**. 마지막이 **asc(덜 오른 종목 우선)** — 추격 인상 완화(phase 38). desc로 되돌리지 말 것.
- **BULL 섹터 가산은 하나만**: `scoreSectorMomentum` 의 +4 floor(추천 풀 안정)만 유지하고 `applyRegimeWeights` BULL 섹터 승수는 **1.0**(phase 38, 이중가산 제거). ×1.20 재도입 금지(오른 종목 섹터 점수 부풀림).
- **신규 진입 감점 임계(`applyNewEntryPenalty`/`newEntryPenaltyThreshold`)**: 어제 추천 풀 밖에서 갑자기 진입 + 5일 누적 급등 → technical −5(추격 방지). 임계 = **BULL 25% / 그 외 15%**(phase 38). BULL 은 5일 +15% 가 정상 추세에도 흔해(2026-05-14 46건 무차별 → STRONG_BUY 0) **완전 비활성(phase36)했다가, 극단(25%)만 잡도록 복원** — BULL 에서 다시 완전 비활성하거나 임계를 15%로 낮추지 말 것(정상 추세 풀 보존 vs 추격 방지 균형).

### 4b. 재료(catalyst) 태그는 산식 미편입 (의도)
- 네이버 뉴스 → Gemini 분류(`StockCatalystService`) → `stock_catalyst` **일캐시(종목·일자 1회)**. 용도는 **배지 표시 + 시그널 스냅샷(검증)뿐** — 재료별 적중률이 데이터로 검증되기 전엔 점수 산식에 넣지 말 것. 종합판단 보드도 이 캐시를 **read-only**(classify 호출 금지 — quota/스팸가드)로 소비.
- 알림: **신규 분류 시에만** 호재→시그널 채널 / 악재→리스크 채널 (캐시 히트 무알림 = 스팸 방지). 분류 실패(Gemini circuit open 등)는 **캐시 안 함** → 다음 기회 재시도.
- 모닝브리핑(07:30) 후행 워밍: BUY 컷(55) 이상 **상한 5종목** — Gemini quota 가드. 근거 없이 늘리지 말 것. 보드 종목 일괄 워밍은 **후속 백로그**(rate 제한 하에 도입 검토 — VERIFICATION_BACKLOG).
- **⚠ 뉴스 fetch 파이프라인 함정 3종(2026-07-01, 7일 연속 100% NONE 장애 — 세 원인이 겹쳐 있었음)**:
  1. **네이버 키 배선**: backend 는 `NAVER_CLIENT_ID/SECRET` 을 compose `environment:` 에 **명시 이중배선**(env_file 단독 의존 금지 — env_file 주입은 컨테이너 '생성' 시점 고정이라 `.env` 수정 후 `restart` 만 하면 반영 안 됨, **recreate 필요**). GEMINI 도 동일(backend `environment:` 에 명시).
  2. **URL 인코딩**: `NaverSearchService.buildSearchUrl` 은 **`URI` 반환**(String 금지). `RestTemplate.exchange(String)` 은 URL 을 URI 템플릿으로 보고 **재인코딩**(% → %25) → 한글 이중 인코딩 → 네이버가 무관 뉴스 반환 → 종목명 필터 전부 탈락 → NONE. `URI` 인자는 재인코딩 안 함. 회귀 테스트 `NaverSearchServiceTest`(query=%EC…, %25 없음).
  3. **§4c 하드닝**: `naver.isAvailable()==false`(소스 다운)면 `getCatalyst` 는 **null 반환(캐시 안 함)** — "뉴스 0건"을 NONE 으로 캐시하면 일캐시라 하루종일 재분류 못 함. 소스 가용일 때의 진짜 '뉴스 없음'만 NONE.
- **Gemini quota — 무료 티어 rate 제한(2026-07-01 해소)**: 모델 **`gemini-2.5-flash-lite`**(2.0-flash 종료, 무료 ≈15 RPM, URL 은 `${GEMINI_API_URL}` 로 오버라이드). `GeminiService.RateLimiter`(synchronized `acquire()` + 슬롯 예약)로 **전역 직렬화**, 간격 **4.5초**(≤~13 RPM, 15 미만). 이전 `enforceRateLimit` 은 volatile check-then-act(비원자적)라 동시 호출자(재료·AI전략·StockDetail 등) 버스트가 통과 → 429 였음 — **이 비원자 방식/2초 간격으로 되돌리지 말 것.** 503(구글 과부하)은 재시도 자동복구, 429는 rate 제한으로 방어 → 재료+AI전략 둘 다 정상. **후속(백로그)**: 배치 프롬프트(N종목 1콜=RPM 실질 감축, 무료 티어 최선책)·소비자 우선순위(재료>AI전략). ⚠ 프롬프트 프리픽스 캐싱은 **토큰 비용만↓**(RPM 무관)이라 429 완화엔 무효 — RPM 은 rate 제한/호출수 감축으로만.

### 4c. "데이터 없음"을 그럴듯한 값으로 위장하지 않는다 (2026-06-11 점검에서 3건 제거)
- **체결강도**: 소스는 **체결 API(FHKST01010300, inquire-ccnl)의 `tday_rltv`** — 현재가 시세 API(FHKST01010100)엔 체결강도 필드가 없다(여기서 읽으려던 게 항상-100% 버그의 근원). 미수집이면 **null 유지** → 프론트 게이지가 '-' + 시간대별 안내 표시. **null→100(균형) 강제 변환 금지.** 봇 `isVolumeIncreasing` 도 이 값에 의존.
- **시장 진단 condition 은 ADR(20일) 기반만**. 당일 등락비(`applyDailyRatio`)는 dailyRatio 표시값만 채운다 — ADR 임계(120/80/60)로 당일 등락비를 판정해 condition 을 덮어쓰면 평범한 상승일도 장중 '과열'로 오판.
- **섹터 거래대금은 실측만**(`SectorTradingService.resolveAccumulatedValue`): KIS 누적거래대금 → 현재가×거래량 폴백, 둘 다 없으면 스냅샷 제외. 시총×0.1% 같은 임시값 생성 금지. 휴장일엔 3분 크론 early-return(가드만, cron 시각 불변) — 휴장일 표시는 on-demand 수집의 마지막 거래일 실측이 담당.
- **python-backend 재편 (2026-06-11)**: 가짜 데이터 점검(5건) 후 자바 중복 라우터(네이버 크롤/yfinance/Gemini/스크리너) **전부 삭제** — 현재 역할은 `/api/v2/health` + `/api/v2/regime/current`(pykrx 시장 국면) 뿐. 새 기능은 "Java/KIS 로 비싼 일(히스토리·벌크)"일 때만 추가. 국면 규칙(v1): KOSPI 종가 vs MA60 + MA20 5거래일 슬로프 → BULL/BEAR/SIDEWAYS — 검증 데이터 쌓이기 전 임의 변경 금지. Java 는 `MarketRegimeClient`(1h 캐시, best-effort)로 소비, 미가용 시 regime_at_signal=NULL(미수집). **⚠ KOSPI 지수 데이터 소스(2026-06-30, P0-pykrx)**: pykrx 지수 엔드포인트(`get_index_ohlcv`)가 KRX 포맷 변경으로 전구간 빈값 → **regime/sector 는 `app/utils/index_source.fetch_kospi_daily`(Java `GET /api/market/index/kospi-daily`, KIS 일봉 진짜 종합지수 0001)로 소비**. classify 로직·국면 v1 불변. pykrx 종목 OHLCV(`get_market_ohlcv`)는 정상. pykrx 지수로 되돌리지 말 것(깨져 있음). ticker_list(reconstructed 백테스트)는 잔여 P3-4.
- **차트기법 신호 (2026-06-29)**: 펨코 추세추종 기법을 별도 모듈로 추가 — `/api/v2/chart/timing`(정배열·이격도·엔벨로프 눌림목·박스 타이밍)·`/api/v2/chart/sector-strength`(섹터 상대강도). 지표는 `app/indicators/*` 순수함수(pytest 有). ⚠ **산식 검증 완료 = 승격불가**(2026-06-30 P2-12 백테스트: 적중률 31%·Sharpe 0.08·**점수 역상관**) — 노출 위치(확정): **타이밍 = '오늘' 탭 '🪝 차트 신호 관찰' 별도 섹션**(`TodayBriefingTab.vue`, momentum 55컷 후보와 분리·**매수후보 아님**·**접기 기본**·**timingScore 미표시**(역상관이라 '높을수록 좋음' 오해 방지)), **섹터강도 = '발굴' 탭 상단 상시 배지**(`StockTradingDashboardV2.vue`). 봇/종합추천/매수후보 랭킹 편입 금지(P2-12 검증 결과 부진 → 승격 보류, **관찰용 유지** — '검증 전'으로 되돌리거나 '매수후보'로 승격·점수 노출 금지). Java 소비 = `ChartPatternClient`(best-effort) → `ChartSignalController`(`/api/recommendation/trend-pullback-top10`·`/sector-strength`, 응답 `unverified=true`).
- 신규 코드도 같은 원칙: 결측은 null/생략으로 정직하게. (단, RecommendationDto 의 -1=NA 같은 명시적 **표시 계약** sentinel 은 기존 규약 유지 — 엔티티 `recommendation_snapshot.growth/value_stability` 는 P3-3(V48, 2026-07-10)로 NULL=NA 전환 완료, 저장/복원 경계에서 변환.)

### 4d. 봇 실주문 정합성·체결 안전 (B2-A/B3-A, 2026-06-23)
- **재시작 reconciliation**(`AutoTradingBotService.reconcilePositionsWithKis`): 재시작 복구 시 REAL 모드면 KIS 실잔고(`realTradeService.getPortfolio`) vs 봇 추적 포지션을 대조해 orphaned(봇O/KIS X)·untracked(KIS O/봇X)를 **로그+텔레그램 경고만**. **자동 매매/정정 절대 금지**(KIS 계좌는 수동매매와 공유 가능 — untracked 가 곧 버그 아님). diff 는 순수함수 `computeReconciliation`.
- **매도 체결 확인**(`RealTradeService.confirmFill` → KIS `inquireDailyCcld` TTTC0081R, 읽기전용): 지정가는 부분/미체결 가능 — "주문 접수=체결"로 간주하면 잔량이 KIS orphan 됨. 매도 3경로(스캘핑·스윙·종가)는 `isSellConfirmedShort` 로 **확정 미달이면 포지션 유지(다음 사이클 재시도)**, 전량체결/조회실패(UNKNOWN)면 현행대로 제거. 판정은 순수함수 `resolveFill`.
  - **불변식**: ① 조회 실패=UNKNOWN=현행 제거(안전 기본값) 유지. ② VIRTUAL 무영향(REAL 한정). ③ `confirmFill` 은 클래스가 `@Transactional` 이라 **`@Transactional(NOT_SUPPORTED)`** 로 폴링 sleep 이 DB 트랜잭션을 잡지 않게 — 이 어노테이션 제거 금지.
  - Phase 2(미체결 잔여분 능동 취소 `order-rvsecncl`)는 미구현 — 주문변경 API라 모의계좌 검증 필수.
  - ⚠ `inquireDailyCcld` 응답 필드(`output1`/`odno`/`tot_ccld_qty`)·TR_ID 는 규격 기준 — 실전 첫 매도 로그로 1회 확인(틀려도 null→UNKNOWN→현행이라 안전).
- **KIS 주문 성공 + 로컬 DB 저장 실패 = 즉시 killswitch**(`triggerKillSwitchOnUncertainty`). KIS 비멱등이라 의도된 보수 동작 — 자동 재시도/롤백으로 바꾸지 말 것. killswitch 는 DB 기반이라 재시작해도 유지(매매 차단).
- **봇 진입 가격 sanity 가드**(`PriceSanityGuard.judge` + `passesPriceSanity`, 2026-07-06): 진입가가 전일 종가 대비 ±50% 초과면 주문 차단(가격은 미보정 — §3 불변식과 비충돌, 주문만 막음). **앵커는 반드시 StockPriceHistory(J=KRX 단독) 종가** — KIS 응답 역산(prdy_vrss)은 통배수(BATCH_SCALED) 오염 시 같이 스케일돼 무력이므로 앵커로 쓰지 말 것. 앵커 결측/노후(4일 초과)=UNKNOWN=통과 — 결측 근거 차단 금지(§4c). 임계 50%를 낮추면 상한가 추종 오탐, 앵커를 UN 소스로 바꾸면 가드 무력화. ⚠ 앵커 결측=UNKNOWN=통과는 fail-open이므로 신규 상장(히스토리 부재) 종목은 가드 사각 — 인지된 트레이드오프.
- **일일 손실 서킷브레이커**(`DailyLossBreakerService.judge` + `entryBlockedByDailyLossBreaker`, V38, 2026-07-06): 당일 봇 **실현손익 합산**(VirtualTradeHistory SELL 확정 기록만, REAL=999999/VIRTUAL=활성 가상계좌) ≤ -한도(기본 300,000원, `bot_config` 행 `daily_loss_breaker`) → **신규 진입만 차단, 기존 포지션 손절/청산/모니터링은 계속** — 이 **비대칭이 핵심**(출혈 확대만 방지, 탈출은 막지 않음). 매도 경로에 이 게이트 추가 금지. **불변식**: ① 기존 killswitch(불확실성·영구)·-3%/-1.5% 자산 킬스위치와 **별개 상태·로직 재사용 금지** — trippedDate 날짜 비교로 다음 거래일 자동 해제(리셋 잡 없음) + ADMIN 수동 해제(`POST /api/paper-trading/bot/daily-loss-breaker/release`). ② **확정 기록만 합산**(§4c) — 부분/미체결 잔량은 resolveFill 재시도 후 기록 시점 반영, 추정치 카운트 금지. ③ judge 판정 순서 = **BLOCKED-before-null**: 발동 후 조회 실패(DB 블립)에도 차단 유지, 미발동 조회 실패만 fail-open+RISK 알림(10분 스로틀). 순서 바꾸면 발동 상태가 블립에 풀림. ④ trip 영속 = **전용 bot_config 행 + 조건부 UPDATE**(rowsAffected==1일 때만 알림/감사 1회) — 'trading_bot' 행으로 옮기면 saveBotState load-modify-save 가 trippedDate 클로버. ⑤ VIRTUAL 계좌 해석은 **읽기 전용 조회만**(getOrCreateActiveAccount 금지 — 쓰기·중복 생성 race). ⑥ 수동 매수 엔드포인트(`/api/paper-trading/buy` 등)는 **의도적 미게이트**(봇 아님·운영자 판단).

### 5. 인프라 관련
- 스케줄러 락(`SchedulerLockService`)은 **fail-open** (Redis SET NX EX). TTL < cron 으로 누락 시 다음 cron 재시도. **단일 인스턴스 전제 — 매매봇(`AutoTradingBotService` 실주문)은 이 락 미사용(JVM 내 가드만). 멀티 인스턴스 확장 시 봇 크론에 fail-closed 락 필수**(fail-open으론 Redis 장애 시 중복 주문 못 막음).
- 봇은 **Clock 주입**으로 테스트 결정성 확보 — 시간 의존 로직에 `Clock`을 그대로 사용할 것.
- 캐시 계층 L1 Caffeine → L2 Redis(CacheWarmer 워밍) → L3 MariaDB. 워밍 잡은 `isMarketHours()` 밖이면 early-return. **단 시세(`StockPriceService.getStockPrice`)는 예외 — L1 로컬(ConcurrentHashMap) → DB(MariaDB)만, Redis 비경유**(시세 단일 경로 불변식). 전역 L2=Redis는 섹터/수급/AI전략 등 다른 도메인 캐시.
- cron 시각들은 튜닝된 값이다. 근거 없이 바꾸지 말 것.

### 6. 인증/세션·실시간 동시성 (2026-06-22 점검)
- **인증 필터는 Access Token 만 통과**: `JwtAuthenticationFilter` 는 `validateAccessToken`(type 검사) 사용 — `validateToken`(서명·만료만)으로 되돌리면 REFRESH 토큰을 Authorization 헤더로 보내 API 통과(보안 결함). 레거시 type=null 토큰은 여전히 허용.
- **라우터 가드는 RT 보존**(`frontend/src/main.js` beforeEach): AT(15분) 만료라도 RT(7일) 있으면 세션 유지(API 401 인터셉터가 자동 갱신). AT `exp` 만 보고 `UserManager.logout()`(=RT까지 삭제)으로 `/login` 튕기지 말 것 — "로그인 15분 뒤 풀림" 버그의 원인이었음.
- **authAPI 는 `apiClient` 경유**(raw axios 금지 — baseURL `/api`·인터셉터 일관). 로그인 실패는 200+success:false(401 아님)라 자동갱신 인터셉터 영향 없음.
- **로그아웃은 서버 토큰도 삭제**: `UserManager.logout()` 가 best-effort `POST /api/auth/logout`(raw fetch — api.js 순환참조 회피) 호출 → Redis AT/RT 삭제. 백엔드 `AuthController.logout`(SecurityContext username, 멱등).
- **`RealTimeDataCache.updateMinuteBar`**: `synchronizedList` 복합연산(get(size-1)/remove(0))은 `synchronized(bars)` 블록으로 보호 — 풀지 말 것(동시 틱 IndexOutOfBounds).

---

## 코드 위치 힌트 (탐색 시작점)
- 점수: `RecommendationService`, 결론+매매계획: `StockConclusionService`, 체크리스트: `BuyChecklistService`
- 발굴 5트랙(모두 `RecommendationService` + `/api/recommendation/*` + `RecommendationDto`): 저평가 `getValueTop10`/value-top10, 성장 `getGrowthTop10`/growth-top10, 낙폭과대 `getOversoldTop10`/oversold-top10, 실적 `getEarningsTop10`/earnings-top10, 수급 `getSmartMoneyTop10`/smartmoney-top10. 프론트는 `StockTradingDashboardV2.vue` 발굴 리스트 서브탭(`discoverListTabs`/`ensureDiscoverListLoaded`).
- 시그널 평가: `SignalOutcomeService` (19:30 배치, 3거래일 후) — 조건부 적중률 집계(`getAccuracyByBand`)도 여기
- 재료: `StockCatalystService` (V31, 네이버→Gemini→일캐시), API 는 `StockDetailController` `/api/stock/{code}/catalyst`
- 백테스트 API: `BacktestController` `/api/backtest/performance` (서비스는 기존 `BacktestService`)
- 모닝브리핑+재료워밍: `MorningBriefingService` (크론은 `StockAlertScheduler` 07:30)
- 시세: `StockPriceService`, KIS: `KoreaInvestmentService`
- 체결강도: `ScalpingAnalysisService` (ccnl 폴백 `getCcnlVolumePower`), 게이지: `VolumePowerGauge.vue`
- 시장 진단(ADR): `MarketTimingService`, 섹터 거래대금: `SectorTradingService`
- 시장 국면(V32): python `regime_service.py` ↔ Java `MarketRegimeClient`
- 차트기법 신호(미검증 베타): python 지표 `app/indicators/*`(순수, `tests/test_indicators.py`) + `chart_pattern_service.py`/`sector_strength_service.py` + `routers/chart_patterns.py`. 파라미터 `ChartPatternConfig`(config.py). Java `ChartPatternClient`/`ChartSignalRanker`(순수, `ChartSignalRankerTest`)/`ChartSignalController`. 프론트: **타이밍** = `TodayBriefingTab.vue`(오늘 탭 '차트 타이밍 매수 후보' 베타 섹션, `loadTimingCandidates`), **섹터강도** = `StockTradingDashboardV2.vue`(발굴 탭 상단 배지, `refreshSectorStrength`)
- 봇: `AutoTradingBotService`, 성과: `BotPerformanceService`
- 봇 실주문: `RealTradeService`(체결확인 `confirmFill`/`resolveFill`, KIS주문성공+DB실패→killswitch), 재시작 정합성 `AutoTradingBotService.reconcilePositionsWithKis`/`computeReconciliation`, KIS 체결조회 `KoreaInvestmentService.inquireDailyCcld`(TTTC0081R)
- 인증: `JwtAuthenticationFilter`·`JwtTokenProvider`(jwt-redis 모듈, 테스트 인프라 없음), `AuthController`/`AuthService`, 프론트 `utils/auth.js`·`utils/api.js`·`main.js`(라우터 가드)
- 스케줄: `SchedulingConfig`, 락: `SchedulerLockService`
- 프론트 시간대 판정: `frontend/src/.../StockTradingDashboardV2.vue` (663~673줄 부근)
- 최대 화면: `StockDetailDashboard.vue` (~4,707줄)
- 종목상세 주가 차트: **lightweight-charts 렌더러 `components/v2/HtsChart.vue`**(십자선·축눈금·줌/팬 내장, 2026-07-15 DIV/SVG 수제 차트에서 교체). 시리즈 변환 순수함수 `utils/htsChartData.js`(일봉 date/분봉 KST epoch time 규약). 데이터/계산 계층은 `composables/useChartCalculations.js`(채널·꼬리는 `utils/trendChannel.js`·`utils/candleAnatomy.js`, 백엔드 `TrendChannelCalculator`·V49 스냅샷과 산식 동기 — 변경 시 화면↔보드↔검증 어긋남). 당일 분봉('1일' 탭)=`IntradayChartService`(`/api/stock/{code}/intraday-candles`). 마이그레이션 이력: `docs/GUIDE_HTS_CHART_MIGRATION.md`.

## 프론트 IA (P-IA 3단계, 2026-06-11)
- 주식 허브 = `StockTradingDashboardV2` 단일 화면, **GNB 4탭: 오늘/시장/발굴/매매** (`DashboardHeader.vue`). 레거시 경로(/sector, /news, /ai-strategy 등)는 main.js 에서 탭 쿼리로 redirect — **새 주식 화면(라우트)을 만들지 말고 탭/서브탭에 흡수할 것.**
- 기본 진입은 **'오늘' 탭**(`TodayBriefingTab.vue` — 시장 한줄·🌙간밤미국장·매수 후보(55점 컷)·🪝차트신호관찰(접힘)·신뢰도·포지션. 하단 도구 바로가기 3버튼은 GNB 중복이라 2026-07-21 제거). `resolveInitialTab` 쿼리 없으면 today 고정 — 시각 기반 분기로 되돌리지 말 것. 탭 매핑 회귀 테스트: `StockTradingDashboardV2.ia.test.js`.
  - **시간대신호(장전/장후)·실시간수급(장중)·관심종목 = '오늘' 탭(2026-07-01, 발굴 슬림화 A 1단계)**: hub(StockTradingDashboardV2) 데이터를 **Vue 슬롯**(`#phase-signals`/`#watchlist`)으로 TodayBriefingTab에 주입(슬롯=hub scope라 데이터·CSS 유지, props/로딩 무변경). **발굴 목록은 5트랙 리스트 主로 슬림화**(차트신호 종목·관심종목 기본 접힘). 발굴↔오늘 위젯 되섞지 말 것. **차트신호 3 surface = 3 독립 엔진(중복 아님, P2-15 확정)**: ① 발굴목록 **'📐 차트 패턴'**=Java `ChartPatternService`(기하학 패턴, 유일출처) · ②③ **'🪝 차트 타이밍'**(오늘 관찰 + 종합판단 컬럼)=python `compute_timing`(같은소스, 백테스트 31%) · 🎯종합=`compositeRanking`(composite 5/5, 종합판단과 다른 엔진). **삭제·통합 금지**('패턴'=Java/'타이밍'=python 네이밍으로 구분만). 🎯종합은 종합판단 상위호환 아님(composite 손실이라 은퇴 금지).
- **역할 분리(2026-06-23~24): 오늘=모멘텀, 발굴=다각도 선별.** 모멘텀 종합추천 TOP10(`getTop5`)은 '오늘' 탭 전용 — **발굴에 모멘텀 TOP10 다시 넣지 말 것**(오늘과 중복 + "오른 종목만" 노출의 원인이었음).
- **차트 기법 통합 시 발굴/매수후보 스코어러는 항상 분리한다(2026-06-29).** 추세추종 기법은 성격이 다른 두 신호로 쪼개진다: ① 섹터 상대강도='덜 빠지는 섹터' = **발굴 탭 상단 배지**(유니버스 필터), ② 정배열+눌림목 진입 타이밍(momentum 아님, 추세 안의 mean-reversion, momentum 과 objective 정반대) = **'오늘' 탭 '차트 신호 관찰' 별도 섹션(매수후보 아님·접기 기본·점수 미표시)**. **기존 momentum 스코어러(`RecommendationService`/`getTop5`)에 욱여넣지 말고 별도 모듈**(python `app/indicators/*` + Java `ChartPatternClient`/`ChartSignalRanker`/`ChartSignalController`)로 둘 것. 타이밍은 momentum 매수후보를 **대체하지 않는 별도 관찰 리스트**. **P2-12 백테스트 결과 승격불가(2026-06-30: 적중률 31%·점수 역상관)** → 관찰용 유지, 실거래/봇/매수후보 승격 금지(`unverified=true`). 참고: momentum(`getTop5`)은 이미 '오늘' 탭 전용이라 발굴↔매매 공유 잔재 없음.
- **발굴 = 종합판단 중심 축소(2026-07-01)**: 노출 서브탭은 **🧭종합판단 + 백테스트뿐**(`discoverSubTabs`). **목록 5트랙·🎯종합·AI전략·스크리너·퀀트(TA)는 숨김** — 삭제 아님, **코드(렌더 블록/임포트/로더)·딥링크(`?sub=`) 보존**(복구: `discoverListVisible=true` + `resolveInitialDiscoverGroup` list 분기 되살리기 + `discoverSubTabs` 항목 추가). 목록 5트랙 종목은 **종합판단 union(발굴 트랙 포함) 토글에 이미 포함**돼 보드에서 비교 가능(빈-보드 폴백도 '목록 탭'→'발굴 트랙 포함' 토글로 유도). `discoverGroup` 항상 'deep'(목록 숨김). 이전 2단(목록+심화) 구조·9위젯 섹션 코드는 남아있고 게이팅으로 비노출. **🎯종합 vs 🧭종합판단**(둘 다 momentum, 종합=빠른목록/종합판단=3계층 비교표)은 여전히 코드 공존 — 🎯종합만 nav 에서 숨긴 것(은퇴 아님). 5트랙 백엔드는 모두 별도 엔드포인트·산식·성격(코드 유지):
  - 💎저평가 `getValueTop10` (PBR·ROE·부채·흑자) / 🚀성장 `getGrowthTop10` (매출·이익 성장률+PEG) / 📉낙폭과대 `getOversoldTop10` (RSI 과매도+MA20 낙폭+반등, 가격히스토리 스캔) / 💰실적 `getEarningsTop10` (흑자전환·이익급증) / 🏦수급 `getSmartMoneyTop10` (외국인·기관 순매수).
  - **lazy 로드**: `ensureDiscoverListLoaded(key)` 가 보고 있는 트랙만 호출(특히 낙폭과대 universe 스캔이 무거움), 각 30분 캐시. 5트랙 동시 eager 로드로 되돌리지 말 것.
  - 백엔드 5트랙 공통 골격: financial/price/투자자 데이터 broad 스캔 → 점수 → 상위30 `quickDangerCheck`(DART) → top10. 실적/수급은 기존 `scoreEarnings`/`scoreSupplyDemand` 산식 **재사용**(`calculateCategoryTop10`, 단일 출처). 성장 산식 `computeGrowthScoreParts`/낙폭 `computeOversoldScoreParts` 는 순수함수(테스트 有).
- 결론 카드(`StockConclusionCard.vue`): 매매계획(손절/목표가+MFE/MAE) · 점수대 적중률 · 재료 배지까지 표시. 데이터 없으면 각 블록 조용히 숨김(배지 생략)이 규약.
