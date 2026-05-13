# 주식 플랫폼 — 시스템 개요 (외부 AI용 컨텍스트)

> **Version**: 2026.05.13 Phase 22
> 작성: 2026-05-13 (phase 1~21 반영). 외부 AI 에게 "이 시스템이 무엇이고, 어떤 시그널이 있고,
> 어떻게 매수 결정을 내리는지" 컨텍스트를 주기 위한 요약. 자세한 코드/스키마는
> [`STOCK_SYSTEM_DOCUMENTATION.md`](./STOCK_SYSTEM_DOCUMENTATION.md) (851줄) 참고.

---

## 1. 시스템 한 줄 정의

한국 주식(KRX) 종목 발굴 / 분석 / 모의·실전 자동매매를 통합한 개인용 플랫폼.
**Spring Boot 4.0** 백엔드 + **Vue 3** 프론트엔드 + **MariaDB** + **Redis(L2 캐시)** + **KIS WebSocket(실시간 시세, 옵션)**.

핵심: 어떤 종목을 사야 하는가 결정을 돕는 시그널 11종 + 자동매매 봇 + 텔레그램 알림 3채널 +
종목별 룰 기반 결론 + 매수 체크리스트 + 시그널 적중률 추적 (시장 alpha 기반).

### Core Design Principle

- **하나의 종목에 대해 여러 시간 척도와 차원의 답변을 동시에 제공한다.** 단기 모멘텀과 장기
  가치가 충돌하는 것은 정상이며, 사용자가 종합 판단할 수 있도록 투명하게 노출한다.
- **모든 강력 추천에는 적중률 + 체크리스트 + 리스크를 함께 제시한다** — 추천 한 줄로 사용자를
  움직이지 않고, 데이터로 의사결정을 뒷받침.
- **시그널의 실력은 시장 베타와 분리해 평가한다** (phase 20) — 코스피가 오른 날 종목이 오른
  것을 적중으로 잡지 않고, BM 대비 alpha 가 양수인 경우만 hit.
- **봇 hard rule 은 수동 매매에도 동일하게 적용한다** (phase 6/19) — 필수 항목(거래상태/공매도)
  미충족 시 가산 항목이 양호해도 진입 비권장.
- **데이터 신선도가 깨지면 거래를 멈춘다** (phase 1) — 오래된 시그널로 진입하느니 기회를 놓치는
  쪽 선택.
- **데이터 신선도와 리스크 관리는 모든 의사결정의 최우선 원칙** — 수익 추구보다 자본 보존이 먼저.
  킬스위치/연속 손절 정지/공매도 차단/거래정지 차단 등 hard 가드가 시그널 강도보다 항상 우선한다.

---

## 2. 데이터 소스

| 소스 | 용도 | 호출 빈도 |
|---|---|---|
| **KIS OpenAPI** (REST) | 시세 / 호가 / 투자자별 매매동향 / 주문 | 캐시 1분, rate limit 5/s |
| **KIS WebSocket** (옵션) | 실시간 체결가 push (H0STCNT0) | `KIS_WEBSOCKET_ENABLED=true` 시 보유 종목 push |
| **DART** | 전자공시 (대량보유, 실적 발표) | 5분 cron (장중) / 시간별 (장외) |
| **네이버 금융** (크롤링) | 시세 폴백, 공매도 잔고 | KIS 미설정 시 |
| **Gemini API** | 정성 분석 / 시그널 해석 / 뉴스 요약 | 일일 limit 500, 80%/90% 임계 알림 |
| **goldapi.io / 한국수출입은행** | 금/은/환율 (시장 컨텍스트) | 일 4회 |
| **Reddit / RSS** | 뉴스 / 정책 키워드 감지 | 15분 cron |

---

## 3. 시그널 카탈로그 (핵심 11종)

각 시그널은 **다른 시간 척도 + 다른 차원**의 정보를 본다. 결론이 서로 다를 수 있다.

| # | 시그널 | 무엇 | 입력 차원 | 시간 척도 | 출력 | 적중률 추적 |
|---|---|---|---|---|---|---|
| 1 | **종합 추천 TOP10** (`RecommendationService`) | 실적·수급·기술·섹터 4카테고리 합산 (가치/AI는 별도 트랙) | 펀더멘털+기술+수급 | 30분 캐시 / 11:30·14:00·17:00·20:05 스냅샷 | **0~100점** (75+ 강력매수 / 55~74 매수 / <55 관망) | ✅ STRONG_BUY / BUY |
| 2 | **AI 분석 TOP PICK** (`AiStockAnalysisService`) | Gemini + 기술 15%·수급 50%·펀더멘털 35% | RSI/MA + 외국인·기관 + PER/PBR | 09:00·12:00·15:00 일 3회 | 0~100점 (풀매수/매수/관망/매도) | ❌ |
| 3 | **수급 급증** (`InvestorSurgeService`) | 외국인/기관 순매수 순위 변화 | KIS 투자자 매매동향 | 초단기 (10분 cron, 08:00~20:00) | HOT(100억+) / WARM(50억+) / NORMAL | ✅ SURGE_HOT / WARM |
| 4 | **복합 신호 (5종 매칭)** (`CompositeSignalService`) | 차트패턴·지지선·저평가·수급·AI 5개 중 N개 매칭 | 기술+가치+수급+AI | 30분 캐시 | 1~5점 + 매칭 신호 목록 | ✅ COMPOSITE_4PLUS / 5OF5 |
| 5 | **AI 전략 스냅샷** (`AiStrategySnapshotService`) | SCALPING/SWING/TURNAROUND/VALUE 전략별 후보 | 전략별 상이 | SCALPING 2분 / 그 외 30분 | 전략별 5~10종목 + BUY/SELL/HOLD | ❌ |
| 6 | **섹터 흐름** (`SectorTradingService`) | 섹터별 거래대금 1분 스냅샷 → 5/30분 파워 계산 | 거래대금 | 초단기 (1분) | INFLOW / OUTFLOW | ❌ |
| 7 | **차트 패턴** (`ChartPatternService`) | 더블탑/바텀, H&S, 삼각수렴, 컵앤핸들 6종 검출 | 90일 일봉 OHLC | 중기 (30분 캐시) | 패턴명 + BULLISH/BEARISH + 신뢰도 | ❌ |
| 8 | **선점 레이더** (`PreemptiveRadarService`) | 정책뉴스 + 신고가 전 눌림목 + 5%+ 대량취득 + 어닝 서프라이즈 | 뉴스+공시+실적예측 | 중·장기 | 정책키워드 매칭 종목 | ❌ |
| 9 | **멀티컨빅션** (`MultiConvictionService`) | 외국인/투신/사모/연기금/보험 중 2개+ 동시매수 | 투자자 일일거래 | 단기 (일일) | BuySignal / SellSignal | ❌ |
| 10 | **저평가 점수** (`RecommendationService.calculateValueTop10`) | PBR≤0.7·ROE/PBR≥15·부채≤50%·흑자 가산 | **순수 펀더멘털** | **장기** | 0~20점 → "우량+저평가" 태그 | ❌ |
| 11 | **관심종목 리스크** (`WatchlistRiskMonitorService`) | 공시/급락/대량공급/거래정지 4대 위험 | DART + 시세 + 수급 | 초단기 (10분, 쿨다운 60분) | DANGER / WARNING | ❌ (리스크 시그널) |

### 시그널 차원 매트릭스

| 시그널 | 펀더멘털 | 기술적 | 수급 | AI 정성 | 시간 척도 |
|---|---|---|---|---|---|
| 종합 추천 (1) | O | O | O | △ | 단기~중기 |
| AI 분석 (2) | O | O | O | O | 중·장기 |
| 수급 급증 (3) | | | O | | **초단기** |
| 복합 신호 (4) | O | O | O | O | 중기 |
| 저평가 (10) | **O** | | | | **장기** |
| 섹터 (6) | | O | O | | 초단기 |

→ **같은 종목이 "저평가"(장기 펀더멘털) + "관망"(단기 모멘텀 약함) 인 건 모순이 아니다.** 다른 질문에 대한 다른 답.

---

## 4. 종합 추천 점수 산식 (100점 만점)

### 카테고리 구성 (4개)

| 카테고리 | 만점 | 입력 |
|---|---|---|
| earnings (실적) | 20 | 어닝 서프라이즈 + 매출/영업이익 추세 |
| supplyDemand (수급) | 20 | 외국인/기관 순매수 추세 |
| technical (기술적) | 20 | RSI / 이동평균선 / 모멘텀 |
| sectorMomentum (섹터) | 20 | 섹터 거래대금 INFLOW/OUTFLOW |

→ raw 합산 최대 80. normalizeScore 가 0~100 으로 스케일링.

별도 트랙 (총점 산식 제외, 태그/UI 노출용):
- **valueStability** (가치/저평가, 0~20) — `calculateValueTop10` 별도 TOP10
- **aiStrategy** (AI 전략) — 태그용

### normalizeScore 공식 (phase 14)

**의도**: 4 카테고리 raw 만점 80점을 사용자에게는 100점 만점으로 일관되게 노출. 카테고리 수가
바뀌어도(TOTAL_CATEGORIES 상수만 조정) 임계값(75/55/40) 의미가 보존되도록 동적 비율 기반.

```
rawCap = TOTAL_CATEGORIES × 20 = 80
scaled = raw × 100 / rawCap

if validCount >= TOTAL_CATEGORIES:
    return min(100, scaled)
else:
    cap = 25 + 75 × (validCount / TOTAL_CATEGORIES)
    return min(cap, scaled)
```

| validCount | full raw | scaled | cap | 결과 |
|---|---|---|---|---|
| 4/4 | 80 | 100 | (미적용) | **100** |
| 3/4 | 60 | 75 | 81 | 75 |
| 2/4 | 40 | 50 | 62 | 50 |
| 1/4 | 20 | 25 | 43 | 25 |
| 0/4 | - | - | - | 0 |

### 임계값 (`RecommendationService` + `StockConclusionService`)

| 점수 | 레벨 | 의미 |
|---|---|---|
| ≥ 75 | **STRONG_BUY** (강력매수) | 4 카테고리 평균 15점 이상 — 다수 시그널 합의 |
| 55 ~ 74 | **BUY** (매수) | 4 카테고리 평균 11점 이상 |
| 40 ~ 54 | HOLD (관망) | 일부 시그널만 충족 |
| < 40 | 제외 / WAIT | 시그널 약함 |

### 점수 일관성 이슈 (사용자 페인 + 해결)

**현상**: TOP10 추천 리스트와 종목 상세 페이지 점수가 달랐음 — 추천 리스트는 "5신호 매칭 개수"(0~5),
상세 페이지는 AI 점수(0~100). 다른 차원.

**해결 (phase 5~13)**:
- `StockConclusionService` (phase 5): 종합 추천 점수 + 카테고리별 점수를 입력으로 받아 4-level
  (STRONG_BUY/BUY/HOLD/WAIT) + 한 줄 헤드라인 + 6개 factor 출력.
- `BuyChecklistService` (phase 6 + 19): 봇 hard rule **필수 2개 + 가산 3개 = 총 5개 항목** — **필수 / 가산 차등 (phase 19)**.
  **필수 항목** (1개라도 미충족 → 즉시 NOT_RECOMMENDED):
    1. **tradable** (META) — `StockStatusService.isActive` 거래 가능 (정지/상폐 아님)
    2. **shortSelling** (SHORT) — `ShortSellingService.getShortSellingRatio` < 5%

  **가산 항목** (3개 중 충족 수에 따라 등급):
    3. **consecutiveBuy** (SHORT) — 외국인 또는 기관 ≥ 3일 연속매수
    4. **compositeSignal** (MID) — `CompositeSignalService.evaluate` 매칭 ≥ 3/5
    5. **conclusion** (MID) — `StockConclusionService` 결론이 BUY 이상

  **등급**: 가산 3/3 → STRONG, 2/3 → MODERATE, 1/3 → CAUTION, 0/3 → NOT_RECOMMENDED.
- 프론트 통합 (phase 13): 종목 상세 페이지 상단에 결론 카드 + 매수 체크리스트 모달.

→ 사용자는 페이지 진입 즉시 한 줄 결론을 보고, 체크리스트로 봇 룰 충족 여부 확인 가능.

---

## 5. Risk & Safety Management (자본 보존 최우선 계층)

Core Design Principle 의 "신선도와 리스크가 모든 의사결정에 우선" 을 구현하는 11가지 가드.
시그널 강도가 아무리 좋아도 이 중 하나라도 발동하면 진입 차단.

| 가드 | 임계 | 발동 시 동작 | 도입 |
|---|---|---|---|
| **킬스위치** (계좌) | 일일 손실 -3% | 당일 매수 정지 | 초기 |
| **스캘핑 킬스위치** | 일일 -1.5% | 스캘핑 모드만 정지 | 초기 |
| **연속 손절 정지** | 3회 연속 손절 | 당일 매수 정지 | 초기 |
| **VIX 일시정지** | 글로벌 변동성 급등 | 매수 정지 | 초기 |
| **KOSPI 하락 정지** | 지수 하락 임계 | 매수 정지 | 초기 |
| **surge 신선도 가드** | 스냅샷 15분 stale | 매수 보류 + 텔레그램 risk | phase 1 |
| **가격 신선도 가드** | 매도 평가 가격 60초 stale | KIS 재조회. 실패 시 매도 보류 (다음 15초 사이클) | phase 1 |
| **진입 직전 가격 검증** | 신호 평가 후 ±2% 변동 | 진입 스킵 | 초기 |
| **섹터 OUTFLOW 차단** | 자금 유출 섹터 | 해당 섹터 종목 매수 거절 | 초기 |
| **거래정지/상폐 차단** | StockStatusService 일일 동기화 | 매수 거절 (필수 체크리스트 항목) | 초기 + phase 19 격상 |
| **공매도 5%+ 차단** | 비율 ≥ 5% | 매수 거절 (필수 체크리스트 항목) | 초기 + phase 19 격상 |

수동 매매도 이 룰을 따른다 — `BuyChecklistService` 가 동일 항목을 사용자 화면에 노출 (phase 6 + 19).

---

## 6. 자동매매 봇 (`AutoTradingBotService`)

| 전략 | 모드 | 진입 조건 | 매도 조건 | 활성 시각 |
|---|---|---|---|---|
| **스캘핑** | 모의 전용 | 순매수≥10억 + 양봉 + 변동폭≥1.5% + 보조 2/4 | 손절 -1.5% / 익절 +1.2% 절반 / 트레일링 -1% / 타임컷 15~20분 | 09:45~10:30 |
| **스윙** | 모의 + 실전 | 외국인/기관 3일+ 연속매수 + MA20 지지 + RSI<65 | 익절 +5% / 손절 -3% / 최대 5일 | 14:00 체크 |
| ~~종가 매수~~ | 비활성 | (2026-09-14 거래시간 연장 후 재설계) | | |

봇이 적용하는 안전 가드는 §5 Risk & Safety Management 참고.

### 봇 성과 (`BotPerformanceService` + phase 9)

`GET /api/paper-trading/bot-performance?days=30&mode=VIRTUAL` 응답:
- totalTrades / winCount / loseCount / winRate
- totalPnl / avgPnl / maxWin / maxLoss / profitFactor
- **maxDrawdown** (phase 9 추가) — 누적 손익 그래프의 peak-to-trough 최대 낙폭
- avgHoldingMinutes
- dailyPnl[] / stockPnl[] / exitReasonStats{}

---

## 7. 알림 시스템 — 텔레그램 3채널

| 채널 | 환경변수 | 알림 내용 |
|---|---|---|
| **브리핑** | `TELEGRAM_BOT_TOKEN` | 모닝브리핑(07:30) · 마감알림(16:45) · 시장상태 · 헬스체크 |
| **시그널** | `TELEGRAM_BOT_TOKEN_SIGNAL` | 매수 신호 · 마법공식 · 턴어라운드 · 봇 진입/매도 |
| **리스크** | `TELEGRAM_BOT_TOKEN_RISK` | 킬스위치 · 공매도 경보 · DB 저장 실패 · stale surge · **Gemini 한도 80%/90% (phase 8)** |

---

## 8. 사용자 매수 결정 흐름 (phase 13 통합 후)

1. **추천 발굴**: V2 대시보드 "종합 추천 TOP10" 또는 "복합 신호" 섹션 클릭
2. **종목 상세 진입**: `GET /api/stock/{code}/summary` → `StockDetailDashboard.vue`
3. **즉시 결론 확인**: 헤더 바로 아래 **StockConclusionCard** 표시
   - 4-level 뱃지 + 한 줄 헤드라인 + guidance
   - 6개 factor 그리드 — 점수 + 시간 척도 라벨 (단기/중기/장기/필수)
4. **매수 체크리스트** (선택): "✅ 매수 체크리스트" 버튼 → 모달 표시
   - 5개 항목: 거래상태 / 공매도 / 외국인기관 연속매수 / 복합신호 / 종합결론
   - 권고 STRONG/MODERATE/CAUTION/NOT_RECOMMENDED
5. **리스크 / 기술적 검증**: 상세 페이지 하위 위젯 (DART 공시, 지지선, 수급 추이)
6. **실행**: 매수 버튼 → `POST /api/paper-trading/trades`

### 신규 API (phase 5~10)

| 엔드포인트 | 응답 | 용도 |
|---|---|---|
| `GET /api/stock/{code}/conclusion` | StockConclusionDto | 룰 기반 한 줄 결론 + factor |
| `GET /api/stock/{code}/checklist` | BuyChecklistDto | 5개 매수 체크리스트 + 권고 |
| `GET /api/signal-outcomes/accuracy?days=30` | SignalAccuracyDto | 시그널별 적중률 통계 (3일 후 평가 누적) |

---

## 9. 시그널 적중률 추적 (phase 10 + 12)

### 흐름

1. **시그널 발생 시 INSERT**: `signal_outcome` 테이블에 (signal_type, stock_code, signal_date, price_at_signal)
   추적 중인 시그널 타입 (phase 12 + 16):
   - **STRONG_BUY** (≥75) / **BUY** (55~74) — `RecommendationService.saveSnapshotInternal`
   - **SURGE_HOT** (100억+) / **SURGE_WARM** (50억+) — `InvestorSurgeService.collectIntradaySnapshot` (phase 16)
   - **COMPOSITE_4PLUS** (4/5 매칭) / **COMPOSITE_5OF5** (5/5) — `CompositeSignalService.evaluate` (phase 16)
   - 같은 (type/stock/date) 중복 방지
2. **3일 후 batch 평가**: 매일 19:30 KST `evaluatePendingSignals` (batchScheduler)
   - unevaluated 항목의 현재 가격 + KOSPI 현재 지수 조회
   - `pct_change_3d` = (priceAfter - priceAtSignal) / priceAtSignal × 100
   - `bm_return_3d` = (KOSPI - bmAtSignal) / bmAtSignal × 100
   - `alpha_3d` = pct_change_3d - bm_return_3d
   - **hit 기준** (phase 20): `alpha_3d ≥ 0 AND pct_change_3d > 0` (시장 이김 + 절대 수익)
   - BM 데이터 없으면 기존 +3% 폴백
3. **통계 조회**: `GET /api/signal-outcomes/accuracy?days=30` — 시그널별 적중률 / 평균 변동률
4. **UI 노출** (phase 15): 종목 상세 페이지 결론 카드에 level별 적중률 한 줄 표시
   - "📊 STRONG_BUY 시그널 지난 30일 적중률 62% (28/45건, 평균 +2.4%)"
   - ≥ 60% 녹색 / 40~60% 노랑 / < 40% 빨강

---

## 10. 인프라 (성능 / 안정성)

### 스케줄러 풀 (phase 2~3)

`SchedulingConfig` — 60+ `@Scheduled` 작업 3개 풀로 분리.

| 풀 | 크기 | 사용처 |
|---|---|---|
| `taskScheduler` (@Primary) | 16 | 자동매매, 수급 갱신, 포지션 감시 |
| `cacheScheduler` | 16 | 시세/수급/섹터/AI 캐시 워밍업 (9개 서비스) |
| `batchScheduler` | 16 | 일일 리포트, 크롤링, 정리, signal_outcome 평가 (20+ 서비스) |

→ 장 마감 시각 캐시/배치 폭주가 트레이딩 사이클 슬롯을 점유하지 못함.

### 캐시 계층

- **L1 (Caffeine, in-memory)**: 30분 TTL, 종목별 시세/지표
- **L2 (Redis)**: 시장 데이터 (섹터 / 스마트머니 / 수급급증 / AI전략)
- **목적**: 프론트 트래픽이 KIS API rate limit(5/s) 직접 때리지 않게 격리

### KIS WebSocket (phase 4, 옵션)

- 빈 등록: `@ConditionalOnProperty(kis.websocket.enabled=true)` — 기본 비활성
- 활성 시: approval_key 발급 → java.net.http.WebSocket 연결 → 보유 종목 자동 구독
- `RealtimePriceBus` — push 시세 in-memory 캐시 (종목별 최신값)
- `AutoTradingBotService` 가 `ObjectProvider<RealtimePriceBus>` 로 optional 주입 — 비활성에서도 정상 동작
- 매도 평가 시 push 캐시(2초 이내) 우선 → KIS REST 호출량 ↓

### 외부 API 회복성 (Resilience4j)

- CircuitBreaker: kisApi(50% 실패 → OPEN 30s), geminiApi(60% / 60s), dartApi(50% / 60s)
- Retry: kisApi (최대 3회, 300ms × 2배 백오프)
- HikariCP: 풀 15, leak detection 2분, max-lifetime 29분

### Gemini 한도 관리 (phase 8)

- `GeminiService.trackDailyUsage` — 호출 직전 카운터 증가
- 80% 도달 → 텔레그램 risk 채널 1회 알림
- 90% 도달 → "비핵심 호출 차단 권장" 알림
- 자정(KST) 자동 리셋
- 기존 CircuitBreaker(`geminiApi`) 와 함께 quota 보호

---

## 11. 핵심 코드 위치

```
backend/src/main/java/com/myplatform/backend/
├── service/
│   ├── AutoTradingBotService.java       자동매매 (스캘핑+스윙) + 안전장치
│   ├── RecommendationService.java       종합 추천 TOP10 (4 카테고리, 100 스케일링)
│   ├── StockConclusionService.java      룰 기반 한 줄 결론 (phase 5)
│   ├── BuyChecklistService.java         5개 매수 체크리스트 (phase 6)
│   ├── SignalOutcomeService.java        시그널 적중률 추적 (phase 10)
│   ├── AiStockAnalysisService.java      AI 점수 (Gemini)
│   ├── AiStrategySnapshotService.java   4전략 스냅샷
│   ├── InvestorSurgeService.java        수급 급증 (HOT/WARM)
│   ├── CompositeSignalService.java      5신호 매칭
│   ├── SectorTradingService.java        섹터 흐름
│   ├── ChartPatternService.java         차트 패턴 6종
│   ├── PreemptiveRadarService.java      선점 레이더
│   ├── MultiConvictionService.java      멀티 투자자 합의
│   ├── WatchlistRiskMonitorService.java 관심종목 리스크
│   ├── StockPriceService.java           시세 (KIS+네이버 폴백)
│   ├── KoreaInvestmentService.java      KIS REST 클라이언트
│   ├── KisWebSocketService.java         KIS WebSocket (옵션)
│   ├── RealtimePriceBus.java            push 시세 in-memory 캐시
│   ├── GeminiService.java               Gemini API + 일일 한도 알림
│   ├── BotPerformanceService.java       봇 성과 + MDD
│   ├── MarketCacheWarmerService.java    Redis L2 캐시 워밍업
│   └── TelegramNotificationService.java 3채널 알림
├── controller/
│   ├── StockDetailController.java       /summary /conclusion /checklist /quick /heavy
│   ├── PaperTradingController.java      모의/실전 매매 + 봇 성과
│   └── SignalOutcomeController.java     /accuracy
├── entity/
│   ├── RecommendationSnapshot.java
│   ├── SignalOutcome.java               phase 10 (V26)
│   └── VirtualTradeHistory.java
└── config/
    └── SchedulingConfig.java            스케줄러 풀 3개

frontend/src/
├── views/
│   └── StockDetailDashboard.vue         종목 상세 (4757줄, 헤더 아래 결론 카드 통합)
└── components/v2/
    ├── StockConclusionCard.vue          phase 13 — 4-level + factor 그리드
    └── BuyChecklistModal.vue            phase 13 — 5개 항목 모달
```

---

## 12. 변경 이력 (Phase 1~22)

| Phase | 변경 |
|---|---|
| 1 | stale 데이터 가드 (surge 15분 + 가격 60초) |
| 2 | 스케줄러 풀 32→48 |
| 3a/b | 풀 3개 분리 (taskScheduler/cache/batch) + 60+ @Scheduled 분배 |
| 4 | KIS WebSocket — `@ConditionalOnProperty` + ObjectProvider |
| 5 | StockConclusionService — 룰 기반 한 줄 결론 + 4-level |
| 6 | BuyChecklistService — 봇 hard rule 5개 항목 노출 |
| 7 | 체크리스트 항목 시간 척도 라벨 |
| 8 | Gemini 일일 한도 80%/90% 텔레그램 알림 |
| 9 | BotPerformanceDto.maxDrawdown 추가 |
| 10 | signal_outcome 테이블 + 평가 batch + API |
| 11 | normalizeScore 동적 비율 리팩토링 |
| 12 | RecommendationService 에서 SignalOutcome.record() 통합 |
| 13 | 프론트 결론 카드 + 매수 체크리스트 모달 |
| 14 | normalizeScore 100 스케일링 — 만점 80→100 버그 수정 |
| 15 | 결론 카드에 적중률 한 줄 추가 — UI 노출 |
| 16 | surge / composite 시그널 record() 통합 — 4종 시그널 추적 |
| 17 | 문서 보강 — BuyChecklist 5개 항목 명시, normalizeScore 의도, 버전 명시 |
| 18 | 봇 성과 탭에 MDD 노출 (실전 스윙 가시성) |
| 19 | 체크리스트 가중치 차등 — 필수(tradable/shortSelling) + 가산 분리 |
| 20 | 시그널 적중률에 BM(KOSPI) alpha 도입 — 시장 베타 분리 |
| 21 | 문서 보강 — Core Design Principle, 시그널 추적 여부 컬럼, phase 18~21 반영 |
| **22a** | **문서 — Risk Management 별도 섹션, 신선도/리스크 원칙 명시, 시그널 충돌 해설 High 격상** |

---

## 13. 알려진 한계 / 다음 작업 후보

| 영역 | 현재 상태 | 개선 방향 | 우선순위 |
|---|---|---|---|
| **시그널 충돌 해설** | 룰 5단계 분기 (StockConclusionService) | "장기 매집 유효하나 단기 진입 불리" 같은 상태 조합형 멘트 추가 | **High** (사용자 혼란 주요 원인) |
| **Time-to-Stale 시각화** | 백엔드 가드만 | 프론트 카운트다운/신호등 | Medium |
| **백테스트** | 부분 구현 | 4전략 과거 수익률 검증 강화 | Medium |
| **MFE/MAE 측정** | 3일 종가만 | 보유 기간 중 최대 상승/하락 추적 — 최적 익절/손절 도출 | Low |
| **포지션 사이징 (ATR)** | 자동매매 비율 고정 | ATR 기반 동적 사이징 (백테스트 누적 후) | Low |
| **MDD 기반 자산 배분** | MDD 노출만 | 낙폭 구간에서 베팅 사이즈 자동 축소 | Low |
| **Gemini Fallback AI** | 한도 알림만 | 로컬 LLM 대체 경로 | Low |
| **WebSocket Gap-filling** | 단순 재연결 | 끊긴 동안 REST 폴백 | Low |
| **세금/수수료** | 실전 매매 수동 계산 | 자동 차감 표시 | Low |
| **StockDetailDashboard.vue 분리** | 4757줄 단일 파일 | 기능별 컴포넌트 분리 (유지보수성) | Low |
| **AI 분석 / 전략 / 섹터 적중률** | 미추적 | record() 추가 통합 (phase 16 패턴 복사) | Low |

---

## 14. 외부 AI 에게 질문할 때 추가 컨텍스트

1. **운영 환경**: Spring Boot 4.0, Spring Framework 7, MariaDB, Redis, 단일 사용자 개인 프로젝트
2. **트레이딩 시간**: KRX 정규장 09:00~15:30 KST, 프리/애프터마켓 08:00~20:00
3. **현재 활성**: 자동매매 모의 / 스윙 실전 / 텔레그램 3채널 / 시그널 적중률 추적 (3일 후 평가)
4. **현재 비활성**: 종가 매수 전략, Sentry, KIS WebSocket(`KIS_WEBSOCKET_ENABLED=true` 로 켤 수 있음)
5. **최근 페인 해결**: 점수 불일치 → 룰 기반 결론 카드 + 매수 체크리스트 모달 (phase 13). 만점 버그 → 100 스케일링 (phase 14). 시장 베타 분리 → BM alpha 평가 (phase 20)
