# 주식 플랫폼 — 상세 가이드 (A-Z)

> **Version**: 2026.05.14 Phase 36
> 화면 / 컴포넌트 / 백엔드 서비스 / DB 스키마 / 로직 흐름 / 스케줄 / 알림까지 모두 a-z.
> 외부 AI 컨텍스트 요약은 [`SYSTEM_OVERVIEW.md`](./SYSTEM_OVERVIEW.md) 참고.
> 본 문서는 운영자/개발자가 화면→코드→DB까지 추적할 때 사용.

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [화면 (라우트) 목록](#2-화면-라우트-목록)
3. [화면별 위젯 + 호출 API](#3-화면별-위젯--호출-api)
4. [Vue 컴포넌트 카탈로그](#4-vue-컴포넌트-카탈로그)
5. [백엔드 서비스 상세](#5-백엔드-서비스-상세)
6. [점수 산정 로직](#6-점수-산정-로직)
7. [자동매매 봇 룰](#7-자동매매-봇-룰)
8. [Risk & Safety Management (안전 가드 11)](#8-risk--safety-management-안전-가드-11)
9. [시그널 적중률 추적](#9-시그널-적중률-추적)
10. [알림 시스템 (텔레그램 3채널)](#10-알림-시스템-텔레그램-3채널)
11. [API 엔드포인트](#11-api-엔드포인트)
12. [DB 스키마 (핵심 테이블)](#12-db-스키마-핵심-테이블)
13. [스케줄 작업 (60+ @Scheduled)](#13-스케줄-작업-60-scheduled)
14. [인프라 (캐시 / 스케줄러 풀 / WebSocket)](#14-인프라-캐시--스케줄러-풀--websocket)
15. [핵심 사용자 흐름](#15-핵심-사용자-흐름)
16. [Phase 변경 이력 (1~36)](#16-phase-변경-이력-136)

---

## 1. 시스템 개요

한국 주식(KRX) 종목 발굴 / 분석 / 모의·실전 자동매매 통합 플랫폼.
**Spring Boot 4.0** + **Vue 3** + **MariaDB** + **Redis(L2)** + **KIS WebSocket(옵션)**.

### Core Design Principle

1. 하나의 종목에 대해 **여러 시간 척도/차원의 답변을 동시에 제공** (단기 모멘텀 + 장기 가치)
2. 모든 강력 추천에는 **적중률 + 체크리스트 + 리스크** 함께 제시
3. 시그널 실력은 **시장 베타와 분리해 평가** (BM alpha)
4. **봇 hard rule 은 수동 매매에도 동일 적용** (필수/가산 차등)
5. **데이터 신선도가 깨지면 거래를 멈춘다**
6. **신선도와 리스크는 모든 의사결정의 최우선** — 수익 추구보다 자본 보존이 먼저

---

## 2. 화면 (라우트) 목록

라우터: `frontend/src/main.js` L33-245

| 경로 | 컴포넌트 | 메타 | 설명 |
|---|---|---|---|
| `/user` | UserDashboard | requiresAuth | 메인 대시보드 (투자 + 일상 관리) |
| `/stock-dashboard` | StockTradingDashboardV2 | requiresAuth | V2 통합 (4탭: 개요/분석/뉴스/매매) |
| `/stock/:stockCode` | StockDetailDashboard | requiresAuth | 종목 종합 상세 (4708줄) |
| `/global-futures` | GlobalFuturesPage | requiresAuth | 선물·금·은·원유 통합 |
| `/ai-strategy` | AiStrategyDashboardPage | requiresAuth | AI 4분할 전략 |
| `/paper-trading` | PaperTradingPage | requiresAuth | 모의/실전 자동매매 |
| `/investor-trades` | InvestorTradePage | requiresAuth | 투자자 매매 |
| `/consecutive-buy` | ConsecutiveBuyPage | requiresAuth | 연속매수 종목 |
| `/investor-surge` | InvestorSurgePage | requiresAuth | 수급 급증 |
| `/sector` | SectorTradingPage | requiresAuth | 섹터별 거래대금 |
| `/earnings-screener` | EarningsScreenerPage | requiresAuth | 실적 스크리너 |
| `/market-timing` | MarketTimingPage | requiresAuth | ADR 시장 타이밍 |
| `/trading-indicators` | TradingIndicatorsPage | requiresAuth | 글로벌 지표 |

**리다이렉트**:
- `/dashboard` → `/user` or `/admin` (역할별)
- `/stock-detail` → `/stock-dashboard` (legacy)
- `/oil` → `/global-futures` (통합)
- `/investor-stock/:code` → `/stock/:code` (탭 통합)

---

## 3. 화면별 위젯 + 호출 API

### § StockDetailDashboard.vue (4708줄, 가장 중요)

**헤더**:
- 종목명 + 코드 + **신호 뱃지 (X/5 매칭)** — 클릭 시 분해
- 현재가 + 등락률 (컬러)
- **듀얼 점수**: 단기 트레이딩 + 중장기 펀더멘털

**상단 (phase 13/15 도입)**:
- **`StockConclusionCard`** — 4-level 결론 + 6 factor + 적중률 + 신선도 신호등

**탭 1 — 종합 분석**:
| 위젯 | API | 용도 |
|---|---|---|
| StockBriefingHeadline | 로컬 계산 | 펀더멘털+AI+수급 행동권고 |
| StockRiskCard | `/risk/check` | DART 공시+뉴스+AI 리스크 |
| Volume Profile | `quantTaAPI.volumeProfile()` | 가격대별 거래량 (POC/VA) |
| 지지/저항 | `quantTaAPI.supportResistance()` | 피벗 강도 표시 |
| RelatedStocksList | `quantTaAPI.relatedStocks()` | 같은 섹터 상관 0.5+ 종목 |
| ChartPatternList | `quantTaAPI.patterns()` | 더블탑·H&S·삼각수렴 검출 |
| 차트 (Chart.js) | (자체) | 일봉 + MA20/60/120 + 볼린저 + 지지선 |
| 핵심 재무 | (heavy API) | PER/PBR/EPS/BPS/시가총액 (TTM) |
| Peer Group | (heavy API) | 동일 섹터 PBR 비교 |
| 빠른 요약 바 | 로컬 | RSI/MA/외인기관/AI점수 |

**탭 2 — 투자자 동향**: 주가 vs 누적 순매수 + 장중 수급

**호출 API**: `stockDetailAPI.getQuick()` (3~5초) → `.getHeavy()` (캐시 1초/미스 10~30초) → `getDiagnosis()` → `quantTaAPI.*()`

### § StockTradingDashboardV2.vue

| 탭 | 위젯 | API |
|---|---|---|
| **개요** | 시장 상태 / TOP10 추천 / 저평가 / 강세 섹터 | `marketAPI.getStatus()` / `recommendationAPI.getTop5()` / `getValueTop10()` / `quantTaAPI.strongSectors()` |
| **분석** | 마법공식 / 차트패턴 스캔 / 퀀트 TA | `quantTaAPI.compositeBatch()` / `scanPatterns()` |
| **뉴스** | (별도 페이지) | - |
| **매매** | 모의/실전 임베드 | (PaperTradingPage 컴포넌트 재사용) |

### § PaperTradingPage.vue

| 탭 | 위젯 | API |
|---|---|---|
| 🤖 모의투자 | 계좌 요약, 포트폴리오, 거래 내역 (수수료+세금 컬럼, phase 26) | `paperTradingAPI.getAccountSummary()` / `getPortfolio()` / `getTradeHistory()` |
| 🔴 실전투자 | 실계좌 (KIS API) | `getRealPortfolio()` |
| 📊 봇 성과 | **BotPnlChart (phase 29)** + 손익 표 + MDD (phase 18) + 종목별/엑시트 사유별 | `getBotPerformance(days, mode)` |
| 📝 주간 리포트 | AI 생성 | `getLatestWeeklyReport()` |

### § GlobalFuturesPage.vue

- 글로벌 선물 (나스닥·S&P·닛케이·HSI), 금/은/원유 멀티 차트
- KOSPI 영향 분석 배너 (방향성 점수 + 리스크 팩터)
- VIX·10년물·Fear&Greed
- API: `globalFuturesAPI.*` / `goldAPI` / `silverAPI` / `oilAPI`

### § AiStrategyDashboardPage.vue

- 4분할 전략 점수 (스캘핑/스윙/턴어라운드/가치)
- AI 종합 매력도 (0~100)
- 전략별 TOP10
- API: `aiStrategyAPI.getLatest()` / `getPerformance()`

---

## 4. Vue 컴포넌트 카탈로그

`frontend/src/components/v2/` — 주요 컴포넌트:

| 컴포넌트 | 줄 수 | 용도 | Props / Emits |
|---|---|---|---|
| **StockConclusionCard** | 318 | 결론 카드 + 적중률 + 신선도 (phase 5/13/15/23) | stockCode |
| **BuyChecklistModal** | 194 | 매수 체크리스트 (phase 6/19) | stockCode, @close |
| **ChartPatternList** | 117 | 차트 패턴 검출 (phase 27 분리) | patterns |
| **RelatedStocksList** | 89 | 관련 종목 (phase 28 분리) | stocks, @select |
| **BotPnlChart** | 110 | 봇 손익 차트 (phase 29) | dailyPnl |
| StockBriefingHeadline | 181 | 펀더멘털/AI/수급 종합 멘트 | diagnosisData, aiAnalysis |
| StockRiskCard | 346 | DART·뉴스·AI 리스크 | stockName, stockCode |
| DashboardHeader | 220 | GNB 4탭 | activeTab |
| StockSearchModal | 243 | 종목 검색 (Ctrl+K) | @select, @close |
| TradingSafetyWidget | 297 | 매매 안전장치 (ADMIN) | - |
| SectionTotalRecommendation | 205 | 5개 신호 매칭 랭킹 | - |
| SectionQuantTa | 913 | 기술 분석 (RSI/MA/볼린저) | - |
| SectionMarketMap | 848 | 섹터·종목 히트맵 | - |
| MagicFormulaSmartTable | 962 | 마법 공식 스크리너 | - |
| SectionBacktest | 510 | AI 전략 백테스트 | - |
| SectionLiveSurge | 486 | 수급 급증 실시간 | - |
| ForecastDetailModal | 422 | AI 예측 상세 | - |

---

## 5. 백엔드 서비스 상세

### § AutoTradingBotService (자동매매 봇)

`backend/.../service/AutoTradingBotService.java`

**핵심 메서드**:
- `checkScalpingEntry()` — 스캘핑 매수 조건 (필수 3개 + 보조 2/4)
- `checkSwingEntry()` — 스윙 매수 (연속매수 + MA20 + RSI)
- `executeScalpingSellLogicInternal()` — 매도 평가 + price stale 가드 + push 캐시 우선
- `executeScalpingBuyLogicInternal()` — 진입 + 직전 가격 검증 + 신선도 가드
- `checkKillSwitch()` — 안전 가드 일괄 체크

**임계 상수**:
```
MIN_NET_BUY_AMOUNT = 10억           SCALPING 진입 순매수
MIN_INTRADAY_RANGE = 1.5%           변동폭
SURGE_DATA_MAX_AGE_MINUTES = 15     phase 1 신선도
PRICE_STALE_SECONDS = 60            phase 1 가격 신선도
STRONG_BUY_THRESHOLD = 75           (RecommendationService 동기화)
MAX_HOLDING_STOCKS = 3
```

### § RecommendationService (TOP10 종합 추천)

`backend/.../service/RecommendationService.java`

**핵심 메서드**:
- `getTop5()` — 메모리 캐시 → DB 폴백 → 백그라운드 fresh 계산 (cold start race 방어)
- `calculate()` — 4 카테고리 점수 + normalizeScore + 페널티 파이프라인
- `applyRiskPenalty()` — 상위 30 후보 DART 공시 검사 (-5 + ⚠리스크공시 태그)
- `applyRealtimeChecks()` — MA20 하회 / 골든크로스 태그 재검증 / 수급괴리 -3
- `applyNewEntryPenalty()` — phase 31c: 어제 스냅샷 밖 + 5일 +15% → -5 (추격 차단)
- `saveSnapshotInternal()` — TOP10 DB 저장 + STRONG_BUY/BUY record (phase 12)
- `detectAndAlertNewStrongBuys()` — 평일 09:00 상승 가속 알림 (phase 31b 재정의)
- `calculateValueTop10()` — 저평가 별도 트랙

**입력 서비스** (의존):
- AiStrategySnapshotService, InvestorTradeService, EarningSurpriseService
- QuantScreenerService, TechnicalIndicatorService, SectorTradingService

**추격매수 방지 (phase 31)** — 상세는 §6 점수 산정 로직 참고. 과열 페널티, 수급 곡선
뒤집기, tie-break delta desc, 신규 진입 감점, 09시 알림 가속 재정의, 시장분위기 일괄가산
제거.

### § StockConclusionService (룰 기반 결론)

`backend/.../service/StockConclusionService.java`

**5단계 룰 + 6가지 충돌 해설** (phase 22b):

| 룰 # | 조건 | level | headline |
|---|---|---|---|
| 1 | total ≥ 75 | STRONG_BUY | 단기 모멘텀 + 다수 합의 |
| 2 | value ≥ 12 + total < 55 | HOLD | 장기 저평가 분할 매수 후보 |
| 3 | supplyDemand ≥ 15 + technical < 8 | BUY | 수급 강, 추격 신중 |
| 4 | total ≥ 55 | BUY | 매수 신호 양호 |
| 5 | total < 55 | WAIT | 관망 권장 |

**`detectConflicts()`** (phase 22b + 33) — 8가지 조합 멘트:
1. 단기 강 + 장기 매우 약 → 익절 3% 내
2. 종합 강 + 기술 약 → 고점 추격 경고
3. 장기+수급 강 + 기술 약 → 분할 매수
4. 실적 강 + 시장 관심 부족 → 매집 후보
5. 섹터 강 + 차트 약 → 섹터 ETF 대안
6. **(phase 33)** tags 에 "후반" + total ≥ BUY → 수급 5일+ 카운터파티 만든 단계 경고
7. **(phase 33)** aiStrategy>0 + total < BUY → AI 발굴 후보, 객관 지표 가속 대기
8. 모든 카테고리 평범 → 더 매력 후보 우선

### § BuyChecklistService (매수 체크리스트)

`backend/.../service/BuyChecklistService.java`

**필수 2개 + 가산 3개 = 5개 항목** (phase 19):

| key | label | 차원 | 임계 | 종류 |
|---|---|---|---|---|
| tradable | 거래 가능 상태 | META | 정상 거래 | 필수 |
| shortSelling | 공매도 비율 | SHORT | < 5% | 필수 |
| consecutiveBuy | 외국인/기관 연속매수 | SHORT | ≥ 3일 | 가산 |
| compositeSignal | 복합 신호 매칭 | MID | ≥ 3/5 | 가산 |
| conclusion | 종합 결론 | MID | BUY 이상 | 가산 |

**등급**: 필수 미충족 → NOT_RECOMMENDED, 가산 3/3 STRONG / 2/3 MODERATE / 1/3 CAUTION / 0/3 NOT_RECOMMENDED

### § SignalOutcomeService (시그널 적중률)

`backend/.../service/SignalOutcomeService.java`

**3단계 흐름**:
1. `record(type, code, name, score, price)` — 시그널 발생 시 가격 + KOSPI 동시 기록
2. `evaluatePendingSignals()` — 매일 19:30 batch. 3거래일 후 가격 + KOSPI 조회 → pct/alpha/MFE/MAE 계산
3. `getAccuracy(days)` — 시그널 타입별 통계 집계

**hit 기준** (phase 20):
- `alpha_3d >= 0 AND pct_change_3d > 0` (시장 이김 + 절대 수익)
- BM 데이터 없으면 기존 +3% 폴백

**추적 시그널 타입 8종**:
- STRONG_BUY / BUY (RecommendationService, phase 12)
- SURGE_HOT / SURGE_WARM (InvestorSurgeService, phase 16)
- COMPOSITE_4PLUS / COMPOSITE_5OF5 (CompositeSignalService, phase 16)
- AI_STRONG / AI_BUY (AiStockAnalysisService, phase 24)

### § AiStockAnalysisService

Gemini + 기술 15% / 수급 50% / 펀더멘털 35% 가중. 09:00/12:00/15:00 일 3회. 90+ → 풀매수 알림.

### § AiStrategySnapshotService

4전략 — SCALPING(2분) / SWING/TURNAROUND/VALUE(30분).

### § InvestorSurgeService

10분 cron (08:00~20:00). HOT(100억+) / WARM(50억+) / NORMAL. 쌍끌이 30억+.

### § CompositeSignalService

5신호 — 차트패턴/지지선/가치영역/수급(3일 연속)/AI(60+점). 30분 캐시.

### § KisWebSocketService (옵션)

`@ConditionalOnProperty(kis.websocket.enabled=true)`. java.net.http.WebSocket. 자동 재연결(백오프 60s). **Gap-filling** (phase 30) — 재연결 시 REST 폴백 일괄 동기화.

### § BotPerformanceService

`/api/paper-trading/bot-performance?days=30&mode=VIRTUAL`:
- winRate / totalPnl / avgPnl / maxWin / maxLoss / profitFactor
- **maxDrawdown** (phase 9) — 누적 손익 peak-to-trough
- avgHoldingMinutes
- dailyPnl[] / stockPnl[] / exitReasonStats{}

### § GeminiService

CircuitBreaker(`geminiApi`) + dailyCount 추적 (phase 8). 80%/90% 도달 → 텔레그램 risk.

### § StockPriceService

KIS REST → 네이버 폴백. 캐시 1분(KIS) / 10분(Naver). `fetchedAt` 필드로 신선도 추적 (phase 1).

---

## 6. 점수 산정 로직

### normalizeScore (phase 11 + 14)

```
TOTAL_CATEGORIES = 4
rawCap = 80
scaled = raw × 100 / rawCap

if validCount >= 4:
    return min(100, scaled)
else:
    cap = 25 + 75 × (validCount / 4)
    return min(cap, scaled)
```

| validCount | full raw | scaled | cap | 결과 |
|---|---|---|---|---|
| 4/4 | 80 | 100 | (미적용) | **100** |
| 3/4 | 60 | 75 | 81 | 75 |
| 2/4 | 40 | 50 | 62 | 50 |
| 1/4 | 20 | 25 | 43 | 25 |

### 카테고리 (각 0~20점)

| 카테고리 | 입력 |
|---|---|
| earnings | 어닝 서프라이즈 + 매출/영업이익 추세 |
| supplyDemand | 외국인/기관 순매수 추세 |
| technical | RSI / MA / 모멘텀 |
| sectorMomentum | 섹터 거래대금 INFLOW/OUTFLOW |

**별도 트랙** (총점 산식 제외):
- valueStability (0~20, calculateValueTop10) — PBR/ROE/부채/흑자
- aiStrategy (태그용)

### 임계값

| 점수 | 레벨 |
|---|---|
| ≥ 75 | STRONG_BUY (강력매수) |
| 55~74 | BUY (매수) |
| 40~54 | HOLD (관망) |
| < 40 | WAIT (제외) |

### 추격매수 방지 페널티 (phase 31)

운영 중 "추천 상위 종목 = 이미 한참 오른 종목 + 다음날 조정" 패턴이 반복 관측되어 산식
자체를 재정의. 모든 페널티는 해당 카테고리 점수 안에서 차감(음수 클램프) → 카테고리=0 이
되면 validCount 에서 빠져 자연 탈락.

**1) 과열 페널티 (`scoreTechnical`)**

| 페널티 | 조건 | 차감 | 도입 |
|---|---|---|---|
| RSI 과열 | RSI ≥ 75 | technical −5 | 31 (P0-1) |
| 볼린저 상단 돌파 | `isBreakout=true` | technical −3 | 31 (P0-1) |
| 5일 가속 (모든 종목) | 5거래일 누적 ≥ +20% | technical −5 | 31 (P0-1) |
| 5일 가속 + 신규 진입 | 어제 스냅샷 밖 + 5일 ≥ +15% | technical −5 (중첩) | 31c (P2) |

**2) 수급 곡선 뒤집기 (`scoreSupplyDemand`) — phase 31 P0-2**

외국인/기관 연속매수 가점을 "3일 정점, 5일+ 후반 축소" 곡선으로 변경. 5일+ 연속매수는
이미 카운터파티가 만들어진 후 단계로 간주.

| 일수 | 외국인 (이전→변경) | 기관 (이전→변경) |
|---|---|---|
| 2일 | 4 → **8** | 3 → **6** |
| 3일 | 6 → **10** (정점) | 4 → **8** (정점) |
| 4일 | 8 → 6 | 6 → 5 |
| 5일+ | 10 → **4** | 8 → **3** |

태그에 "시작 / 초기 / 후반" 페이즈 표시.

**3) 정렬 tie-break (`calculate`) — phase 31 P0-3**

```
① normalized total desc
② delta (오늘 − 어제 스냅샷 점수) desc   ← phase 31 추가
③ changeRate desc                       ← 최후 보루
```

"어제 60 → 오늘 78" 같은 추천 풀 안에서의 가속이 "어제 78 유지" 보다 우선. prev 스냅샷
비어있는 콜드스타트는 ③ 으로 자연 위임.

**4) 섹터 시장분위기 일괄가산 제거 (`scoreSectorMomentum`) — phase 31b P1-2**

기존엔 `marketMoodBonus(2~6)` 를 모든 종목에 동일 가산해 변별력 깎임(강세장에 추천 다
떠있다 다음날 다 조정의 구조적 원인). 일괄 가산만 제거하고 marketMoodBonus 계산은 유지
하되 운영 로그에만 노출. "장중 최소 2점 폴백"(ss==0 케이스만 영향)은 유지.

**5) 09시 알림 재정의 (`detectAndAlertNewStrongBuys`) — phase 31b P1-1**

| | 이전 (꼭지 알림) | 변경 (가속 알림) |
|---|---|---|
| 조건 | 어제 75+ 없고 오늘 75+ 진입 | Δ ≥ +10 & 오늘 ≥ 65 & 어제 스냅샷 존재 |
| 의미 | 막 피크 친 종목 | 상승 가속 중 + 과열 전 |
| 정렬 | 입력 순서 | Δ desc |
| 안전장치 | 없음 | prev 스냅샷 비어있으면 스킵 (콜드스타트 스팸 방지) |
| 라벨 | 강력매수알림 | 상승가속알림 |

**6) 필터/표시 점수 일관성 — phase 31d**

이전엔 컷 필터의 raw 합산에만 `valueStability` 가 포함되고 `toDto`/`getNormalizedTotal`
에선 빠져 "55점 컷 통과했는데 UI 표시 점수는 50점" 일관성 깨짐 발생. v7 (5→4 카테고리)
전환 시 필터 라인만 누락된 것으로 추정. phase31d 에서 필터도 4 카테고리로 통일.

### 시장 국면 적응형 가중치 (phase 34 + 35 hysteresis)

`scoreSectorMomentum` 가 전체 섹터 평균 등락률로 시장 국면 판정 후 반환, `calculate()` 끝부분
`applyMarketRegimeWeighting(scoreMap, regime)` 가 카테고리 점수에 multiplier 적용.

| regime | 판정 | earnings | supplyDemand | technical | sector |
|---|---|---|---|---|---|
| BULL | 섹터 평균 > +1% | ×0.95 | ×1.10 | ×1.05 | ×1.00 |
| BEAR | 섹터 평균 < −1% | ×1.20 | ×0.85 | ×0.90 | ×0.80 |
| SIDEWAYS | 그 외 | ×1.00 | ×1.00 | ×1.00 | ×0.90 |

**phase 36**: BULL 폭 ±0.20 → ±0.10 으로 좁힘 (운영 데이터 STRONG_BUY 0건 부작용 후 조정). 신규
진입 페널티(phase 31c)도 regime BULL 이면 스킵 — BULL 강세장에서 5일+15% 가 정상 추세 종목에
서도 흔하므로 무차별 페널티 차단.

- 점수 자체에 적용 → UI/정렬 일관 (phase 31d 원칙 유지)
- SIDEWAYS 의 섹터 0.9 는 phase 31b 시간 척도 불일치 부분 보정
- BULL/BEAR 시 tag `regime:BULL` / `regime:BEAR` 명시
- multiplier 4개 모두 1.0 으로 바꾸면 즉시 disable

**hysteresis (phase 35)** — dead band 0.5 로 임계 근처 흔들림 차단:
- 직전 BULL AND avg > −0.5 → BULL 유지
- 직전 BEAR AND avg < +0.5 → BEAR 유지
- 그 외 → fresh 판정 채택

`lastRegime` volatile field. 강한 반전(±1.0 통과 + dead band 위반)은 한 번에 전환 가능.

### STRONG_BUY + 강한 가치 보너스 (phase 34)

`total ≥ 75` AND `valueStability ≥ 12` → 정규화 점수 +2 (cap 100). `getNormalizedTotal`(정렬)
+ `toDto`(UI) 양쪽 일관. tag `STRONG+VALUE`. v7 분리 철학은 유지하면서 희소한 모멘텀+가치
교집합만 우대.

### MDD 기반 포지션 스케일 (phase 34, 인프라)

`BotPerformanceService.recommendPositionScale(mode, days, mddLimit)` — 최근 N일 MDD 절대값을
사용자 지정 mddLimit 으로 나눈 비율 기반 0.50~1.00 배율. 봇 코드에서 호출은 사용자 직접 결정
(잘못 끼우면 매매 사고).

---

## 7. 자동매매 봇 룰

| 전략 | 모드 | 진입 | 매도 |
|---|---|---|---|
| **스캘핑** | 모의 전용 | 순매수 ≥ 10억 + 양봉 + 변동폭 ≥ 1.5% + 보조 2/4 (체결강도/RSI/이격도/갭) | 손절 -1.5% / 익절 +1.2% 절반 / 트레일링 -1% / 타임컷 15~20분 |
| **스윙** | 모의 + 실전 | 외인/기관 3일+ 연속매수 + MA20 지지 + RSI < 65 | 익절 +5% / 손절 -3% / 최대 5일 |
| ~~종가 매수~~ | 비활성 | (2026-09-14 거래시간 연장 후 재설계) | |

**활성 시각**: 스캘핑 09:45~10:30 골든타임 / 스윙 14:00 체크

---

## 8. Risk & Safety Management (안전 가드 11)

`AutoTradingBotService.checkKillSwitch()` 및 관련 가드들. 시그널 강도가 좋아도 발동 시 진입 차단.

| 가드 | 임계 | 발동 시 |
|---|---|---|
| 킬스위치 (계좌) | 일일 손실 -3% | 당일 매수 정지 |
| 스캘핑 킬스위치 | 일일 -1.5% | 스캘핑 모드만 정지 |
| 연속 손절 정지 | 3회 연속 손절 | 당일 매수 정지 |
| VIX 일시정지 | 글로벌 변동성 급등 | 매수 정지 |
| KOSPI 하락 정지 | 지수 하락 | 매수 정지 |
| **surge 신선도 가드** | 스냅샷 15분 stale | 매수 보류 + 텔레그램 risk (phase 1) |
| **가격 신선도 가드** | 매도 평가 가격 60초 stale | KIS 재조회. 실패 시 매도 보류 (phase 1) |
| 진입 직전 가격 검증 | 신호 평가 후 ±2% 변동 | 진입 스킵 |
| 섹터 OUTFLOW 차단 | 자금 유출 섹터 | 매수 거절 |
| 거래정지/상폐 차단 | StockStatusService | 매수 거절 (필수 체크리스트, phase 19) |
| 공매도 5%+ 차단 | 비율 ≥ 5% | 매수 거절 (필수 체크리스트, phase 19) |

수동 매매도 동일 룰 — `BuyChecklistService`가 사용자 화면에 노출.

---

## 9. 시그널 적중률 추적

### 흐름

1. **시그널 발생 시 INSERT** (signal_outcome 테이블):
   - STRONG_BUY/BUY ← `RecommendationService.saveSnapshotInternal` (phase 12)
   - SURGE_HOT/WARM ← `InvestorSurgeService.collectIntradaySnapshot` (phase 16)
   - COMPOSITE_4PLUS/5OF5 ← `CompositeSignalService.evaluate` (phase 16)
   - AI_STRONG/AI_BUY ← `AiStockAnalysisService.analyze` (phase 24)
   - 같은 (type/stock/date) 중복 차단

2. **3일 후 batch 평가** (매일 19:30 KST, batchScheduler):
   - 종목 현재가 + KOSPI 현재가 조회
   - `pct_change_3d` = (가격 변동률)
   - `bm_return_3d` = (KOSPI 변동률)
   - `alpha_3d` = pct - bm
   - **hit**: alpha ≥ 0 AND pct > 0 (phase 20)
   - **MFE/MAE** (phase 25): 일봉 OHLC 조회 → 3거래일 최고/최저 누적

3. **조회**: `GET /api/signal-outcomes/accuracy?days=30`
4. **UI**: 결론 카드에 level별 적중률 한 줄 + 색상 코드 (phase 15)
   - ≥ 60% 녹색 / 40~60% 노랑 / < 40% 빨강

---

## 10. 알림 시스템 (텔레그램 3채널)

`TelegramNotificationService.sendBriefing / sendSignal / sendRisk`

| 채널 | 환경변수 | 알림 |
|---|---|---|
| **브리핑** | `TELEGRAM_BOT_TOKEN` | 모닝브리핑(07:30) · 마감(16:45) · 시장상태 · 헬스체크 |
| **시그널** | `TELEGRAM_BOT_TOKEN_SIGNAL` | 매수신호 · 마법공식 · 턴어라운드 · 봇 진입/매도 |
| **리스크** | `TELEGRAM_BOT_TOKEN_RISK` | 킬스위치 · 공매도경보 · DB 저장실패 · stale surge · **Gemini 한도 80%/90% (phase 8)** |

---

## 11. API 엔드포인트

### 종목 상세
```
GET /api/stock/{code}/summary       종합 상세
GET /api/stock/{code}/quick         시세+수급+차트+재무 (3~5초)
GET /api/stock/{code}/heavy         리스크+AI+피어 (캐시)
GET /api/stock/{code}/conclusion    룰 결론 (phase 5)
GET /api/stock/{code}/checklist     매수 체크리스트 (phase 6)
```

### 추천
```
GET /api/recommendation/top5         TOP 10 추천
GET /api/recommendation/value-top10  저평가 TOP 10
GET /api/recommendation/strong-value-frequency?days=30
  (phase 35) STRONG_BUY+value≥12 일자별 빈도 — 보너스 dead code 검증용
```

### 자동매매
```
GET  /api/paper-trading/account/summary
POST /api/paper-trading/account/initialize
GET  /api/paper-trading/portfolio
GET  /api/paper-trading/trades              거래 내역
GET  /api/paper-trading/bot-performance     봇 성과 (MDD 포함)
POST /api/paper-trading/bot/start           봇 시작
POST /api/paper-trading/bot/stop
```

### 시그널 적중률
```
GET /api/signal-outcomes/accuracy?days=30
GET /api/signal-outcomes/compare?signalType=STRONG_BUY&cutoff=2026-05-14&windowDays=30
  (phase 32) — cutoff 전후 hit-rate/alpha/MFE/MAE 비교, phase 변경 검증용
GET /api/signal-outcomes/timeseries?signalType=STRONG_BUY&days=60
  (phase 33) — 일별 hit-rate/alpha 시계열, 프론트 그래프용
```

### 수급
```
GET /api/investor-trade/top-trades
GET /api/investor-trade/consecutive-buy
GET /api/investor-trade/surge
```

### AI 전략
```
GET /api/ai-strategy/latest
GET /api/ai-strategy/latest/{type}
GET /api/ai-strategy/performance
```

---

## 12. DB 스키마 (핵심 테이블)

| 테이블 | 용도 | 핵심 컬럼 |
|---|---|---|
| **recommendation_snapshot** | TOP10 스냅샷 | stockCode, totalScore, earnings, supplyDemand, technical, sectorMomentum, valueStability, rankOrder, snapshotAt |
| **signal_outcome** (V26+27+28) | 시그널 적중률 | signalType, stockCode, signalDate, priceAtSignal, **bmPriceAtSignal**, priceAfter3d, pctChange3d, **bmReturn3d**, **alpha3d**, **maxHigh3d**, **maxLow3d**, **mfePct3d**, **maePct3d**, hit, evaluatedAt |
| **bot_trading_position** | 봇 포지션 메타 | strategy(SCALPING/SWING), stockCode, buyPrice, highPrice, buyTime, halfSold, timeExtended, tradingMode, version(낙관적 잠금) |
| **virtual_trade_history** | 모의 거래 기록 | accountId, stockCode, tradeType, quantity, price, totalAmount, commission, tax, profitLoss, tradeReason, tradeDate |
| **investor_intraday_snapshot** | 수급 스냅샷 (10분) | snapshotDate, snapshotTime, stockCode, investorType, netBuyAmount, currentPrice, changeRate |
| **ai_strategy_snapshot** | AI 전략 | strategyType, stockCode, geminiScore, candidates(JSON), snapshotAt |
| **earnings_disclosure** | DART 공시 | corpCode, corpName, stockCode, reportNm, rceptDt, disclosureType, fiscalYear |
| **trading_safety** | 매매 한도/킬스위치 | dailyBuyLimit, alertThreshold, killSwitchActive |
| **stock_master** | 종목 마스터 (KRX) | stockCode, stockName, marketType, sectorName, isActive |

---

## 13. 스케줄 작업 (60+ @Scheduled)

`SchedulingConfig` — 3개 풀 분리 (phase 3).

### taskScheduler (@Primary, 16, 트레이딩 우선)
```
*/30 * 9-11 * * MON-FRI    스캘핑 매수 사이클 (30초)
*/15 * 8-19 * * MON-FRI    스캘핑 매도 + 포지션 감시 (15초)
0 0 14 * * MON-FRI         스윙 매수 체크
0 10 15 * * MON-FRI        15:10 스캘핑 청산
*/30 * 8-19 * * MON-FRI    킬스위치 모니터링
0 2/10 8-19 * * MON-FRI    수급 급증 스냅샷 (10분)
0 */2 9-15 * * MON-FRI     포지션 급락 감시
```

### cacheScheduler (16, 시장 데이터 워밍)
```
30초 fixedDelay     Smart Money 실시간
5분 fixedDelay      투자자 매매동향
60초 fixedDelay     섹터 거래대금 / 시장 상태
2분 fixedDelay      AI 전략 스냅샷 / 섹터 기회
10분 fixedDelay     수급 급증
0 0,30 8-19 * * MON-FRI   AI 전략 (30분)
0 */3 9-15 * * MON-FRI    섹터 분석
0 30 16 * * MON-FRI       ADR 시장 지표
```

### batchScheduler (16, 일일 리포트 + 정리)
```
0 30 19 * * MON-FRI       시그널 평가 (3거래일 후, phase 10/20/25)
0 0 16 * * MON-FRI        일일 리포트
0 30 23 * * MON-FRI       헬스체크 텔레그램
0 50 15 * * MON-FRI       투자자 매매 데이터 수집
0 30 16 * * MON-FRI       실적 공시 + 관심종목 알림
0 0 3 * * MON-FRI         배치 작업 정리 (7일+)
0 30 3 * * MON-FRI        스냅샷 retention (30~90일)
0 0 6 * * *               DART corp_code / KRX 마스터 갱신
0 0 9,12,15 * * MON-FRI   AI 분석 일 3회
```

---

## 14. 인프라 (캐시 / 스케줄러 풀 / WebSocket)

### 캐시 계층

- **L1 (Caffeine, in-memory)**: 30분 TTL. 종목별 시세/지표.
- **L2 (Redis)**: 시장 데이터 (섹터/스마트머니/수급급증/AI전략). `MarketCacheWarmerService` 워밍.
- **목적**: 프론트 트래픽이 KIS rate limit(5/s) 직접 때리지 않게 격리.

### KIS WebSocket (phase 4 + 30, 옵션)

- `@ConditionalOnProperty(kis.websocket.enabled=true)` — 기본 비활성
- approval_key 발급 → java.net.http.WebSocket → 보유 종목 자동 구독 (41 한도)
- 자동 재연결 (백오프 60s) + **Gap-filling** (phase 30): 재연결 시 REST 폴백 일괄 동기화
- `RealtimePriceBus` — push 시세 in-memory 캐시
- `AutoTradingBotService`가 `ObjectProvider<RealtimePriceBus>`로 optional 주입
- 매도 평가 시 push 캐시(2초 이내) 우선 → KIS REST 호출량 ↓

### Resilience4j

- CircuitBreaker: kisApi(50% 실패 → OPEN 30s), geminiApi(60% / 60s), dartApi(50% / 60s)
- Retry: kisApi 최대 3회, 300ms × 2배 백오프
- HikariCP: 풀 15, leak detection 2분, max-lifetime 29분

### Gemini 한도 관리 (phase 8)

- `GeminiService.trackDailyUsage()` — 호출 직전 카운터 증가
- 80% 도달 → 텔레그램 risk
- 90% 도달 → 비핵심 호출 차단 권장
- 자정(KST) 리셋

---

## 15. 핵심 사용자 흐름

### 흐름 1: TOP10 매수 신호 → 매수

```
1. V2 대시보드 → 개요 탭 → "종합 추천 TOP 10" 확인
   API: recommendationAPI.getTop5()
2. 종목 클릭 → /stock/:code
3. StockDetailDashboard 로딩
   API: stockDetailAPI.getQuick() → .getHeavy() + getDiagnosis()
4. 헤더 아래 StockConclusionCard 즉시 확인
   - 4-level 뱃지 + 한 줄 헤드라인 + guidance
   - 6 factor 점수 + 시간 척도 라벨
   - 신선도 신호등 (녹/노/빨)
   - level별 적중률 한 줄 ("STRONG_BUY 지난 30일 62%")
   - 충돌 멘트 (있을 시) — "단기 강함 + 가치 매우 약 → 익절 짧게"
5. "✅ 매수 체크리스트" 모달 → 필수 2 + 가산 3 확인
   API: /stock/:code/checklist
6. STRONG/MODERATE면 매매 탭 이동 → 매수
   API: POST /api/paper-trading/trades
```

### 흐름 2: 봇 성과 모니터링 → 실전 전환

```
1. PaperTrading 페이지 → "봇 성과" 탭
   API: /bot-performance?mode=VIRTUAL&days=30
2. BotPnlChart 차트 + winRate / MDD / profitFactor 카드
3. 30일 winRate ≥ 50% + profitFactor ≥ 1.5 + MDD 작음 확인
4. "실전투자" 탭 → KIS 실계좌 연동
5. 봇 시작 (REAL 모드) — Phase 4 KIS WebSocket 활성화 권장
```

### 흐름 3: 시그널 적중률 검증

```
1. 종목 상세 결론 카드의 적중률 라인 확인
   - "STRONG_BUY 시그널 지난 30일 적중률 62% (28/45건, 평균 +2.4%)"
2. 데이터 부족하면 "누적 중" 안내 → 3일 후 첫 평가, 1주 후 의미 통계
3. 정량 비교 가능: STRONG_BUY vs AI_STRONG vs COMPOSITE_5OF5
   → 어느 시그널이 진짜 돈을 버는지 데이터로 판단
```

---

## 16. Phase 변경 이력 (1~36)

| Phase | 영역 | 변경 |
|---|---|---|
| 1 | 안전 | stale 데이터 가드 (surge 15분 + 가격 60초) |
| 2 | 인프라 | 스케줄러 풀 32 → 48 |
| 3a/b | 인프라 | 풀 3개 분리 (taskScheduler/cache/batch) + 60+ @Scheduled 분배 |
| 4 | 인프라 | KIS WebSocket — @ConditionalOnProperty + ObjectProvider |
| 5 | 의사결정 | StockConclusionService — 룰 기반 한 줄 결론 + 4-level |
| 6 | 의사결정 | BuyChecklistService — 봇 hard rule 노출 |
| 7 | UI | 체크리스트 시간 척도 라벨 |
| 8 | 안전 | Gemini 일일 한도 80%/90% 텔레그램 알림 |
| 9 | 봇 | BotPerformanceDto.maxDrawdown 추가 |
| 10 | 시그널 | signal_outcome 테이블 + 평가 batch + API (V26) |
| 11 | 점수 | normalizeScore 동적 비율 |
| 12 | 시그널 | RecommendationService → SignalOutcome.record() |
| 13 | 프론트 | 결론 카드 + 매수 체크리스트 모달 |
| 14 | 점수 | normalizeScore 100 스케일링 (만점 버그 수정) |
| 15 | UI | 결론 카드에 적중률 한 줄 |
| 16 | 시그널 | surge / composite record() 통합 (6종 추적) |
| 17 | 문서 | SYSTEM_OVERVIEW 보강 |
| 18 | 봇 | 봇 성과 탭에 MDD 노출 |
| 19 | 의사결정 | 체크리스트 가중치 차등 (필수 2 + 가산 3) |
| 20 | 시그널 | BM(KOSPI) alpha 도입 (V27) |
| 21 | 문서 | Core Design Principle + 우선순위 컬럼 |
| 22a/b | 문서 + 룰 | Risk Management 섹션 분리 + 시그널 충돌 해설 6가지 |
| 23 | UI | 결론 카드 신선도 신호등 |
| 24 | 시그널 | AI 분석 record() 통합 (AI_STRONG/BUY, 8종 추적) |
| 25 | 시그널 | MFE/MAE 측정 (V28) |
| 26 | UI | 거래 내역 비용(수수료+세금) 컬럼 |
| 27 | 리팩토링 | ChartPatternList.vue 분리 |
| 28 | 리팩토링 | RelatedStocksList.vue 분리 |
| 29 | UI | BotPnlChart.vue — 봇 손익 차트 |
| 30 | 인프라 | KIS WebSocket Gap-filling |
| 31 | 점수/룰 | **추격매수 방지 P0** — 과열 페널티(RSI≥75 / 볼린저 상단 / 5일+20%) + 수급 곡선 뒤집기(3일 정점, 5일+ 축소) + tie-break delta desc |
| 31b | 알림/룰 | 09시 알림 delta 재정의(꼭지 → 가속, Δ≥+10 & 오늘≥65) + 섹터 시장분위기 일괄가산 제거 (P1) |
| 31c | 점수 | 신규 진입 + 5일 누적 +15% 종목 감점 (P2) — 추천 풀 밖에서 갑자기 등장한 추격 패턴 차단 |
| 31d | 점수 | 필터 점수 valueStability 제거 — 컷 필터 raw 와 toDto/getNormalizedTotal 불일치 수정 |
| 32 | 검증 API | phase 31 검증용 `/api/signal-outcomes/compare` — cutoff 전후 hit-rate/alpha/MFE/MAE 비교 + AI전략 시드 위상 코멘트 명시 (후보 풀 확장기, 산식엔 0 영향) |
| 33 | 충돌 룰 + 검증 + 가드 | detectConflicts 룰 7~8 (수급 후반 페이즈 / AI 발굴+객관 미충족) + `/timeseries` 일별 시계열 API + STRONG_BUY 7일 평균 alpha 음수 시 risk 텔레그램 (관찰 가드, 산식 영향 0) |
| 34 | 점수/봇 | 시장 국면 적응형 가중치 (BULL/BEAR/SIDEWAYS × 카테고리 multiplier, 섹터 0.9로 시간 척도 부분 보정) + STRONG_BUY 강한 가치(value≥12) +2 보너스 + `BotPerformanceService.recommendPositionScale` MDD 포지션 스케일 인프라(봇 호출은 사용자 책임) |
| 35 | 검증 API + 안정성 | `/api/recommendation/strong-value-frequency` (보너스 dead code 검증용 일자별 빈도) + 시장 국면 hysteresis dead band 0.5 (임계 근처 BULL↔BEAR 즉시 전환 차단) + 진단 API `/api/diagnostics/data` (35b/c — 데이터 누적/점수 분포 즉시 확인) + 검증 API permitAll |
| **36** | **튜닝** | **BULL 강세장 over-penalty 완화 — 운영 데이터(STRONG_BUY 0건, max 71) 진단 후: 신규 진입 페널티 BULL 스킵 + BULL multiplier 폭 ±0.20 → ±0.10 (earnings 0.95 / sd 1.10 / tc 1.05 / sec 1.00)** |

---

## 운영 환경

- **Spring Boot** 4.0 / Spring Framework 7 / MariaDB / Redis
- **트레이딩 시간**: KRX 정규장 09:00~15:30 KST, 프리/애프터마켓 08:00~20:00
- **활성 기능**: 자동매매 모의 / 스윙 실전 / 텔레그램 3채널 / 시그널 적중률
- **비활성 (옵션)**: 종가 매수 전략, Sentry, KIS WebSocket (`KIS_WEBSOCKET_ENABLED=true`로 활성)
- **운영 URL**: `https://dhkim-lab.duckdns.org`
