# 주식 시스템 종합 문서

> 최종 업데이트: 2026-03-09 (리팩토링 반영)

---

## 목차
1. [화면(라우트) 목록](#1-화면라우트-목록)
2. [공통 모듈 (Composable / Utility)](#2-공통-모듈)
3. [프론트엔드 뷰 상세](#3-프론트엔드-뷰-상세)
4. [V2 컴포넌트 상세](#4-v2-컴포넌트-상세)
5. [백엔드 서비스 상세](#5-백엔드-서비스-상세)
6. [점수/임계값 종합](#6-점수임계값-종합)
7. [API 엔드포인트](#7-api-엔드포인트)
8. [외부 API 연동](#8-외부-api-연동)
9. [스케줄 작업](#9-스케줄-작업)
10. [주요 Enum/상수](#10-주요-enum상수)

---

## 1. 화면(라우트) 목록

### 활성 라우트

| 경로 | 컴포넌트 | 설명 |
|------|---------|------|
| `/user` | UserDashboard | 메인 대시보드 (진입점) |
| `/stock-dashboard` | StockTradingDashboardV2 | V2 통합 주식 대시보드 (시장뷰/종목발굴/내계좌봇) |
| `/stock/:stockCode` | StockDetailDashboard | 종목 종합 상세보기 |
| `/ai-strategy` | AiStrategyDashboardPage | AI 4분할 트레이딩 전략 |
| `/investor-stock/:stockCode` | InvestorStockDetailPage | 투자자별 매매 동향 + 차트 |
| `/investor-trades` | InvestorTradePage | 투자자 매매 목록 (V2 스마트머니 "더 보기") |
| `/consecutive-buy` | ConsecutiveBuyPage | 연속 매수 종목 (V2 스마트머니 "전체 목록") |
| `/investor-surge` | InvestorSurgePage | 수급 급증 종목 (V2 스마트머니 "전체 목록") |
| `/earnings-screener` | EarningsScreenerPage | 실적 스크리너 (V2 리서치 "더 보기") |
| `/sector` | SectorTradingPage | 섹터별 거래대금 (V2 시장지도 "더 보기") |
| `/market-timing` | MarketTimingPage | ADR 기반 시장 타이밍 (V2 시장지표 링크) |
| `/trading-indicators` | TradingIndicatorsPage | 글로벌 시장 지표 (V2 글로벌 "트레이딩 지표") |
| `/paper-trading` | PaperTradingPage | 모의/실전 자동매매 (V2 내계좌탭 임베드) |
| `/global-futures` | GlobalFuturesPage | 해외선물 + VIX 공포지수 |
| `/oil` | OilPricePage | WTI 원유 시세 |

### 리다이렉트 라우트

| 경로 | 대상 | 사유 |
|------|------|------|
| `/stock-detail` | → `/stock-dashboard` | stockCode 없이 접근 시 무의미, 미사용 |
| `/ai-stock` | → `/ai-strategy` | 레거시 경로 호환 |
| `/dashboard` | → `/user` 또는 `/admin` | 역할 기반 리다이렉트 |

### 화면 간 네비게이션 흐름

```
UserDashboard (/user)
  └─ StockTradingDashboardV2 (/stock-dashboard) ← 메인 허브
       ├─ [시장 뷰 탭]
       │    ├─ SectionMarketMap → /sector (더 보기)
       │    │                   → /market-timing (시장 타이밍)
       │    │                   → /trading-indicators (트레이딩 지표)
       │    └─ 종목 클릭 → /stock/:stockCode
       ├─ [종목 발굴 탭]
       │    ├─ SectionAiStrategy → /ai-strategy (상세)
       │    ├─ SectionSmartMoney → /investor-trades (더 보기)
       │    │                    → /consecutive-buy (전체 목록)
       │    │                    → /investor-surge (전체 목록)
       │    │                    → /investor-stock/:code (종목 클릭)
       │    ├─ SectionResearch → /earnings-screener (더 보기)
       │    └─ 종목 클릭 → /stock/:stockCode
       └─ [내 계좌/봇 탭]
            └─ PaperTradingPage (embedded)
```

---

## 2. 공통 모듈

### 2.1 useMarketStatus.js (컴포저블)

**경로:** `frontend/src/composables/useMarketStatus.js`

**사용처:** MarketInfoWidget, SectionMarketMap, GlobalFuturesPage

**폭락 감지:**
```javascript
checkCrash(data)
  → extractKospiRate(data) / extractKosdaqRate(data)
  → 등락률 <= -3% 이면 true
  → diagnosis/marketStatus에 '폭락'/'패닉'/'CRASH' 포함이면 true
```

**ADR 시장 상태:**
```javascript
getMarketStatus(isCrash, adr) → { status, title, icon, desc, badgeClass, adrClass }
```

| ADR 값 | status | title | icon | desc |
|--------|--------|-------|------|------|
| 폭락 감지 | crash | 폭락장 | 🚨 | 관망 및 리스크 관리 필수 |
| ≥ 120 | overheated | 과열 | 🔥 | 추격 매수 주의, 익절 고려 |
| 100–119 | bullish | 강세 | 📈 | 상승 추세, 눌림목 매수 유효 |
| 80–99 | normal | 보합 | ➡️ | 방향성 탐색 중 |
| 60–79 | bearish | 약세 | 📉 | 하락 추세, 반등 대기 |
| < 60 | extreme-fear | 침체 | 💎 | 저점 매수 기회 탐색 |

**VIX 구간 분류:**
```javascript
getVixStatus(vixValue) → { cssClass, text, emoji }
getVixMeterWidth(vixValue) → 0~100 (게이지 %)
```

| VIX 값 | cssClass | text | emoji |
|--------|----------|------|-------|
| ≥ 30 | vix-extreme | 극심한 공포 | !! |
| 25–29 | vix-fear | 공포 | ! |
| 20–24 | vix-caution | 경계 | ~ |
| 15–19 | vix-normal | 보통 | |
| < 15 | vix-calm | 안정 | |

### 2.2 marketFormatters.js (유틸리티)

**경로:** `frontend/src/utils/marketFormatters.js`

**사용처:** MarketInfoWidget, SectionMarketMap, SectorTradingPage

| 함수 | 용도 | 예시 |
|------|------|------|
| `formatNumber(num, decimals)` | 숫자 포맷 (한국어 로케일) | `2584.23` → `"2,584.23"` |
| `formatChange(change)` | 등락률 포맷 | `1.5` → `"+1.50%"` |
| `getChangeClass(change, inverse)` | 등락률 CSS 클래스 | `1.5` → `"positive"`, 환율은 inverse |
| `formatTradingValue(value)` | 거래대금 포맷 (조/억/만) | `1.5e12` → `"1.50조"` |

**거래대금 포맷 규칙:**
| 금액 | 표시 |
|------|------|
| ≥ 1조 | x.xx조 |
| ≥ 1억 | x억 |
| ≥ 1만 | x만 |
| < 1만 | 원 단위 |
| 0/null | 0원 |

---

## 3. 프론트엔드 뷰 상세

### 3.1 StockTradingDashboardV2.vue (V2 통합 대시보드)

**경로:** `/stock-dashboard`

**3개 메인 탭:**
- 📊 시장 뷰 — 섹터히트맵, 시장지수, 뉴스피드
- 🔍 종목 발굴 — AI전략, 관심종목, 스마트머니, 실적스크리너, AI성과
- 🤖 내 계좌/봇 — PaperTradingPage 임베드 (embedded=true)

**종목 발굴 서브탭:**
| 키 | 이름 | 컴포넌트 |
|-----|------|---------|
| ai | AI 전략 | SectionAiStrategy |
| watchlist | 관심종목 | SectionWatchlist |
| smart | 스마트 머니 | SectionSmartMoney |
| screener | 실적 스크리너 | SectionResearch |
| backtest | AI 성과 | SectionBacktest |

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

**데이터 로딩 전략:**
- 탭 기반 lazy loading (처음 활성화 시에만 로드)
- V2 Python API → Java API 폴백 체인
- 타임아웃 래핑: API별 3초~15초
- globalData 반응성: 새 객체 통째로 할당 (Vue 반응성 보장)

**종목 검색:** Ctrl+K → StockSearchModal → `/stock/:stockCode` 이동

---

### 3.2 AiStrategyDashboardPage.vue (AI 4분할 전략)

**경로:** `/ai-strategy`

**4분할 전략:** ⚡스캘핑, 📈스윙, 🔄턴어라운드, 💎가치투자

**종합점수 → 의견 매핑:**
| 점수 | 의견 | CSS 클래스 |
|------|------|-----------|
| ≥ 70 | 적극 매수 | strong-buy |
| 50–69 | 매수 | buy |
| 30–49 | 관망 | neutral |
| < 30 | 매도 | sell |

---

### 3.3 GlobalFuturesPage.vue (해외선물 대시보드)

**경로:** `/global-futures`

**코스피 영향 분석 배너:**
- Impact Score (0–100%)
- 리스크 팩터 분석 (복합 분석 지표)

**VIX 공포지수 패널:**
- 공통 컴포저블 `getVixStatus()` 사용 (→ `useMarketStatus.js`)
- VIX 미터 게이지: `getVixMeterWidth()` (0~50+ → 0~100%)

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

**선물 카테고리:**
| 카테고리 | 종목 |
|---------|------|
| 메인 카드 | KM (코스피200 야간선물) |
| 미국 지수 | NQ, ES, YM |
| 원자재 | category='commodity' |
| 통화 | category='currency' |

**자동 갱신:** 30초 간격 (토글 가능)

---

### 3.4 SectorTradingPage.vue (섹터 거래대금)

**경로:** `/sector`

**기간 탭:** 📊 오늘누적(TODAY), ⚡ 5분파워(MIN_5), 🔥 30분파워(MIN_30)

**거래대금 포맷:** 공통 유틸리티 `formatTradingValue()` 사용 (→ `marketFormatters.js`)

**자동 갱신:** 5분 간격, 데이터 비어있으면 5초 후 자동 재시도

---

### 3.5 MarketTimingPage.vue (ADR 시장 타이밍)

**경로:** `/market-timing`

**ADR 차트:** 60일 추이 (KOSPI, KOSDAQ, 종합)
- 최소 5일 데이터 필요 (미만 시 "데이터 수집 중")
- 최소 20일 데이터 필요 (ADR20 계산)
- visibleDatasets: kospi/kosdaq/combined 토글

---

### 3.6 OilPricePage.vue (WTI 원유 시세)

**경로:** `/oil`

- WTI 1배럴 USD 가격 + KRW 환산가
- 시가/고가/저가/종가 + 거래량
- 최근 1개월 Bar 차트 (Chart.js)
- 자동 갱신: 60초

---

### 3.7 InvestorStockDetailPage.vue (투자자별 매매 동향)

**경로:** `/investor-stock/:stockCode`

- 주가 vs 누적 순매수 추이 (기간: 1M/3M/6M/1Y/YTD)
- 듀얼축 차트: 주가(회색), 외국인(빨강), 기관(녹색), 연기금(보라)
- 장중 수급 추이 (외국인/기관/연기금 탭)
- 상위 20위 데이터

---

### 3.8 StockDetailDashboard.vue (종목 종합 상세)

**경로:** `/stock/:stockCode`

**구조:**
- 헤더: 종목명, 현재가, 듀얼 AI 점수박스 (단기 Trading + 중장기 Fundamental)
- 좌측: 캔들스틱 차트 + MA + 거래량, 재무지표, 동종업종 비교, 관련뉴스
- 우측: 체결강도 + 수급 분석
- 실시간 자동갱신 (10초 간격, 토글)

**데이터 소스:**
- 주가/등락 정보
- AI 분석 점수 (단기/중장기)
- 진단 데이터 (재무/수급/기술적)
- 차트 데이터 (캔들스틱, MA, 거래량)
- 동종업종 비교 (수평 바 차트)

---

### 3.9 UserDashboard.vue (메인 대시보드)

**경로:** `/user`

**섹션:**
1. 시장 정보 위젯 (MarketInfoWidget)
2. 주식 트레이딩 대시보드 V2 바로가기 (전체 너비)
3. 시세 섹션 (금/은/원유/글로벌선물 — 위젯 설정으로 토글)
4. 관리 섹션 (콘텐츠/설정/파일/자동차/자산/가계부)
5. 기타 (게시판/로또/연금복권)

**위젯 설정:** `localStorage.dashboardWidgets` (기본값: 모두 활성)

---

### 3.10 TradingIndicatorsPage.vue (트레이딩 지표)

**경로:** `/trading-indicators`

**구조:**
1. 글로벌 시장 (나스닥100 선물, S&P500 선물, 반도체지수)
2. 글로벌 악재 필터 (매수보류/가능)
3. 주도 섹터 랭킹 (상위/하위)
4. VWAP 분석 (종목코드 입력 → 시그널)

---

### 3.11 PaperTradingPage.vue (모의/실전 자동매매)

**경로:** `/paper-trading` (독립), V2 대시보드 내계좌탭 (embedded)

**탭:** 🤖 모의투자, 🔴 실전투자

**모의투자:** 초기자본, 현재잔액, 총자산 요약 + 보유종목 + 주문내역 + 성과분석

**Props:** `embedded: Boolean` — true면 헤더 숨김 (V2 내장 모드)

---

## 4. V2 컴포넌트 상세

**경로:** `frontend/src/components/v2/`

### 4.1 SectionMarketMap.vue (시장 지도)

**내부 탭:** 섹터 히트맵 → 시장 지표 → 글로벌 → AI 예측

**폭락/ADR 분류:** 공통 컴포저블 `checkCrash()`, `getMarketStatus()` 사용 (→ `useMarketStatus.js`)

**폭락 감지 시:**
- ADR 무시, 강제 100% 빨간색 (adr-crash)
- "⚠️ 폭락 감지 — ADR 무시됨" 라벨
- 시장 상태: "🚨 폭락장 (관망 및 리스크 관리 필수)"

**USD/KRW:**
- 데이터 없을 때: "데이터 지연" (주황색, `.data-delayed`)

**AI 예측 (forecast 탭):**
- 탭 진입 시 lazy 로드 (`marketAPI.getForecast()`)
- 3 시나리오 (Bull/Base/Bear): 확률 + 이유 + 5일 차트
- 실패 시 1회 자동 재시도 (5초 후)
- 폴백 안내: "AI 분석 일시 불가 — 기계적 예측"

---

### 4.2 MarketInfoWidget.vue (시장 상태 위젯)

**경로:** `frontend/src/components/MarketInfoWidget.vue` (UserDashboard 전용)

**폭락/ADR 분류:** 공통 컴포저블 `checkCrash()`, `getMarketStatus()` 사용 (→ `useMarketStatus.js`)
**포맷:** 공통 유틸 `formatNumber()`, `formatChange()`, `getChangeClass()` 사용 (→ `marketFormatters.js`)

**구조:**
- 좌측: 시장 상태 카드 (아이콘 + 제목 + 설명 + ADR 배지)
- 우측: 지수/환율 카드 (KOSPI, KOSDAQ, USD/KRW)

**API 호출:**
- `marketAPI.getSimpleStatus()` — 시장 상태
- `exchangeRateAPI.getCurrentRate()` — 환율

**자동 갱신:** 5분 간격

---

### 4.3 SectionAiStrategy.vue (AI 전략 요약)

**4개 전략 점수바 + 종합점수 + 활성 탭 TOP 3 종목 카드**

**AI 점수 클래스:**
| 점수 | 클래스 | 색상 |
|------|--------|------|
| ≥ 70 | ai-high | 녹색 |
| 50–69 | ai-mid | 노랑 |
| < 50 | ai-low | 회색 |

---

### 4.4 SectionSmartMoney.vue (투자자 매매동향)

**3개 서브탭:**
| 탭 | 내용 | "더 보기" 링크 |
|----|------|--------------|
| 매매 동향 (당일) | 외국인/기관 토글, TOP 10 | `/investor-trades` |
| 연속 매수 (최근 30일) | 연속 매수 일수 + 투자자 타입 | `/consecutive-buy` |
| 수급 급증 (장중) | 수급 비율 + 변화량 | `/investor-surge` |

**순매수 포맷:**
| 금액 | 표시 |
|------|------|
| ≥ 1억 | ±x억 |
| < 1억 | ±x백만 |

---

### 4.5 SectionResearch.vue (실적 스크리너)

**"더 보기" 링크:** `/earnings-screener`

**스크리너 종류:** 마법의 공식 / 저PEG 성장주 / 턴어라운드 (각 TOP 3)

**AI 쌍끌이:** 단기 + 중장기 80점 이상 종목

**스코어 배지:**
| 점수 | 클래스 | 색상 |
|------|--------|------|
| ≥ 80 | badge-high | 녹색 |
| 50–79 | badge-mid | 노랑 |
| < 50 | badge-low | 회색 |

---

### 4.6 기타 V2 컴포넌트

| 컴포넌트 | 용도 |
|---------|------|
| DashboardHeader | GNB 3탭 (시장뷰/종목발굴/내계좌봇) + 검색 + 시간 |
| SectionWatchlist | 관심종목 목록 |
| SectionBacktest | AI 전략 백테스트 결과 |
| StockSearchModal | 종목 검색 모달 (Ctrl+K) |
| ForecastDetailModal | AI 예측 상세 팝업 |
| SkeletonLoader | 로딩 플레이스홀더 |
| WatchlistBookmark | 관심종목 등록/해제 |
| MagicFormulaSmartTable | 스크리너 테이블 |

---

## 5. 백엔드 서비스 상세

### 5.1 StockAnalysisService (더블체크 분석)

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

### 5.2 TechnicalIndicatorService (기술적 분석)

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

### 5.3 QuantScreenerService (퀀트 스크리너)

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

### 5.4 InvestorSurgeService (수급 급증 감지)

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

### 5.5 GlobalFuturesService (글로벌 선물)

**데이터 소스:** Yahoo Finance API (`https://query1.finance.yahoo.com/v8/finance/chart/`)

**지원 종목:**
| 심볼 | Yahoo 코드 | 이름 | 카테고리 |
|------|-----------|------|---------|
| NQ | NQ=F | Nasdaq 100 | Index |
| ES | ES=F | S&P500 E-mini | Index |
| YM | YM=F | Dow E-mini | Index |
| CL | CL=F | WTI Crude | Commodity |
| BZ | BZ=F | Brent Oil | Commodity |
| GC | GC=F | Gold | Commodity |
| 6E | EURUSD=X | EUR/USD | Currency |
| 6J | USDJPY=X | USD/JPY | Currency |
| KRW | KRW=X | USD/KRW | Currency |
| VIX | ^VIX | VIX 공포지수 | Volatility |

**캐시:** 60초

---

### 5.6 StockDetailService (종목 상세 + AI 분석)

**technicalSignal ↔ recommendation 동기화:**
```java
// 신호가 약세인데 추천이 매수일 때 자동 보정 (generateAiAnalysis + Gemini 분석 양쪽)
"이평선 하향 이탈" + "BUY"         → technicalSignal = "수급 강세 (적극 매수)"
"이평선 하향 이탈" + "TRADING_BUY" → technicalSignal = "단기 매수 구간"
"이평선 하향 이탈" + "WAIT_AND_BUY" → technicalSignal = "조정 대기 (눌림목 매수)"
```

---

### 5.7 OilPriceService (원유 시세)

**데이터 소스:** Yahoo Finance (CL=F)
**캐시:** 60초
**갱신:** 평일 07/10/14/18/22시 + 서버 시작 시

---

### 5.8 StockPriceService (주가 조회)

**데이터 소스:**
1. Primary: KIS API (실시간)
2. Fallback: Naver Finance API (15분 지연)

**Rate Limiting:** 200ms 딜레이, 2회 재시도 (exponential backoff)

---

## 6. 점수/임계값 종합

### 프론트엔드 임계값 (공통 모듈에서 관리)

| 항목 | 임계값 | 동작 | 관리 위치 |
|------|--------|------|----------|
| 폭락 감지 | KOSPI/KOSDAQ ≤ -3% | ADR 무시, 강제 빨간색, 관망 권고 | `useMarketStatus.js` |
| ADR 과열 | ≥ 120 | 추격 매수 주의 | `useMarketStatus.js` |
| ADR 강세 | 100–119 | 눌림목 매수 유효 | `useMarketStatus.js` |
| ADR 보합 | 80–99 | 방향성 약함 | `useMarketStatus.js` |
| ADR 약세 | 60–79 | 매수 보류 | `useMarketStatus.js` |
| ADR 침체 | < 60 | 분할 진입 기회 | `useMarketStatus.js` |
| VIX 극심 | ≥ 30 | 극심한 공포 | `useMarketStatus.js` |
| VIX 공포 | ≥ 25 | 공포 | `useMarketStatus.js` |
| VIX 경계 | 20–24 | 경계 | `useMarketStatus.js` |
| VIX 보통 | 15–19 | 보통 | `useMarketStatus.js` |
| VIX 안정 | < 15 | 안정 | `useMarketStatus.js` |
| AI 적극매수 | 종합점수 ≥ 70 | — | 각 컴포넌트 |
| AI 매수 | 50–69 | — | 각 컴포넌트 |
| AI 관망 | 30–49 | — | 각 컴포넌트 |
| AI 매도 | < 30 | — | 각 컴포넌트 |

### 백엔드 임계값

| 항목 | 임계값 | 용도 |
|------|--------|------|
| 일회성 이익 | 영업이익-순이익 갭 50% | 재무 경고 |
| 부채비율 위험 | > 200% | 매수 필터 아웃 |
| PEG 저평가 | < 1.0 | 성장주 필터 |
| 시총 최소 (PEG) | ≥ 500억 | 소형주 제외 |
| EPS성장 최대 (PEG) | ≤ 200% | 기저효과 제외 |
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

## 7. API 엔드포인트

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
GET  /api/screener/summary                   → 스크리너 요약 (V2에서 사용)
```

### 시장 지표
```
GET  /api/market/status                      → 시장 상태 (KOSPI/KOSDAQ + ADR)
GET  /api/market/status/simple               → 간단 시장 상태 (MarketInfoWidget용)
GET  /api/market/adr/history?days=60         → ADR 히스토리
GET  /api/market/forecast                    → AI 시장 예측 (5일, 60초 타임아웃)
GET  /api/market/52week-high                 → 52주 신고가 TOP 50
GET  /api/market/52week-low                  → 52주 신저가
GET  /api/market/market-cap                  → 시가총액 상위
GET  /api/market/trading-value               → 거래대금 상위
GET  /api/market/price-rise                  → 상승률 상위
GET  /api/market/price-fall                  → 하락률 상위
```

### 투자자 매매
```
GET  /api/investor/top-trades/realtime       → 실시간 상위 매매
GET  /api/investor/top-trades                → 투자자별 상위 매수/매도
GET  /api/investor/all-top-trades            → 외국인+기관 통합 상위
GET  /api/investor/consecutive-buy/all       → 연속 매수 종목
GET  /api/investor/surge/all                 → 수급 급증 종목
GET  /api/investor/surge/common              → 외+기 공통 순매수
```

### 글로벌 선물
```
GET  /api/global-futures/kospi-impact        → quotes + impact + riskFactors
GET  /api/global-futures/quotes/{symbol}     → 개별 선물 시세
GET  /api/global-futures/quotes              → 전체 선물 시세
```

### 기타
```
GET  /api/exchange-rate                      → USD/KRW 환율
GET  /api/oil/price                          → WTI 원유 시세
GET  /api/oil/history/month                  → 원유 1개월 이력
GET  /api/stock/{stockCode}/summary          → 종목 종합 상세 (90초 타임아웃)
GET  /api/stock/search?keyword=              → 종목 검색
```

### V2 API (Python FastAPI)
```
GET  /api/v2/market/status                   → 시장 상태 (V2)
GET  /api/v2/market/sectors                  → 섹터 데이터 (V2)
GET  /api/v2/market/sectors/leading          → 주도 섹터 (V2)
GET  /api/v2/market/global/nasdaq-futures    → 나스닥 선물 (V2)
GET  /api/v2/ai-strategy/latest              → AI 전략 (V2)
GET  /api/v2/investor/top-trades             → 투자자 매매 (V2)
GET  /api/v2/investor/consecutive-buy/all    → 연속 매수 (V2)
GET  /api/v2/investor/surge/all              → 수급 급증 (V2)
GET  /api/v2/screener/summary                → 스크리너 요약 (V2)
GET  /api/v2/news/today                      → 뉴스 (V2)
```

**V2 폴백 패턴:** V2 Python API 시도 (2초 타임아웃) → 실패 시 Java API 폴백

---

## 8. 외부 API 연동

| 서비스 | 용도 | 비고 |
|--------|------|------|
| KIS API | 국내주식 실시간/일봉/투자자/순위 | 토큰 24h, 200ms 쿨다운, 재시도 2회 |
| Yahoo Finance | 해외선물, 원유, VIX, 환율 | 캐시 60초 |
| Naver Finance | 주가 폴백 (KIS 실패 시) | 15분 지연 |
| Gemini API | AI 종목분석, 테마 태깅, 시장 예측 | Rate limit + Ollama 폴백 |
| DART API | 공시 문서 (리스크 분석) | 선택적 |

---

## 9. 스케줄 작업

| 서비스 | 스케줄 | 용도 |
|--------|--------|------|
| MarketIndicatorService | 평일 18:00 | 시장 지표 순위 수집 (52주 고저/시총/거래대금 등) |
| InvestorSurgeService | 평일 09:02~15:22 매 10분 | 장중 수급 급증 감지 |
| OilPriceService | 평일 07/10/14/18/22시 | WTI 시세 갱신 |
| 각종 서비스 | 서버 시작 +75초 | 캐시 워밍업, 초기 데이터 로드 |

**데이터 보관:**
- MarketIndicatorSnapshot: 30일 보관, 초과분 자동 삭제
- AlertHistory: 수급 알림 쿨다운 30분
- StockPriceHistory: 장기 보관 (기술적 분석용)

---

## 10. 주요 Enum/상수

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
LOSS_TO_PROFIT     → 흑자전환
PROFIT_GROWTH      → 이익급증
MARGIN_IMPROVEMENT → 마진개선
```

### SurgeLevel
```
HOT    → 급증! (단일 ≥ 100억)
WARM   → 상승 (단일 ≥ 50억)
NORMAL → 기타
```

### TrendStatus (수급 급증)
```
ACCUMULATING   → 순매수 증가 중
PROFIT_TAKING  → 순매도 중
NORMAL         → 변동 없음
```

### MarketStatus (프론트엔드 sign 코드)
```
'1' → 상한  '2' → 상승  '3' → 보합  '4' → 하한  '5' → 하락
```

### 시장 거래 상태 (선물)
```
REGULAR  → 장중
PRE      → 프리마켓
POST     → 애프터마켓
POSTPOST → 애프터마켓 연장
CLOSED   → 장마감
```
