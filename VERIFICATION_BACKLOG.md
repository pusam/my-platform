# 검증 / 개선 백로그

각 티켓은 그대로 Claude Code에 던질 수 있게 **문제 / 합격 기준 / 테스트**로 구성.
위에서부터 순서대로 처리 권장. P0가 실제 버그, P2는 정합성/개선.

---

## P-IA. 프론트 화면 정보구조(IA) 재설계 — ✅ 완료 (2026-06-05)
- **목표**: GNB를 **시장(거시) / 발굴(추천·전략) / 매매(봇·페이퍼)** 3탭으로 재정의, 글로벌 통합, 종목 상세 요약/근거/심화 3섹션화.
- **1단계** `753cadd`: `GlobalFuturesPage` embedded prop(자체 nav 숨김). 행동테스트 3.
- **2단계** `04371d9`: GNB premarket/research/global → market/discover/trade. 단일 라이브 패널 내 **블록별 탭 게이팅**
  (대량 이동 없이 소스 순서=탭별 표시 순서). `isLiveTab`(시장||발굴) computed 로 폴링/스태거/freshness/visibility 게이트
  일괄 치환 → **폴링·탭숨김정지·스태거 보존**. mapLegacyTab/resolveInitialTab/Sub 재정의 + main.js redirect 동기화.
  글로벌은 시장 서브탭에 `defineAsyncComponent`로 임베드(별도 청크 유지). 매핑 테스트 18.
- **3단계** `b36b7b6`: 상세 요약(헤더+결론+QuickSummary)/근거(헤드라인+리스크+main-grid)/심화(볼륨·지지저항·패턴·관련)
  3섹션. `DetailSection.vue`(v-show 마운트 유지=API 타이밍 보존)로 심화 기본 접힘. 행동테스트 5.
- **불변식 보존**: 백엔드/시세경로/산식 무변경, 프론트 currentPhaseKey(08~20)와 KRX(09~15:40) 분리 유지(불변식2),
  스태거/폴링/useAutoRefresh 동작 보존. 전체 69 tests green, build 통과.
- **남은 폴리시(선택)**: ① currentPhaseKey "강조 전환" 세부 UX(장중 실시간 우선/장후 성과 강조)는 현재 phase v-if 유지로
  적용 완료(`896fefb` — phase-strip 배너 + 패널 phase 클래스로 장중/장후 강조). ② Peer 카드 심화존 이동 완료(`6389972`).
  ③ **운영 시각 QA 필요**(탭 전환/딥링크/장중 폴링 실동작은 런타임 확인 권장) → 체크리스트: `docs/OPS_QA_IA_REDESIGN.md`.

---

## P0-1. 가격 outlier 가드 맹점 (실제 버그)
- **문제**: `warnIfPriceOutlier`가 "현재가가 당일 [저가~고가]±10%"로만 보는데, 현재가·고가·저가가 **일괄 ×10**되면 현재가가 여전히 범위 안이라 가드가 발화하지 않는다. 당일 범위 조건은 일괄 스케일링에 무용지물.
- **합격 기준**:
  1. 현재가·고저가가 일괄 ×10된 케이스 → 가드 발화(ERROR 로깅).
  2. 정상 종목(소폭 등락) → **절대 미발화**.
  3. 전일 종가 0/null → 예외 없이 skip.
  4. 가격은 여전히 **미보정** (로깅만).
- **구현 가이드**: "현재가 ≥ 전일 종가 × N(예: 5)" OR 조건을 기존 범위 조건에 추가.
- **테스트**: 위 4케이스 단위테스트 + 경계값(×4.9 미발화 / ×5.1 발화) + 기존 `StockDetailServiceTest` 회귀.

## P0-2. ×10 근본원인 조사 (수정 아님, 진단)
- **문제**: 코드엔 ×10 연산이 없는데 일부 종목만 ×10. KIS `FHKST01010100` + `UN`(KRX+NXT 통합시세) 사용 중. NXT/이중상장 종목의 통합시세 필드 규약 차이 의심.
- **합격 기준**: ×10 발생 종목 목록을 뽑고, 그 종목들이 NXT/이중상장/특정 구간에 몰리는지 분류. "응답 자체가 ×10" vs "필드 매핑 오류" 중 어느 쪽인지 KIS raw 로그로 확정.
- **산출물**: 진단 스크립트/테스트 + 분류 결과 요약. (근본 보정은 결과 확인 후 별도 티켓)
- **✅ 결론 (2026-06-04, 운영 90일 실측)**: `CURRENT_FIELD_OUTLIER 0건` (현재가가 자기 당일 밴드 밖인 행이
  전무) → **×10 현재가 오염 미발생 확정**. MIN 앵커 스캔의 대량 매칭은 전부 false positive(저측 글리치 앵커
  + 동결/스테일 피드 + 실제 5×+ 랠리)였음. 부수 관찰(동결 피드·저측 글리치·손상 등락률 900%)은 ×10과 무관한
  별개 현상 → 필요 시 별도 티켓. 상세: `docs/PRICE_X10_DIAGNOSIS_P0-2.md` §6.

---

## P1-3. 시세 단일 경로 회귀 가드
- **문제**: 화면 간 가격 불일치는 시세 경로가 갈라질 때 생긴다. 지금은 공용 `getStockPrice()`로 통일됐으나 회귀 방지 장치가 필요.
- **합격 기준**: `getQuick`/`getHeavy`/목록이 모두 `StockPriceService.getStockPrice()`를 경유함을 보장하는 테스트(별도 시세 fetch 추가 시 실패).
- **✅ 완료 (2026-06-04)**: `StockDetailServiceTest` 에 `SinglePriceSourceGuard` 추가. getQuick 표시가격 =
  stockPriceService sentinel 그대로 + `verify(kisService, never()).getStockPrice` (병렬 KIS 표시가격 fetch
  추가 시 실패), getHeavy 도 `verify(stockPriceService, atLeastOnce()).getStockPrice` 경유 확인. 핫패스 무변경.

## P1-4. 시그널 hit 산식 테스트
- **문제**: `SignalOutcomeService`의 hit 판정 검증 부족.
- **합격 기준**: alpha≥0 & pct>0 → hit / 둘 중 하나라도 미달 → miss / alpha null → 폴백 pct≥3% / pct 정확히 0·경계값 처리 확인.
- **✅ 완료 (2026-06-04)**: hit 공식을 `SignalOutcomeService.isHit(alpha, pct)` 순수 메서드로 분리(동작 보존,
  인라인 대체) + `SignalOutcomeHitTest` (alpha=0·pct=0·pct=2.99/3.00 경계 등 9케이스).

## P1-5. 추천 점수 경계·coverage 테스트
- **합격 기준**: validCount<3 → 미채택 / 임계 40·55·75 경계 분류 정확 / total≥75 & value≥12 → +2 보너스 적용 / MarketRegime 승수(BULL·BEAR) 반영 확인.
- **✅ 완료 (2026-06-04)**: `RecommendationService` 의 순수 산식을 package-private 로 분리(동작 보존):
  `normalizeScore`(경계), `validCount`(≥3 컷 분모), `strongValueBonus`(+2), `applyRegimeWeights`(BULL/BEAR
  승수 + clamp), `MarketRegime` enum. `RecommendationScoreTest` — raw32/44/60→40/55/75, validCount cap,
  보너스 75&12→77(상한 100), BULL 섹터×1.2/BEAR 실적×1.2 clamp 등. 기존 `toDto`/regime weighting 은 위
  헬퍼로 위임(분기·태그 동작 동일).

---

## P2-6. 봇 트랙 수 문서·코드 정합
- **문제**: 문서엔 "3봇" "4트랙"이 혼재하고 청산봇(`executeClosing*`)은 주석처리(비활성). 셈법 3종이 서로 안 맞음.
- **합격 기준**: 실제 active 트랙을 코드 기준으로 확정해 주석/문서에 명문화. 청산봇 재활성 여부는 결정 사항으로 분리 제안.
- **✅ 완료 (2026-06-04)**: 코드 기준 = **활성 5트랙**(스캘핑 매수/매도/청산 + 스윙 매수/매도) + **비활성 2**
  (종가 매수/매도, `@Scheduled` 주석). `AutoTradingBotService` 헤더에 트랙 표 명문화 + `STOCK_AZ_FULL.md`
  카운트("4트랙"·"6크론") 정정. `AutoTradingBotTrackTest` 리플렉션 가드(@Scheduled 5개 고정 — 켜/끄면 실패).
  청산봇 재활성은 별도 결정 사항으로 분리(거래시간 연장 후 충돌/데이터신뢰성 재설계 전제).

## P2-7. isRegularSession 종료 시각
- **문제**: `MarketCalendarService.isRegularSession`이 09:00~**15:40**인데 KRX 정규장은 09:00~15:30. 종가단일가 버퍼 의도일 수 있음.
- **합격 기준**: 의도 확인 후 코드 주석 + 경계 테스트(15:30·15:31·15:40·15:41) 추가.
- **✅ 완료 (2026-06-04)**: 15:40 = **종가 단일가매매 버퍼(의도된 값)** 주석 명시. 테스트 가능
  `isRegularSession(date,time)` 오버로드 추가. `MarketCalendarServiceTest` 경계 09:00/08:59·15:30/15:31·15:40/15:41.

## P2-8. MarketCalendar 음력 공휴일
- **문제**: 고정 공휴일만 처리, 음력(설/추석 등) 누락 가능.
- **합격 기준**: 음력 공휴일 반영 + 해당 일자 isRegularSession=false 테스트.
- **✅ 완료 (2026-06-04)**: `KOREA_LUNAR_DERIVED_HOLIDAYS`(설날·추석·부처님오신날 양력환산, 2025~2027)
  추가 → `isMarketClosed` 반영. **⚠ 매년 갱신 필요**(미수록 연도는 거래일로 처리=안전 열화) 주석.
  테스트: 2026 설날(2/17)·추석(9/25)·2027 부처님(5/13) 휴장 + 연휴 다음 평일 개장.

## P2-9. 시간대 경계 동작 특성화 테스트
- **문제**: NXT(08~20) vs KRX(09~15:30) 분리로 경계 구간(08~09, 15:30~20:00)에서 "화면 during인데 봇/섹터 데이터 비어있음" 같은 동작이 구조적으로 발생.
- **합격 기준**: 경계 시각별 각 모듈 동작을 characterization 테스트로 고정 → 의도된 동작임을 문서·테스트로 못박기.
- **✅ 완료 (2026-06-04)**: `MarketCalendarServiceTest` NxtVsKrxGap — 08:30(NXT_PREMARKET)·16:00(NXT_AFTERHOURS)
  에 KRX 정규장 false, 10:00 중첩에 둘 다 open 으로 **의도된 갭을 고정**. NXT 세션 경계는 기존
  `PriceScalingDiagnosticServiceTest.sessionBoundaries`(`sessionOf`)가 이미 고정 — 교차 검증.

## P2-0. (선행) 프론트 vitest 셋업
- **✅ 완료 (2026-06-04)**: `frontend/vitest.config.js`(vite.config 와 분리, jsdom + @vue/test-utils 2 + globals),
  `vitest.setup.js`(matchMedia·IntersectionObserver 스텁), 스크립트 `npm test`/`test:watch`,
  첫 실제 테스트 `marketFormatters.test.js`(16) + 마운트 스모크 `__tests__/setup.smoke.test.js`(3) = **19 green**.
  P2-10 컴포넌트 분리의 "동작 보존 테스트 먼저" 전제 충족. CLAUDE.md 명령 갱신.

## P2-10. StockDetailDashboard 분리 (개선)
- **문제**: 4,707줄 단일 컴포넌트.
- **합격 기준**: 동작 보존(스냅샷/행동 테스트 먼저) 후 탭/위젯 단위 분리. 동작 변화 0. (선행 P2-0 vitest 셋업 ✅)
- **🔄 1차 분리 완료 (2026-06-04)**: 자족적 위젯 2개 추출 — `VolumeProfileCard.vue`(VP 그리드/POC/VA),
  `SupportResistanceCard.vue`(지지·저항/강도라벨). 헬퍼·스타일 동반 이동, 부모 v-if 조건 그대로 유지.
  **행동 테스트 먼저** 작성(VolumeProfileCard 5 + SupportResistanceCard 5 = 10 green), `npm run build` 통과.
  부모 **4,707 → 4,508줄**(−199). 동작 변화 0(추출만).
- **🔄 2차 분리 완료 (2026-06-05)**: `QuickSummaryBar.vue`(핵심 요약 카드 — RSI/20일선/외국인/기관/리스크/AI
  6칸 + 스켈레톤) 추출. getQs* 헬퍼 11개 동반 이동, `getRecommendationLabel`은 부모 타 용도 존재해 소형 복제.
  행동 테스트 8 green(반올림·라벨·null 안전·loading 스켈레톤·!hasData 미렌더), 빌드 통과.
  부모 **4,508 → 4,342줄**(−166).
- **🔄 3차 분리 완료 (2026-06-05)**: `PeerComparisonCard.vue`(섹터 Peer Group — PBR 막대/색상구간/섹터평균선)
  추출. `getPeerBarWidth`/`getPeerBarClass` 동반 이동, 전용 클래스(.peer-*)라 스타일 충돌 無. 부모 v-if 유지.
  행동 테스트 6 green(current 강조·bar 너비·색상구간·평균선 조건). 부모 **4,342 → 4,187줄**(−155). 누적 −520줄.
  ※ 재무(financial) 섹션은 `.section-header`/`.positive`(공유 스타일 29회) 의존이 커 시각 변화 위험 → 보류.
  잔여(투자자 동향 탭 등)는 동일 패턴 점진 분리.

## P2-11. 스테일/글리치 피드 가드 (진단·로깅, 데이터 품질)
- **배경**: P0-2 운영 90일 실측에서 ×10 현재가 오염은 0건이었으나, 그 과정에서 ×10 과 무관한
  데이터 품질 이슈가 드러남 (상세: `docs/PRICE_X10_DIAGNOSIS_P0-2.md` §6).
- **문제 (3종, 모두 ×10 가드가 못 잡음)**:
  1. **동결/스테일 피드** — 거래정지/상폐/스테일 종목이 같은 현재가로 수주간 3분마다 반복 적재
     (예: 001230 이 11,400 에 2026-04-24~05-28 고정). 화면엔 "정상 시세"처럼 보임.
  2. **저측 글리치** — 단발성 ×0.2 수준(정상가의 ~1/5) 행. `MIN()` 기반 분석을 오염시켜 false positive 유발.
  3. **손상 등락률 필드** — `prdy_ctrt` 가 일일 변동제한(±30%)을 황당하게 초과 (예: 011930=900.00%).
     ※ P0-1 그물 2가 이미 ±31% 초과를 ERROR 로깅하나, 동결/저측 글리치는 미감지.
- **합격 기준**:
  1. **동결 감지** — 직전 N회(예: 동일 currentPrice 가 X분 이상/Y틱 연속) 변화 0 이면 stale 의심 로깅
     (정규장 한정; 휴장/장외 동결은 정상이므로 제외). `StockStatusService` 의 거래정지/상폐 목록과 교차해
     "정지종목 동결=정상 / 활성종목 동결=이상" 구분.
  2. **저측 글리치 감지** — DB 앵커(직전 정상가) 대비 ≤0.2배 행을 ERROR 로깅 (P0-1 DB앵커 그물이 이미
     ≤0.2 조건 보유 → **신규 코드 불필요, 재현 테스트로 동작만 고정**).
  3. **손상 등락률** — P0-1 그물 2 회귀 테스트에 900% 같은 극단값 케이스 추가.
- **불변식 준수**: P0-1 과 동일하게 **로깅만, 가격 미보정**. 시세 단일 경로(`getStockPrice`) 변경 금지.
- **테스트**: 동결 N틱 경계(미발화/발화), 정지종목 동결=미발화, 저측 ×0.2 발화, 등락률 900% 발화.
- **비고**: 진단·관측 목적. 실제 차단/대체값 주입은 결과 확인 후 별도 티켓.
- **✅ 구현 완료 (2026-06-04)** — 로깅/관측 전용, 시세 핫패스(`getStockPrice`/`warnIfPriceOutlier`) 무변경:
  - `util/StaleFeedDetector` — 꼬리 동결 길이 계산(순수) + `StaleFeedDetectorTest`.
  - `PriceScalingDiagnosticService.scanStaleFeeds` — 정규장 동결 감지 + `StockStatusService` 활성/정지 교차
    (`ACTIVE_FROZEN`=이상 / `SUSPENDED_FROZEN`=정상) + 손상 등락률(±31% 초과) 보고. 정규장 한정.
  - `GET /api/diagnostics/stale-feeds?tickThreshold=20` 엔드포인트(읽기 전용).
  - 저측 글리치(×0.2)·손상 등락률(900%)은 P0-1 기존 그물이 이미 발화 → `StockPriceOutlierGuardTest`
    회귀 케이스로 동작 고정(신규 보정 코드 없음).
  - 전체 `./gradlew test` green.
  - **운영 점검 절차**: `docs/OPS_STALE_FEED_CHECK_P2-11.md` (엔드포인트 호출 → ACTIVE_FROZEN 판정 →
    거래정지 동기화 누락/피드 스테일 분기, 재배포 전 SQL 대체 포함).

---

## P3-1. 멀티 인스턴스 확장 시 봇 fail-closed 락 (설계 — **부분 해소 2026-06-29**, 확장 결정 시 잔여 착수)

> **✅ 부분 해소 (2026-06-29)**: 봇 크론 리더 선출 **`BotLeaderElectionService`**(Redis 리스 SET NX EX + 10s 하트비트, fail-CLOSED)
> 도입. **봇 크론 5개**(`executeScalpingBuyLogic`·`executeScalpingSellLogic`·`executeScalpingClearance`·`executeSwingBuyLogic`·`executeSwingSellLogic`)가
> `isLeaderForBot()` 통과해야 실행 → 멀티 인스턴스 중 리더 1개만 주문, Redis 장애 시 주문 중단. `SchedulerLockService`(fail-open)는
> 미변경(별개 메커니즘). 설정 `bot.leader-election.enabled`(기본 true, 단일+Redis미사용 환경은 false). 테스트 `BotLeaderElectionServiceTest`(2인스턴스/Redis다운/단일/bypass).
> **✅ 추가 해소 (2026-06-30, 작업2)**: 리더 전환 순간 중복 BUY 방지 — `BotOrderIntentService` + `bot_order_intent`(V35).
> 리더 A가 KIS BUY 쏜 직후(응답 전, 포지션 저장 전) 死 → B 승계 시 같은 (종목,BUY,거래일,시그널) 재매수를 **KIS 호출 직전 선기록 멱등키**로 차단
> (PENDING/DONE→SKIP, FAILED→재시도 허용). REQUIRES_NEW 라 주문 트랜잭션 롤백에도 키 생존. killswitch(KIS성공+DB실패)와 무충돌.
> **BUY 전용**(SELL은 보유수량 체크로 자연 멱등 + 작업1 청산 재시도와 충돌 회피). 테스트 `BotOrderIntentServiceTest`(페일오버 차단/재시도/완료).
> **잔여(미해소)**: ③ `RealTradeService` fencing 2차 방어의 **SELL 부분청산 과청산 가드**는 여전히 미구현 — 확장 결정 시 착수.

> **선결 조건**: 이 티켓은 **backend 멀티 인스턴스 배포를 결정하는 시점**에만 착수한다. 현재 `docker-compose.yml`
> backend = replicas 1(단일 컨테이너)이라 **실위험 없음** → 지금 락을 붙이면 단일 인스턴스에서 손해만 본다(아래 ④).
> 출처: 2026-06-08 코드 점검 ⑤ (`AutoTradingBotService`/`SchedulerLockService`/`RealTradeService` 실측).

- **문제 (멀티 인스턴스 시 실주문 중복 = 즉시 손실)**:
  1. `AutoTradingBotService`는 `SchedulerLockService`(분산락)를 **사용하지 않는다**. `@Scheduled` 봇 크론은 모든
     인스턴스에서 중복 실행된다. 중복 진입 방지는 **JVM 내 가드뿐**(`scalpingPositions.putIfAbsent` · `holdingCodes`
     보유체크 · 매도 쿨다운) → **인스턴스 간엔 무력**(메모리 맵은 JVM 로컬).
  2. `SchedulerLockService.tryLock`은 **fail-open**(Redis 비활성/예외 시 `return true`). **그대로 봇에 붙여도
     Redis 장애 시 모든 인스턴스가 통과** → 중복 주문을 못 막는다. 봇엔 **fail-closed 경로가 별도로 필요**.
  3. `RealTradeService.executeBuy`는 매수 직전 `getBalanceInfo(true)`(KIS 강제 재조회)로 보유를 확인하나 **멱등성
     키/주문 dedup 없음** + KIS 포트폴리오 반영 지연 → 두 인스턴스가 동시 평가하면 **둘 다 "미보유" → 중복 실주문**.

- **핵심 설계 — 매수/매도 비대칭(중요)**:
  - **매수(진입)는 fail-closed 안전**: 락 실패 시 그 사이클 skip → 다음 cron 재시도. 손실 없음. **여기에만 fail-closed.**
  - **매도/청산은 fail-closed 금물**: Redis 장애로 락 실패 시 매도를 skip하면 **손절/익절을 놓쳐 손실 확대**. 게다가
    중복 매도는 RealTradeService가 보유 0이면 거절하므로 대체로 멱등 → **매도엔 락 미적용(또는 fail-open 유지)**.
  - ⚠ **부분청산 과청산 위험(검토 필요)**: 매도를 fail-open(락 없음)으로 두면 두 인스턴스가 같은 종목 **익절 절반(부분청산)을
    동시 실행** → 의도(50%)보다 많이 팔리는 **과청산** 가능(전량 매도는 보유 0 거절로 멱등이라 안전, 부분 매도만 노출).
    부분 매도 한정 가드 검토: 종목별 in-flight 마커(`SET NX EX` per stockCode) 또는 "오늘 부분청산 1회" 수량 락.
  - **모의/실전 구분**: 스캘핑은 **모의 전용**(가상 포트폴리오) → 실손실 없으나 가상 중복 방지 위해 적용 권장.
    **실손실 위험은 스윙 매수(REAL 모드) + 청산봇(재활성 시)** 이 핵심.

- **합격 기준 (구현 시)**:
  1. `SchedulerLockService`에 **fail-closed 변형** 추가(예: `tryLockStrict` — Redis **예외 시 `false` 반환**).
     기존 `tryLock`(fail-open)은 다른 잡들이 쓰므로 **건드리지 않는다**.
  2. **진입 크론에만** strict 락: `executeScalpingBuyLogic` · `executeSwingBuyLogic` · (재활성 시)`executeClosingBuyLogic`
     → `tryLockStrict("bot:{strategy}-buy", ttl)` 실패 시 즉시 return(진입 skip). **매도/청산 크론엔 적용 금지.**
  3. **락 granularity**: 전략별 단일 락(`bot:scalping-buy`/`bot:swing-buy`/`bot:closing-buy`). TTL은 **cron 주기보다
     짧게**(누락 시 다음 cron 재시도) + **작업 1회 소요보다 길게**. `try/finally`로 `release()`.
  4. **단일 인스턴스/Redis 비활성 회귀**: Redis 비활성 환경에서 strict 락이 봇을 영구 차단하면 안 됨 → 정책 결정 필요.
     권장: **"멀티 인스턴스 = Redis 필수"를 기동 시 강제**(Redis 없으면 봇 비활성 또는 기동 실패)하고, strict는 Redis
     활성 전제. 단일 인스턴스(Redis 비활성)에선 기존 동작 그대로 유지.
  5. (선택, 2차 방어) `RealTradeService.executeBuy`에 **fencing/멱등성**: 종목별 in-flight 마커(`SET NX EX` per
     stockCode) 또는 주문 dedup 키 → 락 누수 시에도 동일 종목 중복 주문 차단.
  6. **검증**: 두 인스턴스 동시 구동 시나리오에서 **동일 종목 중복 실매수 0건**.

- **테스트**:
  - `tryLockStrict` 단위: 미잠김→true / 이미 잠김→false / **Redis 예외→false(fail-closed)** / (정책)Redis 비활성→해당 동작.
  - 진입 크론: 락 실패 시 **진입 skip(주문 0건)** — `Clock` 주입 + mock 결정론.
  - 매도 크론: strict 락 미적용(매도 skip 없음) 확인 — fail-closed 회귀 방지.
  - 단일 인스턴스 회귀: 락 획득 성공 시 기존 진입 동작 동일.

- **비고**: 코드 충돌이 아니라 **확장 안전성** 이슈. 현 단일 인스턴스에선 동작 정상.
  관련 주석: `SchedulerLockService`·`AutoTradingBotService` 클래스 Javadoc(2026-06-08 추가),
  `CLAUDE.md`/`STOCK_AZ_FULL.md`(§3.5)/`STOCK_PLATFORM_GUIDE.md`(§7)에 동일 가정 명시됨.

---

## P2-12. 차트 타이밍 스코어러 백테스트 (적중률/MDD 측정) — **검증 전 베타 한정** (2026-06-29 신규)

> **현황**: 펨코 차트 추세추종 기법(정배열·이격도·엔벨로프 눌림목·박스·섹터 상대강도)을 **별도 모듈**로 통합했다.
> 산식/가중치는 전부 **사후확증 예시 기반이라 승률 미검증** → 현재 노출은 **타이밍 = '오늘' 탭 매수후보 아래 '검증 전 베타'
> 별도 섹션**(momentum 55컷 후보와 분리·대체 아님) + **섹터강도 = '발굴' 탭 상단 배지**로만. **봇/종합추천/매수후보 랭킹에는
> 편입하지 않는다**(코드 가드: 응답 `unverified=true`, momentum 스코어러와 분리). **실거래 매수신호로 노출 금지**(베타 라벨 유지).

- **✅ 백테스트 구현 (2026-06-30, 작업1)**: `app/backtest/`(`cost`·`metrics`·`chart_backtest_service`) + `routers/chart_backtest.py`(`POST /api/v2/chart/backtest`). `chart_pattern_service.compute_timing` 추출해 **프로덕션과 동일 신호 재생**(단일 출처). 함정 방어 — look-ahead: `df.loc[:D]`(≤D)/평가 `df.loc[>D]` disjoint + `assert gen.index.max()<=D` / 진입 D+1 시가·청산 +3거래일 종가 / 비용: 수수료0.03%+세금0.18% flat + 슬리피지 0.15% 가격적용(보수) / hit=alpha≥0 AND pct>0(SignalOutcome 미러) / 생존편향: deployed 16섹터 메인 + `reconstruct_universe`(pykrx get_market_ticker_list) 시점복원 교차검증. 산출: 적중률/평균순손익/MDD/Sharpe + 섹터 spread·Spearman + tie-break 겹침. 순수함수 pytest `tests/test_backtest.py`.
  - **실행(서버 온디맨드)**: `docker compose run --rm python-backend pytest tests/test_backtest.py -v`(검증) → uvicorn 기동 후 `POST /api/v2/chart/backtest {start,end,mode,universe|sectors}` 1회 → 결과로 **승격 판정(사용자)**. **`unverified` 임의 변경 금지.**
- **❌ 백테스트 결과 = 승격 불가 (2026-06-30, deployed 16섹터 대표, 2026-01-02~05-30, 646신호)**:
  - **per-trade**: hitRate **30.8%** · avgNet **+0.53%** · Sharpe **0.08** · winRate(net>0) 51.2% · profitFactor 1.25 · avgWin +5.19%/avgLoss −4.38% · worstTrade −21.8% · 손실 −5%초과 16.6%.
  - **점수분해 = 역상관(필터로 못 살림)**: score1 37.8%(n=333) > score2 24.5% > score3 21.2% > score4 21.1% > score5 26.7%(n=30). **고점수일수록 적중률↓** → min_score 상향 필터로 개선 불가.
  - **MDD**: 순차 풀베팅 복리 99.4%는 **포지션사이징 없는 산식 아티팩트(폐기)**. 현실적 K=10슬롯 균등배분 청산-실현 기준 **28.6%**(장중 MTM 미반영=보수적 하한).
  - **⚠ alpha 미산출**: `bmAvailable=false`(alphaSignalCount=0) — pykrx `get_index_ohlcv('1001')` 가 KRX 지수메타 포맷 변경으로 `KeyError:'지수명'` → KOSPI bm 전건 결측 → hit이 **pct≥3% 폴백**으로만 평가됨(alpha≥0 기준 적용 시 **더 엄격 = 결과 더 악화**). reconstructed 교차검증도 동일 버그(`get_market_ticker_list` 0개 반환)로 미실행. **단 이 둘은 결론을 바꾸지 않음**(폴백조차 31% + 점수 역상관 + Sharpe 0.08).
  - **결론**: 차트 타이밍 **매수후보 미승격, 베타 유지(`unverified=true`)**. alpha/reconstructed 재평가는 결과를 개선할 수 없는 방향이라 결론 확정. (pykrx 지수 fetch 복구는 P0-pykrx 별건 — regime 운영 데이터 유실이 더 큰 사유.)
- **출처/모듈**:
  - python: `app/indicators/*`(순수함수, pytest 有) + `services/chart_pattern_service.py`(타이밍, `compute_timing` 공용)·`sector_strength_service.py`(섹터) + `routers/chart_patterns.py`(`POST /api/v2/chart/timing`·`/sector-strength`) + `app/backtest/*`(백테스트).
  - Java: `ChartPatternClient`(best-effort) + `ChartSignalRanker`(순수, 테스트 有) + `ChartSignalController`(`/api/recommendation/trend-pullback-top10`·`/sector-strength`).
  - 프론트: 타이밍 = `TodayBriefingTab.vue`(오늘 탭 '차트 타이밍 매수 후보' 베타 섹션, `loadTimingCandidates`) / 섹터강도 = `StockTradingDashboardV2.vue`(발굴 탭 상단 배지, `refreshSectorStrength`).
- **✅ 가시성 확보 (2026-06-29, 작업3)**: `PythonBackendHealthTracker`(소스별 성공/실패/연속실패) — best-effort 클라이언트가 조용히 죽는 걸 가시화. `/api/diagnostics/python-health` 노출 + 연속 3회 실패 시 텔레그램 리스크 알림. 차트 응답에 **`dataAvailable`** 추가(빈 결과가 '신호 없음'인지 '분석서버 다운'인지 구분) → 프론트가 "분석서버 일시 미가용" 명시. **백테스트 표본의 '데이터 미가용 구간'을 식별 가능 → P2-12 신뢰성 보강.**
- **검증 과제**:
  1. **타이밍 신호 적중률**: '신호 발생일 종가 진입 → N거래일 후' 수익률 분포·승률·평균손익. 위험필터(엔벨로프 하단 2회+) 적용/미적용 비교.
  2. **MDD**: 신호 종목 보유 시 최대낙폭. 손절(-3%)·익절(+5%) 동기 가정(봇 상수)과 정합 측정.
  3. **섹터 상대강도 필터 효과**: '덜 빠지는 섹터 상위' 유니버스로 좁혔을 때 적중률/MDD 개선 여부.
  4. **파라미터 민감도**: `box_len`/`box_range_max`/`envelope_k`/`disparity_overheat`/`sector_lookback` 그리드 스윕 — 과최적화 경계 확인.
- **합격 기준 (실거래 승격 조건)**:
  1. 충분한 표본(예: 최소 N개 신호, 복수 국면 BULL/BEAR/SIDEWAYS 포함)에서 **기준 대비 유의한 우위**(예: 동일기간 buy&hold·종합추천 대비 승률/손익비).
  2. 검증 통과 시에만 **매수후보 타이밍 스코어로 승격**(`unverified` 해제 + 별도 PR). 통과 전까지 보조 시그널 유지.
  3. **⚠ 승격 시 tie-break 이중작용 점검(작업5)**: 발굴/매수후보 정렬 `RecommendationService.recommendationComparator` 의 3차 tie-break `changeRate asc`("덜 오른 종목 우선")와 차트 타이밍 눌림목("이미 빠진 자리")은 **같은 방향** → 타이밍을 momentum 랭킹에 편입하면 **덜 오른 종목 과대 가중(이중 작용)** 위험. 승격 PR 에서 comparator 에 타이밍 점수를 섞지 않는지 + 별도 트랙 유지 여부 점검(코드 주석으로 표기 完).
- **테스트**: 백테스트 스크립트(표본/기간/국면 분리 집계) + 결과 요약. 산식 변경 시 기존 지표 pytest(`python-backend/tests/test_indicators.py`) 회귀 green.
- **비고**: 산식은 미검증이나 **구조(타이밍↔섹터 분리, momentum 과 별도 모듈)는 확정** — CLAUDE.md §4 "차트 기법 통합 시 발굴/매수후보 스코어러는 항상 분리" 불변식 참조.

---

## P2-13. 봇 NXT 연장장/종가단일가 청산 — 정규장 강제청산의 후속 (2026-06-29 신규, 작업2 분리)

> **배경**: 정규장 마감(15:20) 강제청산(`executeRegularSessionLiquidation`, BotConfig.forceRegularSessionLiquidation 기본 ON)을 먼저 도입했다.
> 15:20 을 고른 이유는 연속세션 끝이라 시장가/지정가 체결이 확실해서다. **종가단일가(15:20~15:30)·NXT 연장장(08~20) 청산은 미구현** —
> 연장장 "마감" 정의 모호(2026-09-14 애프터마켓 정식 도입 전)가 보류 원인.
> **✅ 견고화(2026-06-29 후속, 작업1)**: 단발 → **윈도우(15:20~15:28 매분 재시도) + 영속 일자 플래그(`BotConfig.lastForceLiquidationDate`, V34) + 리더 페일오버 캐치업 + 15:29 미완료 텔레그램 경고**. 리더가 15:19 死 후 승계 공백으로 단발 청산을 놓치던 엣지케이스 해소. 완전 청산(포트폴리오 empty) 확인 후에만 완료 표기(부분 미체결 재시도). NXT/종가단일가는 여전히 본 티켓 잔여.

- **과제**:
  1. NXT 연장장(애프터마켓) 보유 포지션의 청산 시점·경로 정의(연장장 호가/체결 규격, KIS 주문구분).
  2. 종가단일가 구간(15:20~15:30) 청산 옵션(원하면) — 현재는 연속세션 끝에서만.
  3. `FORCE_LIQUIDATION_TIME`(현 15:20)·청산 대상(정규/연장 분리) 파라미터화.
- **선결**: 2026-09-14 애프터마켓 정식 도입 규격 확정 후 착수(현재는 정규장 청산으로 오버나잇 1차 방어).
- **관련**: `AutoTradingBotService.executeRegularSessionLiquidation`/`FORCE_LIQUIDATION_TIME`, 봇 시간대 상수(REGULAR_END 15:25 / AFTER_MARKET_END 20:00).

---

## P3-2. signal_outcome 생성 DB unique 제약 (방어, 우선순위 낮음 — 2026-06-29 신규, 작업4)

> **배경**: 작업4에서 19:30 평가 배치(`evaluatePendingSignals`)는 **멱등 확인됨**(기존 pending 행 UPDATE → 중복 INSERT 없음).
> 단 시그널 **생성** `record()` 의 중복 INSERT 방지는 **앱레벨 dedup(`findExisting`)뿐**, DB unique 제약은 없다.
> 단일 인스턴스에선 실위험 없으나, 멀티 인스턴스(P3-1)·경합 시 동시 통과로 중복 행 가능(드묾).

- **✅ 해소(2026-06-30, 작업2, V36)**: `V36__add_signal_outcome_unique.sql` — 자기조인 DELETE(최소 id 보존, 원자적) → `ADD CONSTRAINT uq_so_type_code_date UNIQUE (signal_type, stock_code, signal_date)`. 엔티티 `@UniqueConstraint`(idx_so_type_date 는 컬럼순서 달라 중복 아님 → 유지). `record()` INSERT 를 `insertOutcomeIsolated`(`@Transactional REQUIRES_NEW`, selfProvider 프록시)로 격리 + `DataIntegrityViolationException` benign 처리(경합 패자가 호출부 tx 무오염). 검증: 컴파일 + `*SignalOutcome*` + `ApplicationContextSmokeTest` green. **배포 시 Flyway 자동 적용** — 사전 감사 SELECT 로 중복 규모 확인 권장, 사후 `SHOW CREATE TABLE signal_outcome` 로 제약 확인.
- **(원과제)**: `signal_outcome` 에 `(signal_type, stock_code, signal_date)` unique 제약 추가 + 기존 중복 행 정리 선행. P3-1(멀티 인스턴스)과 독립적으로 단일 인스턴스에서도 방어로 선반영(REQUIRES_NEW 격리라 멀티 인스턴스 확장 시에도 유효).
- **관련**: `SignalOutcomeService.record()`/`insertOutcomeIsolated`/`evaluatePendingSignals`(멱등성 주석), `SignalOutcomeRepository.findExisting`, `SignalOutcome`(@UniqueConstraint), `V36__add_signal_outcome_unique.sql`.

---

## P3-3. RecommendationSnapshot growth/valueStability `-1=NA` → nullable 전환 검토 (방어, 2026-06-29 신규, 작업6)

> **배경**: `growth`/`valueStability`(int)는 `-1`을 NA(데이터 없음) sentinel 로 쓴다. 작업6에서 `StockConclusionService.verdictFor`가
> `-1`을 실제 **NEGATIVE 로 오판**하던 버그를 `score<0→"N/A"` 가드 + NA factor 숨김으로 수정했고, 엔티티/사용처에 경고 주석을 달았다.
> 그러나 **sentinel 자체는 위험 패턴** — 새 코드가 `growth > 0`/`>= 0` 필터를 짜면 음수로 오작동할 수 있다.

- **과제**: `int growth`/`int valueStability` → `Integer`(nullable) 전환 + 컬럼 nullable + 사용처(`>= 0` 가드)를 `!= null` 로 정리. 또는 sentinel 유지하되 모든 사용처에 가드 강제.
- **비용/보류 사유**: V29 마이그레이션 default(-1)로 기존 행 다수 → 컬럼 타입 변경 + 데이터 백필 비용 큼. 현재는 verdictFor 가드 + 경고 주석으로 1차 방어, 근본 전환은 보류.
- **관련**: `RecommendationSnapshot.growth`/`valueStability`(경고 주석), `StockConclusionService.verdictFor`(score<0 가드), `RecommendationService.scoreGrowth`.

---

## P0-pykrx. pykrx 지수·종목리스트 엔드포인트 KRX 포맷 변경으로 전구간 빈값 (2026-06-30 발견)

> **진단(2026-06-30, 서버 프로브 확정)**: pykrx 1.0.45 에서 `get_index_ohlcv('1001')`·`get_market_ticker_list()` 가
> **날짜 무관 전구간 0건/빈값**(KRX 가 지수·리스트 엔드포인트 응답 포맷 변경, pykrx 미대응). 표면 증상은
> `KeyError:'지수명'`(get_index_ticker_name)이나 shim 으로 막아도 rows=0 → fetch 자체가 빈 데이터. **개별 종목 OHLCV
> (`get_market_ohlcv`)는 정상**(백테스트 646신호 정상)이라 "지수·리스트 계열" 엔드포인트만 깨짐.

- **영향 범위(정밀)**:
  - **regime(시장국면, V32)** ← `get_index_ohlcv` MA60 → `regime_at_signal` NULL 누적(backfill 불가). **라이브 추천 점수는 무영향**(RecommendationService 가 자체 sector-momentum regime 사용, python regime 소비자는 `SignalOutcomeService` 스냅샷뿐).
  - **sector_strength 배지(발굴탭 베타)** ← `_market_return` KOSPI 결측.
  - **차트 백테스트 alpha** ← `_fetch_index` 결측(작업1 결론 불변).
  - **종목마스터(stock_master)는 무영향** — `KrxStockMasterSeeder` 가 pykrx 가 아니라 **KRX KIND HTML**(`kind.krx.co.kr/corpgeneral/corpList.do`, Jsoup) 소스라 신규상장/상폐 반영 정상. (당초 의심했으나 코드 확인으로 무관 확정.)
- **✅ 지수 경로 해소·운영검증 완료(2026-06-30, P0-pykrx)**: pykrx 지수 의존을 **KIS 일봉 지수로 전환**(Option B). Java `KoreaInvestmentService.getIndexDailyOhlcv(0001, days)`(TR `FHPUP02120000`, 진짜 종합지수 — ETF 프록시 아님) + 내부 엔드포인트 `GET /api/market/index/kospi-daily`(permitAll, `MarketIndexController`). python `regime_service`·`sector_strength_service` 가 pykrx 대신 이걸 소비(국면 규칙 v1·classify 로직·테스트 불변, §10·§4c 보존). 파서 순수함수 단위테스트 `KoreaInvestmentIndexParseTest` + 어댑터 `tests/test_index_source.py`.
  - **⚠ 날짜 앵커 교정(후속)**: KIS 지수 TR 은 `FID_INPUT_DATE_1`(기준일)을 앵커로 직전 100건 반환(종목 TR 은 DATE_2 가 끝인 것과 반대). 초기 `DATE_1=start` 로 둬서 데이터가 4개월 전(start)에서 끝나 regime asOf 오판(kospiClose=2월값) → **`DATE_1=end(오늘)` 로 스왑** 교정.
  - **운영 검증 OK**: regime `asOf=2026-06-30`, `kospiClose` 현재 실값, BULL 정상 분류(`market_regime_kospi` 캐시 클리어 후). `regime_at_signal`(V32) 정확 기록 재개. sector-strength 배지 동일 KIS 경로로 복구 + `/api/recommendation/sector-strength`·`trend-pullback-top10` permitAll 추가(401 해소).
- **잔여(P0 종료 후로 분리)**: `get_market_ticker_list` 깨짐(reconstructed 백테스트 유니버스 복원 전용)만 미해결 → **별도 티켓 [P3-4]로 분리**(P0 닫혀도 추적 유지). **종목마스터는 pykrx 와 무관**(line 298). 
- **상태**: 지수 경로(regime/sector) 해소·운영검증 완료 → **P0 종료**. ticker_list 잔여는 P3-4.
- **관련**: python `regime_service.py`·`sector_strength_service.py`·`chart_backtest_service.py`(pykrx 지수 호출 3곳), Java `KoreaInvestmentService.getIndexDailyOhlcv`/`parseIndexDaily`·`MarketIndexController`, `SecurityConfig`(permitAll `/api/market/index/**`).

---

## P3-4. pykrx get_market_ticker_list 깨짐 — reconstructed 백테스트 유니버스 복원 (P0-pykrx 분리, 2026-06-30)

> **배경**: P0-pykrx 에서 pykrx 1.0.45 `get_market_ticker_list()` 가 KRX 포맷 변경으로 **전구간 0건** 확인됨(지수 `get_index_ohlcv` 와 같은 뿌리, 다른 엔드포인트). 지수 경로는 KIS 일봉으로 해소했으나 ticker_list 는 미해결로 남겨 분리.

- **영향 = 백테스트 reconstructed 모드 전용(운영 무관)**: `chart_backtest_service.reconstruct_universe`(시점별 상장종목 복원, 생존편향 교차검증용)만 빈 유니버스 반환. 작업1 결론(차트 타이밍 **승격불가**)이 이미 확정이라 reconstructed 교차검증의 실익 낮음 → **후순위**.
- **종목마스터(`stock_master`)는 무영향(재확인)**: `KrxStockMasterSeeder` 는 pykrx 가 아니라 **KRX KIND HTML**(`kind.krx.co.kr/corpgeneral/corpList.do`, Jsoup) 소스 → 신규상장/상폐 반영 정상. "KIS 종목마스터 전환" 불필요. 신선도 점검은 `stock_master.last_seed_time_seconds` 메트릭/시드 로그로 독립 확인.
- **과제(필요 시)**: reconstructed 유니버스 복원을 pykrx 대신 **KIS/KRX KIND 기반 시점복원**(또는 stock_master 상장일·상폐 이력)으로 대체 검토. 단 작업1 재개 결정 전엔 착수 불필요.
- **관련**: `python-backend/app/backtest/chart_backtest_service.py`(`reconstruct_universe`, `get_market_ticker_list` 2곳), [P0-pykrx], [P2-12].

---

## P3-5. 간밤 미국장 tilt 임계값 캘리브레이션 (작업3, 미검증 베타 — 2026-06-30 신규)

> **배경**: 작업3에서 간밤 미국장 보조 tilt(`OvernightUsMarketService.classifyOvernight`)를 '오늘' 탭 참고용으로 추가했다.
> 임계값(3지수 평균 ±0.6%, VIX 20/25/30, SOX -2%)은 전부 **임시값**(사후확증 아님, 직관 기반) — `unverified=true`로
> regime/봇/추천 산식에 미편입, 표시 전용. 차트타이밍·섹터강도와 동일 게이팅.

- **검증 과제**: tilt(BULL/NEUTRAL/BEAR) vs **KOSPI 익일 시초가(또는 당일 종가) 수익률** 적중률 측정. 구간별(BULL이 실제로 상승 출발 비율↑?) + SOX 단독 트리거(반도체 급락→한국 약세)의 실효성.
- **합격 시**: 임계값 데이터 기반 보정. (단 regime 산식 편입은 별도 결정 — 현 설계는 표시 전용 유지가 기본.)
- **데이터 소스**: `GET /api/global-futures/overnight-us`(tilt+drivers) 일자별 스냅 + KOSPI 일봉(KIS, P0-pykrx로 복구됨). 신호처럼 `signal_outcome` 패턴으로 스냅 누적 검토.
- **관련**: `OvernightUsMarketService`(`classifyOvernight` 순수, `OvernightUsMarketServiceTest`), `GlobalFuturesController`(`/overnight-us`), 프론트 `TodayBriefingTab.vue`(`loadOvernight`). 참고: 기존 `getKospiImpactAnalysis`(0~100 개장 영향예측)는 별개 모델.

---

## P1-6. 종합점수 4카테고리 적중률 캘리브레이션 (★1순위 — 2026-06-30 신규, 실측 진단 기반)

> **배경**: 차트타이밍 백테스트(31%·점수 역상관, P2-12)에서 "검증 안 된 지표를 합산하면 좋은 신호를 망친다"를 학습.
> 이에 **현재 종합점수 4카테고리(earnings/supplyDemand/technical/sectorMomentum ×20)의 실제 적중률**을 prod
> `signal_outcome`(평가완료 2996건, 단 카테고리 점수 보유 **88건**, regime 0건=pykrx 영향 P0-pykrx로 어제 복구)으로 진단.

- **실측 결과(2026-06-30, n=88 — 표본 작음, 방향성 참고)**:
  - **수급(supplyDemand) = 역상관 구조적 확정 ❌**: 점수구간 단조 감소(0-4=66.7% / 5-9=50% / 10-14=41.2% / **15+=34.8%**), 평균수익도 7.61→0.38 동반 하락. 메커니즘 = `scoreSupplyDemand`가 연속·대량 순매수를 가점 → **≥15는 "이미 많이 사들여진=혼잡=반락"**(차트타이밍 눌림목과 동일 계열). 코드가 5일+ 가점↓로 일부 인지하나 ≥15 누적 시 결국 오른 종목 쏠림.
  - **기술(technical) = 예측력 有 ✅**: 강세(≥15) 57.1% > 약세 43.2%(+13.9%p). 4개 중 유일하게 일함.
  - **실적(earnings) = 게이트 + 약한 랭킹 변별 ✅(약)**: 값이 19점(75건)·20점(13건)에만 몰림(POSITIVE/TURNAROUND만 점수). **20점=적중53.8%/수익3.81 > 19점=42.7%/1.85** — 선별 게이트이면서 20점이 19점보다 좋음(약한 랭킹 변별 有). 유지.
  - **섹터(sectorMomentum) = 14점 구간 예측력 強, 측정 임계가 틀렸음 ✅**: max ~8이라던 1차 분석은 **오류**(AI 테마 점수 `ts`=min(10,4+테마수×2)+등락률, 최대 14를 누락). 실제 분포에 10·12·**14점** 다수, **14점=적중 65%·수익 +6.86%(기술급)**. U자(8점=16.7% 뚝 → 14점 반등) = BULL floor만 받은 약한 8점 vs **AI 테마 주도 14점**. `≥15` 잣대가 sweet spot(14)을 한 칸 차로 놓쳐 "강세 0건" 오측정 → **섹터 측정 임계 `≥14`로 재설정** 필요.
- **4카테고리 진단 완료(2026-06-30)**: **기술=예측력 有(유지)** / **수급=역상관(가중↓ 후보)** / **실적=게이트+20점 약변별(유지)** / **섹터=14점 강예측·측정임계 오류(≥14로 재측정)**. → "기술·섹터(테마14)가 일하고, 수급은 해롭고, 실적은 게이트" 그림.
- **측정 버그(별도)**: `aggregateCategories`의 단일 임계 `CATEGORY_STRONG_THRESHOLD=15`는 카테고리별 점수 분포 차이(실적 8~20·섹터 0~14·수급 0~20)를 무시 → **카테고리별 임계로 분리**해야 강세 표본이 잡힘(섹터 ≥14 등). 이건 산식 아닌 *측정* 수정이라 데이터 무관하게 선반영 가능.
- **조치(가중치)**: **당장 가중치 변경 보류**(n=88·표본 작음, regime 분리 불가). 수급=역상관 의심지표로 확정. regime 복구됐으니 **데이터 축적 → N주 후 국면별 분리 재측정**.
- **로드맵**: 단기 **B안**(미국장·차트타이밍·섹터강도 + 4카테고리를 한 화면에 모아 보되 **점수 미편입**) → 표본 ≥수백 시 **A안**(차트백테스트처럼 단조·유의한 것만 종합점수 합류, 수급 가중↓/제외, 섹터 임계 재조정).
- **관련**: `RecommendationService.scoreEarnings/scoreSupplyDemand/scoreTechnical/scoreSectorMomentum`, `SignalOutcomeService.aggregateCategories`(CATEGORY_STRONG_THRESHOLD=15), `signal_outcome`(V30 카테고리 스냅샷), [P2-12](차트 백테스트 — 같은 교훈), [P2-14](B안 보드).

---

## P2-14. 종합 판단 보드 (B안 — 신호 한 화면 비교, 점수 미편입) (2026-06-30 신규)

> **목적**: "여러 종목 중 최적 찾기" — 매수후보를 신뢰도 3계층 신호로 **한 화면에서 비교**. P1-6 교훈("검증 안 된 지표 합산 = 독")을 구조로: 검증된 것만 종합점수, 미검증은 표시만.

- **✅ Phase 1 완료(2026-06-30)**: momentum 후보(getTop5)만. `GET /api/recommendation/judgment-board` + `JudgmentBoardService`(순수 `assembleRows`/`parseSectorRel`, 테스트 有) + `JudgmentBoardDto`. 프론트 `SectionJudgmentBoard.vue`(발굴 심화 '🧭 종합판단' 서브탭).
  - **✅ 발굴 네비 통합(2026-07-01)**: 발굴 2단 서브탭(목록/심화)을 둘 다 상단으로 모으고 `discoverGroup`로 콘텐츠 단일화(심화 바 버림 해소). **기본 진입 = 종합판단 보드**. 빈 보드 폴백('목록 탭에서 발굴' 버튼). 순수 레이아웃(산식 무관), 134 vitest green. 컬럼 3계층 = ① 점수(검증/게이트: total/기술/실적/섹터테마) · ② 참고(미검증·점수 미편입: 차트타이밍/섹터강도/간밤미국장) · ③ 경고(수급 역상관 **의심**, ≥10, 표본작음 톤). 정렬·필터(역상관 숨기기/기술강세만). **종합점수 산식 무변경**.
- **Phase 2(예정)**: 발굴 5트랙 union — 비-momentum 종목(value/growth/oversold 트랙은 자체 산식이라 4카테고리 비어있음)을 **momentum 4카테고리 스코어러로 일관 재점수** + 출처태그 확장(저평가/성장/…). dedup(union). 기본정렬 종합점수, 트랙별 필터. → momentum 필터(31% 적중·수급 역상관 포함)에 안 갇히고 "momentum 밖 강한 종목" 발견.
- **불변식**: unverified 게이팅(미검증 점수 미편입) · 새 라우트 금지(서브탭 흡수) · 종합점수 산식 무변경(보드는 조립·표시 전용 — 산식 합류는 P1-6 데이터 후 별도 결정).
- **관련**: `JudgmentBoardService`/`JudgmentBoardDto`/`RecommendationController`(`/judgment-board`), `SectionJudgmentBoard.vue`, `StockTradingDashboardV2.vue`(discoverSubTabs board), [P1-6](카테고리 진단).

---

## P2-15. 차트신호/종합 중복 통합 — 발굴 UI 정리 2단계 (2026-07-01 신규)

> **배경**: 발굴 탭 UI 진단(2026-07-01)에서 "난잡함"의 한 축 = 중복. **1단계(A 슬림화) 완료**(시간대신호·관심종목 오늘 탭 이동, 차트신호 종목 접힘 — `525891e`). 2단계 = 중복 기능 통합(C). 종합판단이 Phase 2(P2-14)로 풍부해진 뒤 착수(지금은 흡수 시기 이름).

- **중복 현황(진단 근거)**:
  - **차트 신호 3군데**: ① 발굴목록 '차트 신호 종목'(Java `ChartPatternService` 패턴검출+composite 5/5) · ② 종합판단 '차트타이밍' 컬럼(python `ChartPatternClient` timing) · ③ 오늘탭 '차트 신호 관찰'(python timing, momentum 후보). 출처·대상 다른데 이름이 다 "차트 신호".
  - **🎯종합 vs 🧭종합판단**: 같은 momentum 후보(목록 vs 3계층 비교표). 종합판단 상위호환.
  - **composite 5/5**(`CompositeSignalService`) vs **종합판단 보드**(신호 3계층): 둘 다 "신호 종합".
- **과제(2단계)**: ① 차트신호 3→1 정리(발굴목록 '차트 신호 종목' 삭제 or 종합판단 흡수) · ② 🎯종합 은퇴(종합판단 일원화) · ③ composite 5/5 → 종합판단 흡수 검토.
- **선결**: **P2-14 Phase 2(발굴 union)** 로 종합판단이 비교 대상 충분해진 뒤. 그 전 삭제는 검증된 기능 손실 위험.
- **관련**: `ChartPatternService`(Java 패턴검출)·`CompositeSignalService`(5/5)·`SectionJudgmentBoard.vue`·`SectionTotalRecommendation.vue`·발굴목록 차트신호 블록(`StockTradingDashboardV2.vue`), [P2-14].
