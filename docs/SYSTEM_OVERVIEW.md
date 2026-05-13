# 주식 플랫폼 — 시스템 개요 (외부 AI용 컨텍스트)

> 작성: 2026-05-13. 외부 AI 에게 "이 시스템이 무엇이고, 어떤 시그널이 있고, 어떻게 매수 결정을 내리는지" 컨텍스트를 주기 위한 요약. 자세한 코드/스키마는 [`STOCK_SYSTEM_DOCUMENTATION.md`](./STOCK_SYSTEM_DOCUMENTATION.md) (851줄) 참고.

---

## 1. 시스템 한 줄 정의

한국 주식(KRX) 종목 발굴 / 분석 / 모의·실전 자동매매를 통합한 개인용 플랫폼.
**Spring Boot 4.0** 백엔드 + **Vue 3** 프론트엔드 + **MariaDB** + **Redis(L2 캐시)** + **KIS WebSocket(실시간 시세)**.

핵심은 "어떤 종목을 사야 하는가" 결정을 돕는 시그널 11종 + 자동매매 봇 + 텔레그램 알림 3채널.

---

## 2. 데이터 소스

| 소스 | 용도 | 호출 빈도 |
|---|---|---|
| **KIS OpenAPI** (REST) | 시세 / 호가 / 투자자별 매매동향 / 주문 | 캐시 1분, rate limit 5/s |
| **KIS WebSocket** (옵션) | 실시간 체결가 push (H0STCNT0) | enabled=true 시 보유 종목 push |
| **DART** | 전자공시 (대량보유, 실적 발표) | 5분 cron (장중) / 시간별 (장외) |
| **네이버 금융** (크롤링) | 시세 폴백, 공매도 잔고 | KIS 미설정 시 |
| **Gemini API** | 정성 분석 / 시그널 해석 / 뉴스 요약 | 일일 limit 500 |
| **goldapi.io / 한국수출입은행** | 금/은/환율 (시장 컨텍스트) | 일 4회 |
| **Reddit / RSS** | 뉴스 / 정책 키워드 감지 | 15분 cron |

---

## 3. 시그널 카탈로그 (핵심 11종)

각 시그널은 **다른 시간 척도 + 다른 차원**의 정보를 본다 — 그래서 시그널끼리 결론이 다를 수 있다.

| # | 시그널 | 무엇 | 입력 차원 | 시간 척도 | 출력 |
|---|---|---|---|---|---|
| 1 | **종합 추천 TOP10** (`RecommendationService`) | 실적·수급·기술·섹터·가치 5카테고리 가중 합산 | 펀더멘털+기술+수급 | 30분 캐시 / 11:30·14:00·17:00·20:05 스냅샷 | 0~100점 (75+ 강력매수, 55~74 매수, <55 관망) |
| 2 | **AI 분석 TOP PICK** (`AiStockAnalysisService`) | Gemini + 기술 15%·수급 50%·펀더멘털 35% | RSI/MA/볼린저 + 외국인·기관 + PER/PBR | 09:00·12:00·15:00 일 3회 | 0~100점 (풀매수/매수/관망/매도) |
| 3 | **수급 급증** (`InvestorSurgeService`) | 외국인/기관 순매수 순위 변화 감지 | KIS 투자자 매매동향 | 초단기 (10분 cron, 08:00~20:00) | HOT(100억+) / WARM(50억+) / NORMAL |
| 4 | **복합 신호 (5종 매칭)** (`CompositeSignalService`) | 차트패턴·지지선·저평가·수급·AI 5개 중 N개 매칭 | 기술+가치+수급+AI | 30분 캐시 | 1~5점 + 매칭 신호 목록 |
| 5 | **AI 전략 스냅샷** (`AiStrategySnapshotService`) | SCALPING/SWING/TURNAROUND/VALUE 4전략별 후보 | 전략별 상이 | SCALPING 2분 / 그 외 30분 | 전략별 5~10종목 + BUY/SELL/HOLD |
| 6 | **섹터 흐름** (`SectorTradingService`) | 섹터별 거래대금 1분 스냅샷 → 5/30분 파워 계산 | 거래대금 | 초단기 (1분) | INFLOW / OUTFLOW + 누적 거래대금 |
| 7 | **차트 패턴** (`ChartPatternService`) | 더블탑/바텀, H&S, 삼각수렴, 컵앤핸들 6종 검출 | 90일 일봉 OHLC | 중기 (30분 캐시) | 패턴명 + BULLISH/BEARISH + 신뢰도 |
| 8 | **선점 레이더** (`PreemptiveRadarService`) | 정책뉴스 + 신고가 전 눌림목 + 5%+ 대량취득 + 어닝 서프라이즈 | 뉴스+공시+실적예측 | 중·장기 | 정책키워드 매칭 종목 |
| 9 | **멀티컨빅션** (`MultiConvictionService`) | 외국인/투신/사모/연기금/보험 5유형 중 2개+ 동시매수 | 투자자 일일거래 | 단기 (일일) | BuySignal / SellSignal |
| 10 | **저평가 점수** (`RecommendationService.calculateValueTop10`) | PBR≤0.7·ROE/PBR≥15·부채≤50%·흑자 가산 | **순수 펀더멘털** | **장기** (분기/연간) | 0~20점 → "우량+저평가" 태그 |
| 11 | **관심종목 리스크** (`WatchlistRiskMonitorService`) | 공시/급락/대량공급/거래정지 4대 위험 | DART + 시세 + 수급 | 초단기 (10분, 쿨다운 60분) | DANGER / WARNING |

### 시그널 차원 매트릭스

| 시그널 | 펀더멘털 | 기술적 | 수급 | AI 정성 | 시간 척도 |
|---|---|---|---|---|---|
| 종합 추천 (1) | O | O | O | △ | 단기~중기 |
| AI 분석 (2) | O | O | O | O | 중·장기 |
| 수급 급증 (3) | | | O | | **초단기** |
| 복합 신호 (4) | O | O | O | O | 중기 |
| 저평가 (10) | **O** | | | | **장기** |
| 섹터 (6) | | O | O | | 초단기 |

→ **같은 종목이 "저평가"(장기 펀더멘털) 인데 "관망"(단기 모멘텀 약함) 인 건 모순이 아니다.** 다른 질문에 대한 다른 답이다.

---

## 4. 점수 불일치 이슈 (사용자가 가장 자주 마주치는 혼란)

### 현상

TOP5 추천 리스트에서 "강력매수 78점" 으로 클릭해 들어갔는데, 종목 상세 페이지에서는 "관망" 으로 표시되거나, 점수가 60점대로 보임.

### 원인 (가설 우선순위)

1. **다른 평가 모델** — 추천 리스트는 "5신호 매칭 개수" (0~5), 상세 페이지 점수는 AI 모델 (0~100). 차원 자체가 다름.
2. **시간 척도 차이** — TOP5 는 장중 실시간 30분 캐시, 상세 페이지는 사용자 요청 시점 실시간 또는 DB 스냅샷(11:30/14:00/17:00/20:05). 같은 종목도 30분 후엔 70점대로 떨어질 수 있음.
3. **저평가 ≠ 매수 타이밍** — `저평가 TOP10` 은 PBR/ROE 기준 (장기 가치), 종합 추천은 단기 모멘텀 포함. 저평가지만 단기 추세 약함 → "관망" 정상.
4. **AI 전략 처리 규칙** — `RecommendationService` 에서는 AI 전략을 점수 산식에서 제외(태그용), 다른 곳에서는 포함 가능 → 같은 종목 다른 점수.

### 코드 위치
- `RecommendationService.saveSnapshotInternal()` L434-467 (스냅샷 저장)
- `RecommendationService.normalizeScore()` (validCount 별 cap 40/65/85 다름)
- `RecommendationService.calculateValueTop10()` L503-602 (저평가 점수)
- `AiStockAnalysisService.analyzeStockWithPrice()` L266-295 (AI 점수)

---

## 5. 자동매매 봇 (`AutoTradingBotService`)

| 전략 | 모드 | 진입 조건 | 매도 조건 | 활성 시각 |
|---|---|---|---|---|
| **스캘핑** | 모의 전용 (실전 OFF) | 순매수≥10억 + 양봉 + 변동폭≥1.5% + 보조 2/4 (체결강도/RSI/이격도/갭) | 손절 -1.5% / 익절 +1.2% 절반 / 트레일링 -1% / 타임컷 15~20분 | 09:45~10:30 골든타임 |
| **스윙** | 모의 + 실전 | 외국인/기관 3일+ 연속매수 + MA20 지지 + RSI<65 | 익절 +5% / 손절 -3% / 최대 5일 보유 | 14:00 체크 |
| ~~종가 매수~~ | 비활성 | (2026-09-14 거래시간 연장 후 재설계) | | |

### 안전 장치 (현재 운영)

- **킬스위치**: 일일 손실 -3% 도달 시 당일 매수 정지 (`killSwitchTriggered`).
- **스캘핑 킬스위치**: 일일 -1.5% 도달 (`scalpingKillSwitchTriggered`).
- **연속 손절 정지**: 3회 연속 손절 시 당일 매수 정지.
- **VIX 일시정지**: 글로벌 변동성 급등 시 매수 정지.
- **KOSPI 하락 정지**: 지수 하락 시 정지.
- **surge 신선도 가드** (phase1): 스냅샷 15분 stale 시 매수 보류 + 텔레그램 risk 알림.
- **가격 신선도 가드** (phase1): 매도 평가 가격 60초 stale 시 KIS 재조회. 실패 시 매도 보류 (다음 15초 사이클).
- **진입 직전 가격 검증**: 신호 평가 후 KIS 재조회 → ±2% 변동 시 진입 스킵.
- **섹터 OUTFLOW 차단**: 자금 유출 섹터 종목 매수 거절.
- **거래정지/상폐 차단**: `StockStatusService` 일일 동기화.
- **공매도 5%+ 차단**: 고공매도 종목 진입 거절.
- **HikariCP leak 가드**: 트랜잭션 분리로 커넥션 누수 방지.

---

## 6. 알림 시스템 — 텔레그램 3채널

| 채널 | 환경변수 | 알림 내용 |
|---|---|---|
| **브리핑** | `TELEGRAM_BOT_TOKEN` | 모닝브리핑(07:30) · 마감알림(16:45) · 시장상태 · 헬스체크 |
| **시그널** | `TELEGRAM_BOT_TOKEN_SIGNAL` | 매수 신호 · 마법공식 · 턴어라운드 · 봇 진입/매도 알림 |
| **리스크** | `TELEGRAM_BOT_TOKEN_RISK` | 킬스위치 · 공매도 경보 · DB 저장 실패 · 매도 실패 · **stale surge 알림** |

코드: `TelegramNotificationService.sendBriefing / sendSignal / sendRisk`.

---

## 7. 사용자 매수 결정 흐름 (현재)

1. **추천 발굴**: V2 대시보드 → "종합 추천 TOP10" 또는 "복합 신호 5종" 섹션 클릭
2. **종목 상세 진입**: `GET /api/stock/{code}/summary` → `StockDetailDashboard.vue`
3. **검증**: 상단 복합 신호 뱃지(매칭 X/5) + 이중 점수(단기 / 펀더멘털) + 행동 권고 헤드라인
4. **리스크 확인**: DART 공시 + 부정 뉴스 + AI 경고
5. **기술적 확인**: 지지선/저항선, 외국인·기관 수급 방향, 체결강도
6. **실행**: 매수 버튼 → `POST /api/paper-trading/trades` (모의) 또는 `RealTradeService` (실전, ADMIN)

### 현재 갭

- **사용자 수동 매매는 명시적 룰 없음** — 자동매매 봇만 hard rule 보유.
- 추천 리스트는 "신호 개수" 1차원, 상세 페이지는 "AI 점수 + 펀더멘털 + 리스크" 다차원 — 둘이 정합하지 않을 때 사용자가 종합 판단 부담.
- "관망/매수" 결론이 어느 시간 척도 / 어느 모델에서 나온 건지 화면에 명시 안 됨.

### 개선 후보 (미구현)

1. **시간 척도 라벨**: 각 시그널에 "장기 / 중기 / 단기" 태그 표시.
2. **종합 결론 한 줄**: 종목 상세 상단에 "저평가 + 단기 관망 → 분할 매수 후보" 같은 룰 기반 요약.
3. **매수 체크리스트**: "5개 중 N개 충족 → 매수 신호 강함/중립/약함" 정량 표시.

---

## 8. 인프라 (성능 / 안정성)

### 스케줄러 풀 분리 (phase 3 완료)

`SchedulingConfig` — 60+ `@Scheduled` 작업을 3개 풀로 분리.

| 풀 | 크기 | 사용처 |
|---|---|---|
| `taskScheduler` (@Primary) | 16 | 자동매매 봇 5개, 수급 갱신, 포지션 감시 |
| `cacheScheduler` | 16 | 시세/수급/섹터/AI 캐시 워밍업 (9개 서비스) |
| `batchScheduler` | 16 | 일일 리포트, 크롤링, 정리 작업 (20+ 서비스) |

→ 장 마감 시각(15:50~16:45) 캐시/배치 폭주가 트레이딩 사이클 슬롯을 점유하지 못함.

### 캐시 계층

- **L1 (Caffeine, in-memory)**: 30분 TTL, 종목별 시세/지표.
- **L2 (Redis)**: 시장 데이터 (섹터 / 스마트머니 / 수급급증 / AI전략) — `MarketCacheWarmerService` 가 워밍업.
- **목적**: 프론트 트래픽이 KIS API rate limit(5/s) 직접 때리지 않게 격리.

### KIS WebSocket (phase 4, 옵션)

- 빈 등록: `@ConditionalOnProperty(kis.websocket.enabled=true)` — 기본 비활성.
- 활성 시: approval_key 발급 → java.net.http.WebSocket 연결 → 보유 종목 자동 구독 → 매수/매도/봇 복원 시 자동 등록/해제.
- `RealtimePriceBus` — push 시세 in-memory 캐시 (종목별 최신값).
- `AutoTradingBotService` 가 `ObjectProvider<RealtimePriceBus>` 로 optional 주입 → 비활성 환경에서도 정상 동작.
- 매도 평가 시 push 캐시(2초 이내) 우선 → KIS REST 호출량 ↓, 손절 의사결정 지연 단축.
- 활성화: `KIS_WEBSOCKET_ENABLED=true` 환경변수.

### 외부 API 회복성 (Resilience4j)

- CircuitBreaker: kisApi(50% 실패 → OPEN 30s), geminiApi(60% / 60s), dartApi(50% / 60s).
- Retry: kisApi (최대 3회, 300ms × 2배 백오프).
- HikariCP: 풀 15, leak detection 2분, max-lifetime 29분.

---

## 9. 핵심 코드 위치

```
backend/src/main/java/com/myplatform/backend/
├── service/
│   ├── AutoTradingBotService.java      자동매매 (스캘핑+스윙)
│   ├── RecommendationService.java      종합 추천 TOP10
│   ├── AiStockAnalysisService.java     AI 점수 (Gemini)
│   ├── AiStrategySnapshotService.java  4전략 스냅샷
│   ├── InvestorSurgeService.java       수급 급증 (HOT/WARM)
│   ├── CompositeSignalService.java     5신호 매칭
│   ├── SectorTradingService.java       섹터 흐름
│   ├── ChartPatternService.java        차트 패턴 6종
│   ├── PreemptiveRadarService.java     선점 레이더
│   ├── MultiConvictionService.java     멀티 투자자 합의
│   ├── WatchlistRiskMonitorService.java 관심종목 리스크
│   ├── StockPriceService.java          시세 (KIS+네이버 폴백)
│   ├── KoreaInvestmentService.java     KIS REST 클라이언트
│   ├── KisWebSocketService.java        KIS WebSocket (옵션)
│   ├── RealtimePriceBus.java           push 시세 in-memory 캐시
│   ├── MarketCacheWarmerService.java   Redis L2 캐시 워밍업
│   └── TelegramNotificationService.java 3채널 알림
└── config/
    └── SchedulingConfig.java           스케줄러 풀 3개

frontend/src/views/
├── StockDetailDashboard.vue            종목 상세 (이중 점수 + 복합 신호)
├── StockTradingDashboardV2.vue         V2 통합 대시보드
├── PaperTradingPage.vue                모의/실전 자동매매
└── AiStrategyDashboardPage.vue         AI 전략
```

---

## 10. 알려진 한계 / 다음 작업 후보

| 영역 | 현재 상태 | 개선 방향 |
|---|---|---|
| **시그널 차원 안내** | 화면에 시간 척도 라벨 없음 → 사용자 혼란 | 각 점수/시그널 옆에 "장기/중기/단기" 태그 |
| **점수 일관성** | TOP5 리스트와 상세 페이지 점수 모델 다름 | 단일 통합 점수 또는 두 점수 동시 표시 + 차이 설명 |
| **매수 결정 가이드** | 사용자 수동 매매에 hard rule 없음 | 체크리스트 컴포넌트 (5개 중 N개 충족) |
| **백테스트** | AiStrategyDashboardPage 부분 구현 | 전략별 과거 수익률 검증 강화 |
| **포지션 사이징** | 자동매매는 비율 고정, 수동은 사용자 입력 | 변동성 기반 동적 사이징 |
| **세금/수수료** | 실전 매매 시 수동 계산 | 자동 차감 표시 |

---

## 11. 외부 AI에게 질문할 때 권장 컨텍스트

이 문서를 외부 AI에 줄 때 함께 주면 좋은 추가 컨텍스트:

1. **현재 발생 중인 사용자 페인**: "추천 강력매수 종목이 상세 페이지에서 관망으로 표시되어 매수 결정이 어렵다"
2. **운영 환경**: Spring Boot 4.0, Spring Framework 7, MariaDB, Redis, 단일 사용자 운영 (개인 프로젝트).
3. **트레이딩 시간**: KRX 정규장 09:00~15:30 KST, 프리/애프터마켓 08:00~20:00.
4. **현재 활성 기능**: 자동매매 모의 / 스윙 실전 / KIS WebSocket 비활성 / 텔레그램 3채널 활성.
5. **현재 비활성**: 종가 매수 전략, Sentry, KIS WebSocket(옵션).
