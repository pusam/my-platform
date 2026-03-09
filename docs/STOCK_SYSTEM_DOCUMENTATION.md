# 주식 시스템 종합 문서

> 최종 업데이트: 2026-03-09

---

## 목차
1. [화면(라우트) 목록](#1-화면라우트-목록)
2. [프론트엔드 뷰 상세](#2-프론트엔드-뷰-상세)
3. [V2 컴포넌트 상세](#3-v2-컴포넌트-상세)
4. [백엔드 서비스 상세](#4-백엔드-서비스-상세)
5. [점수/임계값 종합](#5-점수임계값-종합)
6. [API 엔드포인트](#6-api-엔드포인트)
7. [외부 API 연동](#7-외부-api-연동)
8. [스케줄 작업](#8-스케줄-작업)
9. [주요 Enum/상수](#9-주요-enum상수)

---

## 1. 화면(라우트) 목록

| 경로 | 컴포넌트 | 설명 |
|------|---------|------|
| `/stock-dashboard` | StockTradingDashboardV2 | V2 통합 주식 대시보드 (시장뷰/종목발굴/내계좌봇) |
| `/ai-strategy` | AiStrategyDashboardPage | AI 4분할 트레이딩 전략 |
| `/stock/:stockCode` | StockDetailDashboard | 종목 종합 상세보기 |
| `/stock-detail` | StockDetailDashboard | 종목 상세 (코드 없음) |
| `/investor-stock/:stockCode` | InvestorStockDetailPage | 투자자별 매매 동향 + 차트 |
| `/sector` | SectorTradingPage | 섹터별 거래대금 |
| `/market-timing` | MarketTimingPage | ADR 기반 시장 타이밍 |
| `/trading-indicators` | TradingIndicatorsPage | 글로벌 시장 지표 |
| `/paper-trading` | PaperTradingPage | 모의/실전 자동매매 |
| `/global-futures` | GlobalFuturesPage | 해외선물 + VIX 공포지수 |
| `/oil` | OilPricePage | WTI 원유 시세 |
| `/user` | UserDashboard | 메인 대시보드 |

---

## 2. 프론트엔드 뷰 상세

### 2.1 StockTradingDashboardV2.vue (V2 통합 대시보드)

**3개 메인 탭:**
- 📊 시장 뷰 — 섹터히트맵, 시장지수, 뉴스피드
- 🔍 종목 발굴 — AI전략, 관심종목, 스마트머니, 실적스크리너, AI성과
- 🤖 내 계좌/봇 — PaperTradingPage 임베드

**API 호출 & 타임아웃:**
| API | 타임아웃 | 용도 |
|-----|---------|------|
| marketV2API.getSectors('TODAY') | 5초 | 섹터 데이터 |
| marketV2API.getStatus() | 5초 | KOSPI/KOSDAQ |
| marketV2API.getNasdaqFutures() | 5초 | 나스닥 선물 |
| globalFuturesAPI.getQuote('KRW') | 5초 | USD/KRW 환율 |
| aiStrategyV2API.getLatest() | 15초 | AI 전략 (V2) |
| investorAPI.getTopTradesRealtime() | 10초 | 실시간 매매동향 |

**자동 갱신:** 60초 (활성 탭만)

**폭락 감지 (프론트엔드):**
```javascript
isCrashStatus:
  KOSPI 또는 KOSDAQ 등락률 <= -3% → true
  marketStatus에 '폭락'/'패닉'/'CRASH' 포함 → true
```

**globalData 반응성:**
```javascript
// 개별 프로퍼티 할당 대신 새 객체 통째로 할당
this.globalData = newGlobalData  // Vue 반응성 보장
```

---

### 2.2 AiStrategyDashboardPage.vue (AI 4분할 전략)

**4분할 전략:** ⚡스캘핑, 📈스윙, 🔄턴어라운드, 💎가치투자

**종합점수 → 의견 매핑:**
| 점수 | 의견 | CSS 클래스 |
|------|------|-----------|
| ≥ 70 | 적극 매수 | strong-buy |
| 50–69 | 매수 | buy |
| 30–49 | 관망 | neutral |
| < 30 | 매도 | sell |

---

### 2.3 GlobalFuturesPage.vue (해외선물 대시보드)

**코스피 영향 분석 배너:**
- Impact Score (0–100%)
- 리스크 팩터 분석

**VIX 공포지수 구간:**
| VIX 값 | 상태 | CSS 클래스 |
|--------|------|-----------|
| ≥ 30 | 극심한 공포 | vix-extreme |
| 25–29 | 공포 | vix-fear |
| 20–24 | 경계 | vix-caution |
| 15–19 | 보통 | — |
| < 15 | 안정 | vix-calm |

**Alert Level 매핑:**
| 레벨 | 표시 | 설명 |
|------|------|------|
| CRISIS | 폭락 경계 | 극심한 하방 압력 |
| EXTREME_NEGATIVE | 극심한 약세 | 하방 변동성 경고 |
| EXTREME_POSITIVE | 강한 강세 | 강세 모멘텀 |
| NEGATIVE | 약세 주의 | — |
| WEAK_NEGATIVE | 소폭 약세 | — |
| WEAK_POSITIVE | 소폭 강세 | — |
| POSITIVE | 강세 예상 | — |
| NEUTRAL | 보합 예상 | — |

**자동 갱신:** 30초 간격 (토글 가능)

---

### 2.4 SectorTradingPage.vue (섹터 거래대금)

**기간 탭:** 📊 오늘누적(TODAY), ⚡ 5분파워(MIN_5), 🔥 30분파워(MIN_30)

**거래대금 포맷:**
| 금액 | 표시 |
|------|------|
| ≥ 1조 | x.xx조 |
| ≥ 100억 | x억 |
| ≥ 1만 | x만 |
| < 1만 | 원 단위 |

**자동 갱신:** 5분 간격

---

### 2.5 MarketTimingPage.vue (ADR 시장 타이밍)

**ADR 차트:** 60일 추이 (KOSPI, KOSDAQ, 종합)
- 최소 5일 데이터 필요 (미만 시 "데이터 수집 중")
- 최소 20일 데이터 필요 (ADR20 계산)

---

### 2.6 OilPricePage.vue (WTI 원유 시세)

- WTI 1배럴 USD 가격 + KRW 환산가
- 최근 1개월 Bar 차트 (Chart.js)
- 자동 갱신: 60초

---

### 2.7 InvestorStockDetailPage.vue (투자자별 매매 동향)

- 주가 vs 누적 순매수 추이 (3M/6M/1Y)
- 장중 수급 추이 (외국인/기관/연기금 탭)
- 상위 20위 데이터

---

### 2.8 UserDashboard.vue (메인 대시보드)

**섹션:**
1. 시장 정보 위젯 (MarketInfoWidget)
2. 주식 트레이딩 대시보드 V2 바로가기
3. 시세 섹션 (금/은/원유/글로벌선물 — 위젯 설정으로 토글)
4. 관리 섹션 (콘텐츠/설정/파일/자동차/자산/가계부)
5. 기타 (게시판/로또/연금복권)

**위젯 설정:** `localStorage.dashboardWidgets`

---

## 3. V2 컴포넌트 상세

### 3.1 SectionMarketMap.vue (시장 지도)

**내부 탭:** Heatmap → Market → Global → Forecast

**ADR Gauge 상태:**
| ADR 값 | 상태 |
|--------|------|
| ≥ 120 | 과열 |
| 100–119 | 강세 |
| 80–99 | 보합 |
| 60–79 | 약세 |
| < 60 | 침체 |

**폭락 감지 시:**
- ADR 무시, 강제 100% 빨간색
- "⚠️ 폭락 감지 — ADR 무시됨" 라벨

**USD/KRW:**
- 데이터 없을 때: "데이터 지연" (주황색)

---

### 3.2 MarketInfoWidget.vue (시장 상태 위젯)

**폭락 감지:**
```javascript
isCrash:
  kospiRate <= -3% OR kosdaqRate <= -3%
  OR diagnosis에 '폭락'/'패닉'/'CRASH' 포함
```

**시장 상태 → 아이콘/설명:**
| 상태 | 아이콘 | 설명 |
|------|--------|------|
| 폭락 | 🚨 | 관망 및 리스크 관리 필수 |
| 과열 (ADR≥120) | 🔥 | 추격 매수 주의, 익절 고려 |
| 강세 (100–119) | 📈 | 상승 추세, 눌림목 매수 유효 |
| 보합 (80–99) | ➡️ | 방향성 약함, 전략 필요 |
| 약세 (60–79) | 📉 | 하락 위험, 매수 보류 |
| 침체 (<60) | 💎 | 저가 매수 기회, 분할 진입 |

---

### 3.3 SectionAiStrategy.vue (AI 전략 요약)

**AI 점수 클래스:**
| 점수 | 클래스 | 색상 |
|------|--------|------|
| ≥ 70 | ai-high | 녹색 |
| 50–69 | ai-mid | 노랑 |
| < 50 | ai-low | 회색 |

---

### 3.4 SectionSmartMoney.vue (투자자 매매동향)

**3개 서브탭:** 매매 동향 / 연속 매수 / 수급 급증

**순매수 포맷:**
| 금액 | 표시 |
|------|------|
| ≥ 1억 | ±x억 |
| < 1억 | ±x백만 |

---

### 3.5 SectionResearch.vue (실적 스크리너)

**스크리너 종류:** 마법의 공식 / 저PEG 성장주 / 턴어라운드

**스코어 배지:**
| 점수 | 클래스 | 색상 |
|------|--------|------|
| ≥ 80 | badge-high | 녹색 |
| 50–79 | badge-mid | 노랑 |
| < 50 | badge-low | 회색 |

---

## 4. 백엔드 서비스 상세

### 4.1 StockAnalysisService (더블체크 분석)

**최종 점수 공식:**
- **Trading Score (단기):** MA기술(60%) + 수급(40%)
- **Fundamental Score (중장기):** 재무(30%) + 수급(35%) + 기술(35%)

**재무건전성 점수 (0–100, 기본 50점):**
| 조건 | 점수 |
|------|------|
| 영업이익률 > 15% | +20 |
| 영업이익률 > 10% | +15 |
| 영업이익률 > 5% | +10 |
| 영업이익률 > 0% | +5 |
| 영업이익률 < 0% | -10 |
| ROE > 15% | +15 |
| ROE > 10% | +10 |
| ROE > 5% | +5 |
| ROE < 0% | -15 |
| 부채비율 < 50% | +10 |
| 부채비율 < 100% | +5 |
| 부채비율 > 200% | -15 |
| 일회성 이익 경고 | -20 |

**수급 점수 (0–100, 기본 50점):**
| 조건 | 점수 |
|------|------|
| 외국인/기관 순매수 (>0) | +15 기본 + 매수일수×3 |
| 외국인/기관 순매도 (<0) | -20 기본 - 매도일수×4 |
| 순매도 > 100억 | 추가 -10 |
| 외국인+기관 동시 매도 | 추가 -15 |

**종합 판정 (Verdict):**
| 조건 | 판정 |
|------|------|
| score ≥ 75 - warningCount×10 | STRONG_BUY (매수 적기) |
| score ≥ 60 - warningCount×10 | BUY (매수 고려) |
| score ≥ 45 - warningCount×10 | NEUTRAL (관망 권고) |
| score ≥ 30 - warningCount×10 | CAUTION (주의 요망) |
| 그 외 | AVOID (매수 비추천) |

---

### 4.2 TechnicalIndicatorService (기술적 분석)

**이동평균선:** MA5, MA20, MA60, MA120

**RSI (14일):**
| RSI 값 | 상태 |
|--------|------|
| ≥ 70 | OVERBOUGHT (과열) |
| ≤ 30 | OVERSOLD (침체) |
| 31–69 | NEUTRAL (중립) |

**볼린저 밴드 (20일):**
- 중심선 = 20일 SMA
- 상단 = 중심 + 2σ, 하단 = 중심 - 2σ
- 스퀴즈: Band Width ≤ 20일 평균 × 0.7
- 돌파: 종가 > 상단

**MFI (14일):**
| MFI 값 | 상태 |
|--------|------|
| ≥ 80 | OVERBOUGHT (매도 압력) |
| ≤ 20 | OVERSOLD (매수 기회) |
| 21–79 | NEUTRAL |

**Signal Strength (0–100, 기본 50):**
| 조건 | 점수 |
|------|------|
| 종가 > MA20 | +10 |
| 종가 < MA20 | -10 |
| 종가 > MA60 | +15 |
| 종가 < MA60 | -15 |
| 골든크로스 | +15 |
| 정배열 (5>20>60) | +15 |
| RSI ≤ 30 | +20 |
| RSI ≥ 70 | -10 |

**RSI 다이버전스:**
- 하락 다이버전스: 주가 신고가 BUT RSI 하락 → SELL/STRONG_SELL
- 상승 다이버전스: 주가 신저가 BUT RSI 상승 → BUY/STRONG_BUY

---

### 4.3 QuantScreenerService (퀀트 스크리너)

**마법의 공식:**
- 필터: PER > 0, ROE > 0, 부채비율 ≤ 200%
- 점수 = 영업이익률 순위 + ROE 순위 + PER 순위 (낮을수록 좋음)
- 영업이익률 없으면: (ROE순위 + PER순위) × 1.5

**저PEG 성장주:**
- PEG = PER / EPS 성장률
- 필터: PEG < 1.0, 시가총액 ≥ 500억, EPS성장 ≤ 200%, ROE ≥ 0%

**턴어라운드:**
- LOSS_TO_PROFIT: 적자 → 흑자 전환
- PROFIT_GROWTH: 이익 급증
- MARGIN_IMPROVEMENT: 마진 개선
- 최소 순이익: 30억

---

### 4.4 InvestorSurgeService (수급 급증 감지)

**급증 임계값:**
| 레벨 | 단일 투자자 순매수 |
|------|-----------------|
| HOT | ≥ 100억 |
| WARM | ≥ 50억 |
| NORMAL | 기타 |

**듀얼 매칭:** 외국인+기관 동시 ≥ 30억

**알림 쿨다운:** 30분

**수집 주기:** 평일 09:02~15:22 매 10분

---

### 4.5 GlobalFuturesService (글로벌 선물)

**지원 종목:**
| 심볼 | Yahoo 코드 | 이름 | 카테고리 |
|------|-----------|------|---------|
| NQ | NQ=F | Nasdaq 100 | Index |
| ES | ES=F | S&P500 E-mini | Index |
| YM | YM=F | Dow E-mini | Index |
| CL | CL=F | WTI Crude | Commodity |
| GC | GC=F | Gold | Commodity |
| 6E | EURUSD=X | EUR/USD | Currency |
| 6J | USDJPY=X | USD/JPY | Currency |
| KRW | KRW=X | USD/KRW | Currency |
| VIX | ^VIX | VIX 공포지수 | Volatility |

**캐시:** 60초

---

### 4.6 StockDetailService (종목 상세 + AI 분석)

**technicalSignal ↔ recommendation 동기화:**
```java
// 신호가 약세인데 추천이 매수일 때 자동 보정
"이평선 하향 이탈" + "BUY" → technicalSignal = "수급 강세 (적극 매수)"
"이평선 하향 이탈" + "TRADING_BUY" → technicalSignal = "단기 매수 구간"
"이평선 하향 이탈" + "WAIT_AND_BUY" → technicalSignal = "조정 대기 (눌림목 매수)"
```

---

## 5. 점수/임계값 종합

### 프론트엔드 임계값

| 항목 | 임계값 | 동작 |
|------|--------|------|
| 폭락 감지 | KOSPI/KOSDAQ ≤ -3% | ADR 무시, 강제 빨간색, 관망 권고 |
| ADR 과열 | ≥ 120 | 추격 매수 주의 |
| ADR 강세 | 100–119 | 눌림목 매수 유효 |
| ADR 보합 | 80–99 | 방향성 약함 |
| ADR 약세 | 60–79 | 매수 보류 |
| ADR 침체 | < 60 | 분할 진입 기회 |
| VIX 극심 | ≥ 30 | 극심한 공포 |
| VIX 공포 | ≥ 25 | 공포 |
| VIX 경계 | 15–24 | 경계 |
| VIX 안정 | < 15 | 안정 |
| AI 적극매수 | 종합점수 ≥ 70 | — |
| AI 매수 | 50–69 | — |
| AI 관망 | 30–49 | — |
| AI 매도 | < 30 | — |

### 백엔드 임계값

| 항목 | 임계값 | 용도 |
|------|--------|------|
| 일회성 이익 | 영업이익-순이익 갭 50% | 재무 경고 |
| 부채비율 위험 | > 200% | 매수 필터 아웃 |
| PEG 저평가 | < 1.0 | 성장주 필터 |
| 수급 급증 HOT | ≥ 100억 | 알림 |
| 수급 급증 WARM | ≥ 50억 | 관심 |
| 듀얼 매칭 | 외+기 ≥ 30억 | 강한 신호 |
| RSI 과매수 | ≥ 70 | 조정 주의 |
| RSI 과매도 | ≤ 30 | 반등 기회 |
| MFI 과매수 | ≥ 80 | 매도 압력 |
| MFI 과매도 | ≤ 20 | 매수 기회 |
| 볼린저 스퀴즈 | BW ≤ 평균×0.7 | 변동성 확대 예고 |
| 리스크 DANGER | ≥ 80점 | 매수 금지 |
| 리스크 WARNING | 31–79점 | 주의 |
| 리스크 SAFE | 0–30점 | 안전 |

---

## 6. API 엔드포인트

### 분석
```
GET  /api/analysis/diagnosis/{stockCode}     → StockDiagnosisDto
POST /api/analysis/batch-scores              → Map<code, scores>
```

### 스크리너
```
GET  /api/screener/magic-formula?limit=30    → List<ScreenerResultDto>
GET  /api/screener/peg?maxPeg=1.0&limit=30   → List<ScreenerResultDto>
GET  /api/screener/turnaround?limit=30       → List<ScreenerResultDto>
GET  /api/screener/momentum?limit=30         → List<ScreenerResultDto>
```

### 시장 지표
```
GET  /api/market/52week-high                 → List<MarketIndicatorStockDto>
GET  /api/market/52week-low
GET  /api/market/market-cap
GET  /api/market/trading-value
GET  /api/market/price-rise
GET  /api/market/price-fall
```

### 글로벌 선물
```
GET  /api/global-futures/kospi-impact        → quotes + impact + riskFactors
GET  /api/global-futures/quote/{symbol}      → 개별 선물 시세
```

---

## 7. 외부 API 연동

| 서비스 | 용도 | 비고 |
|--------|------|------|
| KIS API | 국내주식 실시간/일봉/투자자/순위 | 토큰 24h, 200ms 쿨다운 |
| Yahoo Finance | 해외선물, 원유, VIX, 환율 | 캐시 60초 |
| Naver Finance | 주가 폴백 (KIS 실패 시) | 15분 지연 |
| Gemini API | AI 종목분석, 테마 태깅 | Rate limit + Ollama 폴백 |
| DART API | 공시 문서 (리스크 분석) | 선택적 |

---

## 8. 스케줄 작업

| 서비스 | 스케줄 | 용도 |
|--------|--------|------|
| MarketIndicatorService | 평일 18:00 | 시장 지표 순위 수집 |
| InvestorSurgeService | 평일 09:02~15:22 매 10분 | 장중 수급 급증 감지 |
| OilPriceService | 평일 07/10/14/18/22시 | WTI 시세 갱신 |
| 각종 서비스 | 서버 시작 +75초 | 캐시 워밍업 |

**데이터 보관:**
- MarketIndicatorSnapshot: 30일 보관
- AlertHistory: 수급 알림 쿨다운 30분

---

## 9. 주요 Enum/상수

### VerdictLevel (종합 판정)
```
STRONG_BUY  → "매수 적기"
BUY         → "매수 고려"
NEUTRAL     → "관망 권고"
CAUTION     → "주의 요망"
AVOID       → "매수 비추천"
```

### TechnicalSignal
```
STRONG_BUY  (strength ≥ 80)
BUY         (strength ≥ 60)
NEUTRAL     (strength ≥ 40)
SELL        (strength ≥ 20)
STRONG_SELL (strength < 20)
```

### InvestorType
```
FOREIGN, INSTITUTION, PENSION, INDIVIDUAL, INVEST_TRUST
```

### MarketType
```
KOSPI, KOSDAQ
```

### TurnaroundType
```
LOSS_TO_PROFIT    → 흑자전환
PROFIT_GROWTH     → 이익급증
MARGIN_IMPROVEMENT → 마진개선
```

### MarketStatus (프론트엔드 sign 코드)
```
'1' → 상한  '2' → 상승  '3' → 보합  '4' → 하한  '5' → 하락
```

### V2 API 폴백 패턴
```
V2 Python API 시도 → 실패 시 Java API 폴백
타임아웃: API별 3초~15초
```
