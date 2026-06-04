# 주식 플랫폼 A-Z — 화면 / 백엔드 / 배치 전수 정리

> 작성: 2026-06-04 (코드 직접 전수 조사 기준 — `@Scheduled` 어노테이션·서비스·라우트 실측 반영).
> 직전 버전(2026-06-01) 대비: 스케줄러 cron 전수 검증, 자동매매 봇 5활성트랙(+청산봇 2 비활성) 정정, 가격 ×10 진단 가드/테스트 갱신 반영.
> 한 줄 요약은 [`STOCK_PLATFORM_ONEPAGER.md`](./STOCK_PLATFORM_ONEPAGER.md), 화면→코드→DB 상세는 [`STOCK_PLATFORM_GUIDE.md`](./STOCK_PLATFORM_GUIDE.md).

한국 주식(KRX 정규장 + NXT 대체거래) 종목 **발굴 / 분석 / 모의·실전 자동매매** 통합 개인 플랫폼.
Spring Boot + Vue 3 + MariaDB + Redis(L2) + KIS REST/WebSocket(옵션) + Gemini AI + DART + 텔레그램(3채널).

```
[외부] KIS REST/WS · Naver(시세 폴백) · DART(공시) · Gemini(AI) · Yahoo(유가) · GoldAPI(금/은)
   │
[캐시] L1 Caffeine(30s~1h) → L2 Redis(5~120m, CacheWarmer 30s~10m 워밍) → L3 MariaDB
   │
[점수] RecommendationService(4카테고리→정규화) · AiStockAnalysis · CompositeSignal · AiStrategy
   │
[기록] recommendation_snapshot → signal_outcome(record) → 3거래일 후 평가(alpha/MFE/MAE)
   │
[출력] REST 60+ 엔드포인트 → Vue 화면 · 텔레그램 3채널 · 자동매매 봇(모의/실전)
```

---

# 1부. 화면 (프론트엔드 — Vue 3 + Vite)

## 1.1 라우트 (`frontend/src/main.js`, 304줄, 전부 lazy-load 코드분할)

| 경로 | 컴포넌트 | 용도 | 가드 |
|---|---|---|---|
| `/` → `/login` | — | 리다이렉트 | — |
| `/login`·`/signup`·`/forgot-password` | Login/Signup/ForgotPassword | 인증(정적 import) | 공개 |
| `/user` | UserDashboard | 메인 홈(투자+일상 메뉴) | auth |
| `/stock-dashboard` | StockTradingDashboardV2 | **주식 통합 허브(트레이드/분석/뉴스/글로벌 탭)** | auth |
| `/stock/:stockCode` | StockDetailDashboard | **종목 상세(4,707줄)** | auth |
| `/global-futures` | GlobalFuturesPage | 글로벌 선물·VIX·금/은/원유 | auth |
| `/board`·`/asset`·`/finance`·`/files`·`/car`·`/diet`·`/exercise`·`/my-content`·`/settings` | 각 페이지 | 일상/자산/파일 등 | auth |
| `/admin`·`/admin/users`·`/admin/logs`·`/admin/batch` | Admin* | 관리자(사용자/로그/배치모니터) | auth+admin |

**리다이렉트(분석 탭으로 통합)**: `/sector`·`/investor`·`/investor-trades`·`/consecutive-buy`·`/investor-surge`·`/earnings-screener`·`/research`·`/market-timing` → `/stock-dashboard?tab=analysis`. `/news` → `?tab=news`. `/trading-indicators`·`/ai-stock`·`/ai-strategy`·`/stock-detail` → `/stock-dashboard`. `/paper-trading` → `?tab=trading`. `/gold`·`/silver`·`/oil` → `/global-futures`.
**가드**: `requiresAuth`(JWT 만료 시 `/login`), `adminOnly`(ADMIN만), 토큰 검증 루프 방지.

## 1.2 핵심 화면 (줄수 = 실측)

### StockTradingDashboardV2 (통합 허브, ~1,950줄)
| 탭(키) | 위젯 | 주요 API |
|---|---|---|
| **트레이드(premarket)** | 시장상태바·종합추천TOP10·저평가TOP10·외국인기관수급·(장중)실시간급증/(장전)관심종목·강세섹터·차트신호종목·섹터동향(거래대금/시장지도)·(장중,관리자)페이퍼트레이딩 | `marketAPI.getStatus`/`recommendationAPI.getTop5`·`getValueTop10`/`investorAPI.getTopTrades`/`quantTaAPI.scanPatterns`·`strongSectors`/`sectorAPI.*` |
| **분석(research)** | 종합추천·AI전략·백테스트·스크리너·퀀트분석·투자자분석·시장타이밍·뉴스(서브탭) | 탭별 독립 |
| **뉴스(news)** | NewsPage | `newsAPI.*` |
| **글로벌(global)** | GlobalFuturesPage | `globalFuturesAPI.*` |

- 자동갱신 15초 폴링(`useAutoRefresh`), 탭 비가시 시 정지(`pauseWhenHidden`).

### StockDetailDashboard (종목 상세, 4,707줄 — 최대 화면)
- **헤더 듀얼 점수**: 단기 트레이딩(`aiAnalysis.overallScore`) + 중장기 펀더멘털(`diagnosisData.overallScore`, RSI 과열 시 "관망" 강등)
- **결론 카드**(StockConclusionCard): 종합추천 스냅샷 기준 4-level + 6 factor + 적중률 + 신선도 신호등 (※ 헤더 점수와 **소스 다름** — 캡션 명시)
- **탭1 종합분석**: 가격헤더·결론카드·Volume Profile(POC/VA, 90일)·지지저항(피벗 클러스터)·브리핑헤드라인·리스크카드 + 본문 3서브탭(① 재무건전성/최근5일수급/기술적/경쟁사비교 ② 뉴스+투자자 ③ AI전략 사유+목표가)
- **탭2 투자자동향**: 가격 vs 누적순매수·당일 실시간 수급·30일 일별추세
- **탭3 트레이딩지표**: 글로벌(나스닥/S&P/필반)·주도섹터 랭킹·RSI 등 기술지표
- **2단계 로딩**: `getQuick()`(3~5s, 시세/수급/차트/재무) → `getHeavy()`(1~30s, 리스크/AI/피어) → `getDiagnosis()`. 자동갱신 10초(토글).
- **시세 소스**: `getQuick/Heavy` 모두 백엔드 **공용 `stockPriceService.getStockPrice()`** 경유(목록과 동일 캐시/DB). ← 화면 간 가격 불일치 정리(최근 커밋).

### 기타 화면
- **PaperTradingPage**(2,674줄): 모의(virtual)/실전(real, admin)/봇성과(botPerformance, BotPnlChart/MDD/승률/Profit Factor)/주간리포트(weeklyReport)
- **GlobalFuturesPage**(1,614줄): 선물(나스닥·S&P·러셀·VIX·10Y·Fear&Greed·코스피영향) + 금/은/원유 30일차트. 30초 폴링.
- **AiStrategyDashboardPage**(1,724줄): 4분할(스캘핑⚡/스윙📈/턴어라운드🔄/가치💎) 각 TOP5 + 사유뱃지 + 종합매력도
- **EarningsScreenerPage**(3,832줄): 마법공식/PEG/턴어라운드/요약 + 관리자(수집/보정). 기준일 표시(08:30·15:40 수집).
- **TradingIndicatorsPage**(1,593줄): 글로벌 4카드·주도섹터·RSI 다이버전스. 15초 폴링.
- **UserDashboard**(1,200줄): 홈(시장위젯+투자 히어로카드+관리/일상 메뉴), 관리자는 시스템 탭(AdminDashboard 임베드).

## 1.3 v2 컴포넌트 (`components/v2/`, 줄수)
SectionMarketMap(848,히트맵/자금흐름/예측) · SectionQuantTa(913,스크리너/상관) · MagicFormulaSmartTable(962,AI스코어 테이블) · SectionLiveSurge(486,장중 실시간급증) · SectionBacktest(510) · ForecastDetailModal(422,AI예측) · StockRiskCard(346,DART+AI) · StockConclusionCard(330) · TradingSafetyWidget(297,킬스위치) · StockSearchModal(243,Ctrl+K) · DashboardHeader(220,GNB탭) · SectionTotalRecommendation(205) · BuyChecklistModal(194) · StockBriefingHeadline(181) · SkeletonLoader(169) · ChartPatternList(117) · BotPnlChart(110) · RelatedStocksList(86,동일섹터+상관0.5+).
**루트**: VolumePowerGauge(체결강도), GlobalNav, DataFreshness, MarketInfoWidget, NotificationBell, AppToast, BackButton.

## 1.4 공통 모듈
- **composables**: `useAutoRefresh.js`(109줄, 폴링+카운트다운, 비가시 정지, 옵션 interval/immediate/pauseWhenHidden) · `useMarketStatus.js`(106줄, 폭락판정 KOSPI/KOSDAQ≤-3%, ADR 5단계, VIX 5단계)
- **utils**: `api.js`(1,045줄, 모듈 30+) · `marketFormatters.js`(formatNumber/formatChange/getChangeClass/formatTradingValue) · `auth.js`(TokenManager/UserManager, JWT exp 검증) · `lazyObserver.js`(IntersectionObserver 1회) · `nativeBridge.js`(네이티브 지문로그인) · `toast.js` · `webauthn.js`
- **api.js 모듈**: authAPI·stockAPI·stockDetailAPI·marketAPI·sectorAPI·quantTaAPI·screenerAPI·investorAPI·aiStrategyAPI·tradingIndicatorAPI·globalFuturesAPI·recommendationAPI·paperTradingAPI·riskAPI·tradingSafetyAPI·newsAPI·userSettingsAPI·assetAPI·exchangeRateAPI·goldAPI·silverAPI·oilAPI·shortSellingAPI·watchlistAPI·telegramAPI·adminAPI·batchJobAPI
- **인터셉터**: 요청 시 JWT 자동첨부 / 429→경고토스트 / 401→리프레시토큰 큐잉 갱신.

## 1.5 시간대 판정 (프론트, `StockTradingDashboardV2` 663~673줄)
```js
mins = h*60 + m
주말(day 0/6) → 'post'
mins < 480  (08:00 이전)      → 'pre'    // 장전
mins < 1200 (08:00~20:00)    → 'during' // 거래중(프리+정규+애프터 통합, NXT)
그 외 (20:00~)               → 'post'   // 종료
```
VolumePowerGauge: `<480` before / `480~1200` market / `>1200` after. SectorTradingPage `isPreMarket` 480~539(섹터는 09:00 기준). EarningsScreener `isBeforeMarketOpen`: `<8시`.

---

# 2부. 백엔드 (컨트롤러 / 서비스 / 점수 산식)

## 2.1 컨트롤러 + 대표 엔드포인트 (60+)
| 컨트롤러 | base | 대표 경로 |
|---|---|---|
| RecommendationController | `/api/recommendation` | `/top5` · `/value-top10` · `/strong-value-frequency` |
| StockDetailController | `/api/stock` | `/{code}/summary`·`/quick`·`/heavy`·`/conclusion`·`/checklist` |
| StockPriceController | `/api/stock` | `/search`(키워드 50자 cap) · `/{code}` (공용 시세 경로) |
| StockAnalysisController | `/api/analysis` | `/diagnosis/{code}`·`/batch-scores`·`/supply-demand/{code}`·`/technical/{code}` |
| PaperTradingController | `/api/paper-trading` | `/account/*`·`/portfolio`·`/trades`·`/statistics`·`/bot/start\|stop\|status\|mode\|trigger-buy`·`/bot-performance`·`/real/*` (봇/실전=ADMIN) |
| SignalOutcomeController | `/api/signal-outcomes` | `/accuracy?days`·`/timeseries`·`/compare?cutoff` |
| InvestorTradeController | `/api/investor` | `/top-trades`·`/top-trades/realtime`·`/consecutive-buy[/all]`·`/surge[/all\|/common\|/trend/{code}\|/collect]`·`/conviction`·`/collect[/recent]`·`/recollect` |
| SectorTradingController | `/api/sector` | `/trading?period=TODAY\|MIN_5\|MIN_30`·`/trading/{code}`·`/trading/rotation`·`/trading/refresh\|status`·`/opportunities` |
| MarketIndicatorController | `/api/market` | `/price-rise`(급등TOP50)·`/price-fall`(급락TOP50) |
| (MarketTiming) | `/api/market` | `/status`·`/adr/history`·`/forecast` |
| GlobalFuturesController | `/api/global-futures` | `/quotes`·`/quote/{symbol}`·`/kospi-impact` |
| DiagnosticsController | `/api/diagnostics` | `/data` (운영 헬스) |

## 2.2 종합 추천 점수 산식 (`RecommendationService`)
- **핵심 4카테고리 × 20 = raw 80** → `normalizeScore` → **0~100**. validCount(≥0점 카테고리) **≥3** 이어야 채택(coverage 75%).
  - **earnings**(실적: EPS서프라이즈/매출/영업이익률/현금흐름) · **supplyDemand**(외국인·기관 5일 순매수+추세slope) · **technical**(MA정배열20/60/120·RSI·거래량·골든크로스) · **sectorMomentum**(섹터 거래대금 INFLOW 순위 + regime 승수)
  - **valueStability**(PBR/ROE/부채/흑자, ≤20) · **growth**(매출·이익 YoY·PEG) · **aiStrategy**는 **별도 트랙**(후보발굴/태그/저평가TOP10), 총점 산식 제외.
- **임계**: **≥75 STRONG_BUY / 55~74 BUY / 40~54 HOLD / <40 제외**. + phase34: total≥75 & valueStability≥12 → **+2 보너스**.
- **시장 국면(MarketRegime)**: 섹터 평균 등락률로 BULL(>+1%, dead band 0.5)/BEAR(<-1%)/SIDEWAYS 히스테리시스 판정 → 카테고리 multiplier(BULL: 실적·수급×1.2, 섹터×1.5 / BEAR: 수급×0.8, 섹터×0.75).
- **추격매수 방지**: RSI≥75/볼린저상단/5일+15~20% 과열 페널티 · "신규진입(어제 없던 종목)이 65+ & 5일+15%" → -10(BULL 제외) · delta tie-break.
- **정렬**: total → delta(오늘-어제) → changeRate.
- **캐시/저장**: 메모리 30분 TTL(top5/value-top10) → DB 스냅샷 폴백. 스냅샷 INSERT 시 STRONG_BUY/BUY → `SignalOutcomeService.record()`(currentPrice>0 보장, phase38).

## 2.3 결론 / 체크리스트
- **StockConclusionService 룰체인**(입력=마지막 `RecommendationSnapshot`): ① total≥75 → STRONG_BUY ② value≥12 & total<55 → HOLD ③ supplyDemand≥15 & technical<8 → BUY(주의) ④ total≥55 → BUY ⑤ else → WAIT. + **충돌해설 8종**(단기강/장기약, 수급강/기술약, 섹터강/종목약, 실적강/관심없음, 수급후반, AI발굴/지표미달, 전부평범 등).
- **BuyChecklistService**: 필수 2(tradable / shortSelling<5%) + 가산 3(연속매수≥3일 / 복합신호≥3of5 / 결론 BUY+) → **3/3 STRONG · 2/3 MODERATE · 1/3 CAUTION · 0/3 NOT_RECOMMENDED**.

## 2.4 시그널 적중률 (`SignalOutcomeService`)
- 발생 시 `record`(priceAtSignal + KOSPI bm 동시 기록, 중복 skip) → **19:30 배치로 3거래일 후 평가**.
- **hit = `alpha_3d ≥ 0 AND pct_change_3d > 0`**(시장 이김 + 절대수익), alpha 없으면 폴백 `pct≥3%`. MFE/MAE(3거래일 OHLCV) 측정.
- 추적 타입: STRONG_BUY · BUY · COMPOSITE_5OF5 · COMPOSITE_4PLUS (+ SURGE/AI 계열).
- 평가 후 **STRONG_BUY 7일 평균 alpha 음수 시 risk 채널 경고**(일 1회).

## 2.5 시세 서비스 (`StockPriceService`) — 공용 경로 통일
- **`getStockPrice(code)`**: L1 priceCache(KIS 1분 / Naver 10분) → L2 DB(5분) → KIS REST(`FHKST01010100`, `UN` 통합시세, rate limit 5req/s) → Naver 폴백(15분 지연). 등락률 0 보정·부호 일원화.
- **`getStockPrices(list)`**: `@Transactional(NOT_SUPPORTED)` — 외부 API 중 DB 커넥션 미점유, IN 배치쿼리로 N+1 회피, 비동기 save.
- **`getStockPricesFromCacheOnly`**: 목록용(API 미호출).
- **`warnIfPriceOutlier`**(진단 가드, **로깅만 / 가격 미보정**): **×10 배수오류** 근본원인(파싱 vs 응답) 추적용. 다중 그물 — ① 당일 밴드(현재가 vs [저가,고가]±10%, *현재가 단독 오염*만 잡힘) ② 전일대비율 `prdy_ctrt` >±31%(일일 변동제한 초과) ③ **DB 앵커 배수**(현재가 vs 직전 저장가 ≥5배/≤0.2배). ※ ①은 응답 전체가 일괄 ×10되면 현재가가 밴드 안에 머물러 **절대 안 잡힘** → 일괄 스케일링은 외부 앵커인 ③(DB 직전가)으로만 검출 가능. KIS raw(`stck_prpr/hgpr/lwpr/sdpr/prdy_ctrt`) 동봉 로깅.
- Naver: 3회 연속 에러 → 60초 서킷, 3회 409 → 30분 블랙리스트. **매시 정각 `cleanupExpiredCache`로 L1 만료 + 블랙리스트 일괄 리셋**.

## 2.6 기타 서비스 (핵심 상수)
- **AiStockAnalysisService**: 가중 기술15/수급50/펀더35, 09·12·15시, 90+ 텔레그램.
- **AiStrategySnapshotService**: 4전략 — 스캘핑 30분 / 스윙·턴어라운드·가치 매시 정각(08~19시), 15:40 종가확정, 06시 정리.
- **InvestorSurgeService**: 장중 10분 스냅샷(HOT 100억/WARM 50억/공통 30억), 당일 보관.
- **CompositeSignalService**: 5신호(패턴/지지선-5%/Value Area/수급/AI≥60), 5of5·4plus 시그널, 25분 워밍.
- **MultiConvictionService**: 2주체+ 동시 순매수(외국인/투신/사모/연기금/보험), 극단등락 제외, 20:10 알림.
- **PreemptiveRadarService**: 선점레이더(섹터 자금가속/신고가눌림/대량보유), 16:35 알림.
- **WatchlistRiskMonitorService**: 4대 리스크(공매도급등/DART/대량매도/뉴스), 08~19시 10분, TTL 8분 락.
- **SectorTradingService**: 09~15시 3분 스냅샷 차분, 08시 일별캐시 리셋, 15:35 전일 스냅샷.
- **ChartPatternService**: 더블바텀/H&S/돌파/박스, 90일 일봉, 30분 캐시.
- **AutoTradingBotService**: **활성 2봇 = 스캘핑 + 스윙**(각 매수/매도 크론, 스캘핑은 15:10 청산 스텝 포함). 별도 청산봇(executeClosing*)은 **주석처리=비활성**. 킬스위치, Clock 주입. (상세 3.4)
- **BotPerformanceService**: 승률/평균수익/MDD/Profit Factor (virtual_trade_history 기준).
- **KoreaInvestmentService(KIS)**: 시세/일봉/투자자TOP50/지수/주문, 5req/s, 서킷 50%/30s·retry3(300ms×2). 실전 `openapi:9443` / 모의 `openapivts:29443`.
- **GeminiService**: gemini-2.0-flash, 뉴스요약 일 500 한도, 서킷 60%/60s.
- **KisWebSocketService**: 옵션(`KIS_WEBSOCKET_ENABLED=false` 기본, 미생성), 구독 41건, 재연결 999회.
- **DartService**: corpCode 06시 갱신 · `DartDisclosureMonitorService` 위험공시(증자/감자/인수합병/파산/상폐예고) 모니터.

## 2.7 DTO / 엔티티 핵심
- **recommendation_snapshot**: stockCode·totalScore·earnings·supplyDemand·technical·sectorMomentum·valueStability·growth·aiStrategy·tags·changeRate·rankOrder·snapshotAt (7일 retention)
- **signal_outcome**: signalType·stockCode·signalDate·priceAtSignal·bmPriceAtSignal·priceAfter3d·pctChange3d·bmReturn3d·alpha3d·maxHigh3d·maxLow3d·mfePct3d·maePct3d·hit·evaluatedAt
- **stock_price**: currentPrice·changeRate·high/low/openPrice·volume·tradingValue·per·pbr·eps·marketCap·fetchedAt (DECIMAL(15,2))
- **virtual_trade_history**(account/type/qty/price/손익/사유) · **bot_trading_position**(strategy/buyPrice/highPrice/halfSold/timeExtended/mode/version 낙관락) · **ai_strategy_snapshot**(strategyType/score/aiComment/themes/rank + 재무지표) · **investor_daily_trade** · **investor_intraday_snapshot**(당일) · **stock_master**

## 2.8 인프라 / 설정
- **캐시 L1(Caffeine)**: sectorTrading 5분 · stockPrice 3분 · stockSearch 1분 · chartPatterns 30분 · stockDetailFinancial 10분 · stockDetailRisk 3분 · stockDetailChart 2분 · stockDetailAi 15분 · aiScreenerAnalysis 1시간 · earningsSummary 6시간. (expireAfterWrite + recordStats)
- **캐시 L2(Redis)**: `CACHE_REDIS_ENABLED=true` 기본, `MarketCacheWarmerService` 30s~10m 워밍으로 프론트 트래픽이 KIS rate limit 직접 안 때리게 격리.
- **Resilience4j**: kisApi(window20/min10, 50%/30s, slow 70%@5s, retry3 300ms×2), geminiApi(60%/60s), dartApi(50%/60s). `/actuator/health` 노출.
- **application.yml**: JWT(access 15분/refresh 7일) · KIS(실전 9443 기본, `KIS_BASE_URL` 전환) · Gemini · DART · 텔레그램 3토큰 · `TRADING_DAILY_BUY_LIMIT_KRW`(기본 500만)·`alert-threshold`(100만) · `NEWS_GEMINI_DAILY_LIMIT`(500).
- **Flyway**: baseline V14(baseline-on-migrate, out-of-order=false).

---

# 3부. 배치 / 스케줄러 / 인프라 (cron 전수 검증)

## 3.1 스케줄러 풀 (`SchedulingConfig`, 각 size 16)
| 풀 | 우선순위 | 용도 |
|---|---|---|
| **taskScheduler**(@Primary, 미지정 시 default) | NORM+2 | 트레이딩·포지션감시·자동매매(스캘핑/스윙/청산)·수급급증 스냅샷 — 슬롯 항상 확보 |
| **cacheScheduler** | NORM | 시세/수급/섹터/AI 캐시 워밍·DART 모니터·AI전략 스냅샷 (KIS 호출) |
| **batchScheduler** | NORM-1 | 리포트·뉴스·재무·정리·추천스냅샷·signal_outcome 평가·유가/금/은 |

## 3.2 일과 타임라인 (KST, MON-FRI 기본) — 실측 cron
| 시각 | cron | 작업(메서드) | 풀 |
|---|---|---|---|
| **03:00 / 03:30** | `0 0 3` / `0 30 3` | 배치이력 7일 정리 / 스냅샷 retention(투자자30·지표90·추천30일) | batch |
| **06:00** | `0 0 6 * * *` | DART corpCode 갱신 / KRX 마스터 refreshDaily / AI전략·수급알림 정리 | batch |
| **07:00/10/14/18/22** | `0 0 7,10,14,18,22` | WTI 원유 시세(Yahoo CL=F) | batch |
| **07:30** | `0 30 7` | 모닝 브리핑(StockAlert) + 아침 뉴스 배치(News) | batch |
| **08:00** | `0 0 8` | 추천 장전 캐시 무효화 / 실적공시 수집(DART) / 섹터 일별캐시 리셋 | batch·cache |
| **08:30** | `0 30 8` | 재무데이터 수집(4단계) / 마법공식·턴어라운드 알림 / 종목상태 동기화(StockStatus) | batch |
| **08:50** | `0 50 8` | 섹터·연속매수·AI전략 캐시 프리워밍(CacheWarmup/MarketCacheWarmer) | cache |
| **09:00** | `0 0 9` | **상승가속 알림**(delta≥+10 & ≥65) / AI분석 1회차 | batch |
| **09~15:30** | `0 2/10 8-19`·`0 */3 9-15`·`0 */2 9-15` | 수급급증 스냅샷(10분, task) / 섹터 스냅샷(3분, cache) / 포지션 낙폭(2분, task) | task·cache |
| **09·12·15시** | `0 0 9,12,15` | AI 주식분석 3회 | batch |
| **08~19시 매시(±30분)** | `0 0,30 8-19`·`0 0 8-19` | AI전략 스냅샷(스캘핑 30분 / 스윙·턴어라운드·가치 매시 정각) | cache |
| **08~17시 15분** | `0 */15 8-17` | 뉴스 크롤링 | batch |
| **08~19시 5분/10분** | `0 */5 8-19`·`0 0/10 8-19` | DART 장중 모니터(5분) / 관심종목 리스크(10분) | cache |
| **09~15시 5분/10분** | `0 */5 9-15`·`0 */10 9-15` | 관심종목 목표가(5분) / 복합조건(10분) | batch |
| **09~19시 5분** | `0 0/5 9-19` | 추천 가격도달 알림(±5%/±10%, ∓3%/∓5%) | batch |
| **11:30 · 14:00 · 17:00** | `0 30 11`·`0 0 14`·`0 0 17` | **추천 장중 스냅샷 3회** | batch |
| **15:35 / 15:38 / 15:40 / 15:55** | `0 35 15`/`0 38 15`/`0 40 15`/`0 55 15` | 섹터 전일스냅샷 / 재무 오후수집 / AI전략 종가확정 / 연속매수캐시 evict | cache·batch |
| **15:50** | `0 50 15` | 외국인·기관 일별 매매 수집(KIS) | batch |
| **16:00** | `0 0 16` | 일일 포트폴리오 리포트 / KIS 투자자 수집 | batch |
| **16:05** | `0 5 16` | 연속매수 캐시 재계산(오후) | cache |
| **16:30** | `0 30 16` | 실적공시 오후수집+알림(DART) / ADR·시장데이터 수집(MarketTiming) | batch·cache |
| **16:35 / 16:45** | `0 35 16`/`0 45 16` | 선점레이더 대량보유 알림 / 마감 시장상태 알림 | batch |
| **18:00** | `0 0 18` | 투자자 보완수집 / 마감 후 뉴스 | batch |
| **18:30 / 19:00** | `0 30 18`/`0 0 19` | 공매도 잔고 수집 / 공매도 5%+ 알림 | batch |
| **19:30** | `0 30 19` | **시그널 3거래일 평가**(alpha/MFE/MAE) + alpha 음수 시 risk 알림 | batch |
| **20:05 / 20:10** | `0 5 20`/`0 10 20` | 추천 마감 스냅샷(+7일 old 삭제) / 멀티컨빅션 알림 | batch |
| **20~23시 매시** | `0 0 20-23` | DART 야간 공시 모니터 | cache |
| **23:00 / 23:30** | `0 0 23`/`0 30 23` | 전종목 재무데이터 수집(~2분) / 배치 잡 헬스체크 | batch |
| **00~07시(화~토)** | `0 0 0-7 * * TUE-SAT` | DART 새벽 공시 모니터(전일 야간 커버) | cache |
| **금/은** | `0 0 9,12,18`+`0 30 15` / `0 1 9,12,18`+`0 31 15` | 금 09·12·15:30·18 / 은 09:01·12:01·15:31·18:01 | batch |

**주간/요일**: 월 08:00(`0 0 8 * * MON`) 실적 서프라이즈 알림 · 일 19:00(`0 0 19 * * SUN`) 주간 트레이딩 다이어리(REAL+VIRTUAL).

## 3.3 인터벌(fixedDelay) 잡
| 주기 | 작업 | 풀 |
|---|---|---|
| 30s | SmartMoney 실시간 워밍 | cache |
| 60s | 섹터거래/주도분석 워밍 · 시장상태(KOSPI/KOSDAQ/ADR) 워밍 | cache |
| 2분 | AI전략 워밍 · 섹터기회 워밍 | cache |
| 5분 | KIS 투자자 TOP 워밍 | cache |
| 10분 | 수급급증 워밍 | cache |
| 25분(initial 5분) | 종합추천 랭킹 워밍(CompositeSignal) | cache |
| 매시 정각(`0 0 * * * *`) | 시세 캐시정리 + Naver 블랙리스트 리셋 | cache |
| 1시간(initial 20분) | KRX 마스터 retryIfEmpty(<100건이면 재시드) | batch |
- 워밍 잡은 `isMarketHours()`(08:00~20:00) 밖이면 early-return (일부는 startup 예외).

## 3.4 자동매매 봇 — 활성 2전략 / 5크론(+청산봇 2 비활성) (`AutoTradingBotService`, taskScheduler)
> 전략 단위로는 **스캘핑·스윙 2개**. `@Scheduled` 크론 단위로는 **활성 5개**(아래 표) + **청산봇 매수·매도 2개 비활성**(`@Scheduled` 주석처리) = 코드상 cron 메서드 7개. (정합 가드: `AutoTradingBotTrackTest`)

| 트랙(크론) | cron | 의미 |
|---|---|---|
| 스캘핑 매수 | `*/30 * 9-11` | **09~11시(골든아워) 30초마다** — 순매수≥10억·거래량비≥200%·당일변동≥1.5%·RSI<55·이격<3%·갭<5% (모의 전용) |
| 스캘핑 매도 | `*/15 * 8-19` | 08~19시 15초마다 익절/손절 감시 |
| 스캘핑 청산 | `0 10 15` | **15:10 전량 청산**(종가 직전 정리) |
| 스윙 매수 | `0 0 14` | 14:00 — 연속매수3일+20일선지지+RSI<65, 최대 3종목 25% |
| 스윙 매도 | `*/30 * 8-19` | 08~19시 30초마다 — 익절+5%/손절-3%/트레일링/최대5일 |
| 청산봇 매수·매도 | (주석처리) | **비활성** |
- Hard rule(BuyChecklist 동일) + 킬스위치(`tradingSafetyAPI`) + Clock 주입(테스트 결정성).

## 3.5 자가치유 / 멀티인스턴스 락 (`SchedulerLockService`, Redis SET NX EX, fail-open)
- **KrxStockMasterSeeder**: ApplicationReady(<100건) 시드 + 06시 refreshDaily + 1시간 retryIfEmpty. 메트릭 `stock_master.last_seed_*`.
- **락 TTL(주요)**: 모닝브리핑/마감알림/모닝알림 15분 · 관심종목 4분(5분 cron) · 복합 8분(10분 cron) · 수급급증 5분(10분 cron) · DART 4분(5분 cron) · 실적/투자자수집/주간다이어리 30분~1시간. TTL<cron으로 누락 시 다음 cron 재시도.

## 3.6 알림 — 텔레그램 3채널
| 채널 | 내용(트리거) | 빈도 |
|---|---|---|
| **브리핑** | 모닝(07:30)·마감시장상태(16:45)·포트폴리오(16:00)·마법공식(08:30)·배치헬스(23:30) | ~5회 |
| **시그널** | 가격도달·수급급증(HOT/WARM/공통)·복합(4+)·선점레이더·실적서프라이즈·멀티컨빅션·상승가속 | 20~50회 |
| **리스크** | alpha 음수·공매도 5%+·DART 위험공시·킬스위치·배치 실패/행 | 1~3회 |

## 3.7 시간대 경계 파편화 (의도적 분리, 현황)
| 모듈 | 시간 | 비고 |
|---|---|---|
| MarketCalendarService.isRegularSession | 09:00~15:40 | KRX 실제 정규장은 **15:30** — 15:40은 종가단일가/버퍼 의도로 추정(주석 명시 필요). 고정 공휴일(음력 누락 가능) |
| AutoTradingBotService | 스캘핑 09~11/매도~19, 스윙 14:00/매도~19 | **봇 KRX 중심(의도)** |
| SectorTradingService(cron) | 09~15시 | 섹터 KRX 기준 |
| DartDisclosureMonitor | 08~19/20~23/00~07 | 24시간 커버 |
| MarketCacheWarmerService.isMarketHours | 08:00~20:00 | **NXT** |
| InvestorSurge/StockDetail/Recommendation 표시 | 08:00~20:00 | NXT |
| 프론트 VolumePowerGauge | 08:00~20:00 | NXT |

→ **표시/추천/수급/캐시워밍 = NXT(08~20)**, **봇·섹터·정규장 판정 = KRX(09~15:30/40)**. 의도적 분리.

---

# 4부. 핵심 상수 한눈에
| 항목 | 값 |
|---|---|
| STRONG_BUY / BUY / HOLD | 75 / 55 / 40 |
| 핵심 카테고리 | 4×20 = raw80 → 0~100 (validCount≥3) |
| STRONG+VALUE 보너스 | total≥75 & value≥12 → +2 |
| 시그널 hit | alpha≥0 & pct>0 (3거래일), 폴백 pct≥3% |
| 수급 HOT/WARM/공통 | 100억 / 50억 / 30억 |
| 공매도 컷 / 연속매수 / 복합신호 | <5% / ≥3일 / ≥3of5 |
| AI 가중 | 기술15 / 수급50 / 펀더35 (알림 90+) |
| 가격 캐시 | L1 KIS 1분·Naver 10분 / DB 5분 / KIS 5req/s |
| 시장국면 | BULL >+1% / BEAR <-1% (dead band 0.5) |
| Gemini 뉴스 | 일 500 한도 |
| 봇 스캘핑 | 09~11시 진입, 15:10 청산 |
| 봇 스윙 | 익절+5%/손절-3%/최대5일, 14:00 진입 |
| JWT | access 15분 / refresh 7일 |
| 일일 매수한도 | 500만(알림 100만) |

---

# 5부. 미해결 / 다음 작업
- **배수오류(×10) — 가드 강화 완료(2026-06-04)**: 기존 가드는 당일 밴드 체크 **단일** 이라, ×10이 응답 전체에 일괄로 걸리면 현재가가 [저가,고가] 안에 머물러 **검출 불가능한 맹점**이 있었음. → **DB 앵커 배수(직전 저장가 대비 ≥5배/≤0.2배) + 전일대비율 ±31% 초과** 그물 추가. 응답 내부 필드끼리의 비교는 일괄 스케일링에 무력하므로 외부 앵커(DB)가 핵심. 여전히 **로깅만**(미보정).
- **×10 원인 — UN 통합시세 종목군 의심**: 시세 경로만 `FID_COND_MRKT_DIV_CODE=UN`(KRX+NXT 통합), 투자자/프로그램매매는 `J`(KRX 단독). ×10 종목이 **NXT/이중상장/특정 구간**에 한정되는지 `[가격이상]` 로그로 대조 → 그렇다면 UN 응답 필드 규약 차이(파싱)로 확정. 다음 진단 단계: 이상치 발생 시 **UN vs J 동시 조회 비교** 또는 NXT 플래그 동봉 로깅. (※ 샘플 종목코드 확보되면 패턴 대조 가능)
- StockDetailServiceTest 공용 시세 경로로 갱신 완료.
- **화면 간 가격 일치**: getQuick/Heavy 공용 `stockPriceService` 경유로 정리됨(목록 동일 캐시). 결론카드 vs 헤더 점수 소스 차이는 캡션 명시로 혼동 정리.
- 백테스트 강화 / ATR 포지션 사이징 / 전략·섹터 적중률 추적 / StockDetailDashboard(4,707줄) 분리.
- 청산봇(executeClosing*) 주석처리 상태 — 재활성 여부 결정 필요.
- MarketCalendar 음력 공휴일 보강 / KRX 정규 애프터마켓(예정) 반영 시 KRX·NXT 경계 재정렬.
