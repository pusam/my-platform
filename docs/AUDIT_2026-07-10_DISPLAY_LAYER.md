# AUDIT 2026-07-10 — 표시/프롬프트 레이어 정합성 (형제 사냥)

> 진단 세션 — **코드 수정 없음, 리포트만.** 수정은 합의 후 별도 세션.
> 배경: 기존 감사(07-07/08)는 봇·산식·불변식 축으로 **P0/P1 0**이었으나, StockDetail AI 카드에서 네 종류의 표시/프롬프트 결함이 나옴 —
> ① 가짜 뉴스 하드코딩 주입(§4c) ② 프론트 키 오타(`instNet5Days`) → 조용한 undefined ③ 라벨-본문 소스 분리 모순 ④ 프롬프트 날짜 미주입 → 환각.
> 본 감사는 **같은 패턴의 형제를 3축으로 전수**했다. 축1=Gemini 프롬프트 조립 전수 · 축2=직렬화 키↔프론트 접근 키 · 축3=라벨/뱃지 vs 근거 소스 분리.
> 방법: 독립 감사관 3(축별) + 직접 검증(근거 firsthand 확인). 심각도 = **현재 사용자 노출 영향 기준**.

> **[수정 진행 — 2026-07-10]** P1 3건 수정 완료(각 재현 테스트 green, 산식·시세경로·봇 무접촉):
> #1 forecast `0c6fd8c` · #2 recommendation 뱃지 `1b15edb` · #3 종합신호 근거병기 `3416000`. P2·P3 는 후속 세션.

## 🔎 요약 — **P0 없음**(활성 경로). P1 3 · P2 11 · P3 다수. 대부분 **네 가지 원형 결함의 구조적 형제**.

| # | 심각도 | 축 | 발견 | 위치 | 원형 |
|---|---|---|---|---|---|
| 1 | **P1** | 1 | 시장 예측 프롬프트에 장전 0(거래대금/상승·하락/등락률) raw 주입 + KOSPI 2700 하드코딩 폴백 + **기준일 미주입** | `GeminiService.buildForecastPrompt:581-587,516-517,619-655` → `/forecast` → `ForecastDetailModal.vue` | ①④ |
| 2 | **P1** | 1·3 | **`recommendation` 판정 뱃지**(BUY/SELL, 큰 초록 뱃지)가 Gemini 본문과 미정합 — 지난 수정은 작은 `technicalSignal` 칩만 고침 | `StockDetailService.parseGeminiResponse:2584-2597`; `AIStrategyCard.vue:33`·`StockDetailDashboard.vue:56`·`QuickSummaryBar.vue:43-47` | ③ |
| 3 | **P1** | 3 | "종합 신호" 라벨(MA/RSI-only `buySignalStrength`)과 바로 옆 `assessment`/값(MFI+볼린저 포함 score)이 **다른 계산** → "강력 매수" 위 "매도 신호" 가능 | `TechnicalIndicatorService:152-158,380-392` vs `StockAnalysisService:701-737`; `FundamentalDiagnosisPanel.vue:254-256` | ③ |
| 4 | **P2** | 2 | `isBothBuying`/`isBothSelling`(**primitive boolean**) → JSON `bothBuying`/`bothSelling` → "외국인+기관 동반 매수/매도!" 배너 **영영 안 뜸** | `StockDiagnosisDto.SupplyDemandDto:97-98`; `FundamentalDiagnosisPanel.vue:143-144`·`EarningsScreenerPage.vue:578-579` | ② |
| 5 | **P2** | 2 | `disparity20` — 진단 DTO에 **없는 필드** → QuickSummaryBar "20일선" 타일 영구 "-"/공란 | `StockDiagnosisDto.TechnicalAnalysisDto`(부재); `QuickSummaryBar.vue:89-99` | ② |
| 6 | **P2** | 3·2 | 실적 스크리너 **RSI 과열 강등이 죽은 가드** — `isRsiOverbought`가 최상위 `data.rsiStatus`(중첩에만 존재) 조회 → 항상 false → RSI 과열에도 적극매수 표시 | `EarningsScreenerPage.vue:1596-1632`(cf. `StockDetailDashboard.vue:1221` 주석 "최상위 X") | ②③ |
| 7 | **P2** | 1 | 스크리너(마법공식/PEG/턴어라운드) 결측 지표를 `PER 0.0/PBR 0.0/ROE 0.0/PEG 0.00`으로 주입 → LLM이 초저평가로 오독 | `GeminiService:139-143,184-187,229-231` → `QuantScreenerController` → `EarningsScreenerPage` | ① |
| 8 | **P2** | 1 | `NewsService` 반환값 요약 — 강한 "본문에 없는 정보 금지" **systemPrompt를 만들지만 chat()에 전달 안 함**(死코드) | `NewsService.java:377-391` | ④(가드 무력) |
| 9 | **P2** | 3 | VolumePowerGauge — 뱃지/숫자색은 `signal`, 숫자/설명/바는 `volumePower` → 두 값 괴리 시 "강한 매수세"+빨강 위 "균형" 설명 | `VolumePowerGauge.vue:5,22,33-37,128-189` | ③ |
| 10 | **P2** | 3 | GlobalFuturesPage — 영향 뱃지 **텍스트=`alertLevel`, 색=`impact`** → 초록 "소폭 약세" 가능 | `GlobalFuturesPage.vue:464-485` | ③ |
| 11 | **P2** | 3 | StockBriefingHeadline — "외인·기관 매수 **동반**"을 **OR**로 판정(기관 순매도여도 동반 주장). `isSupplyNegative`는 올바른 AND | `StockBriefingHeadline.vue:50-53,96-97` | ③(미스노머) |
| 12 | **P2** | 3(부수) | MarketTimingPage — `getConditionClass/Emoji`가 문자열 enum switch인데 **객체**를 받음 → 항상 default → 상태색 없음·아이콘 "❓" | `MarketTimingPage.vue:892-912,25,141,224` | (별개 버그) |
| 13 | **P2** | 1 | AI TOP PICK(`analyzeStockRecommendation`) 프롬프트 **기준일 미주입** — 텔레그램 알림 | `GeminiService:264-279`; `AiStockAnalysisService.buildStockDataSummary:694` | ④ |
| 14 | **P2** | 1 | 하드코딩 peer 밸류에이션 테이블(PBR/PER/ROE) 사용자 표시(Gemini 아님) | `StockDetailService.getPeerDataBySector:2189-2219` | ① |
| 15 | **P3⚠landmine** | 1 | **Random "AI 4대장" 앙상블** — `new Random(...)`가 GPT/Claude/Gemini/Deepseek 점수 발명(§4c 원형과 동일 패턴). **단 현재 라이브 소비자 0** → 미노출 | `AiStockAnalysisService.generateEnsembleInfo:847-877` | ① |
| 16 | **P3** | 2 | `isCurrent`(primitive) → `current` → peer 현재종목 행 하이라이트 영영 안 됨 | `StockDetailDto.PeerComparison:151`; `PeerComparisonCard.vue:13` | ② |
| 17 | **P3** | 2 | `isDangerous`(primitive) → `dangerous` → 잠재(현재 fail-open, 표시 결함 없음) | `RiskAnalysisDto:91`; `StockRiskCard.vue:134` | ② |
| 18 | **P3** | 3 | `volumeSignal` 결측 → "NEUTRAL"(§4c 위장) — 게이지 `isPreMarket` 게이트가 표면화 방지 중 | `ScalpingAnalysisDto:50-51`; `StockDetailService:1351,1509` | ①③ |

(P3 기타: StockDetailDashboard 중장기 박스 관망 뱃지에 caution 태그 없음 `:58-61`; AiStrategyDashboardPage 진단 뱃지 텍스트=`verdict`·색=`score` 미정합 `:161-164`; **테스트 픽스처가 틀린 키를 미러링**해 버그를 가림 — `FundamentalDiagnosisPanel.test.js:19`·`PeerComparisonCard.test.js:6-8`.)

---

## 원형별(테마) 정리 — "네 원형의 형제 지도"

### 테마 A — §4c 프롬프트에 결측/장전 0·가짜값 raw 주입 (원형 ①: `generatePositiveNews`)
- **#1 forecast** 장전 0 breadth + 2700 하드코딩(P1, 라이브·표시됨) · **#7 screener** 결측→0.0(P2) · **#14 peer** 하드코딩 테이블(P2) · **#15 Random 앙상블**(P3, 死) · **#18 volumeSignal→NEUTRAL**(P3).
- **판정**: StockDetail 카드는 이미 정직화(장전 미거래·5일 누적·기준일)됐으나 **forecast/screener는 같은 함정을 그대로 안고 있음**. #1이 가장 시급(사용자가 예측 카드로 봄).

### 테마 B — 판정 라벨 ↔ 본문/근거 데이터 소스 분리 (원형 ③: "수급 강세" vs 본문 관망)
- **#2 recommendation 뱃지**(P1 — 지난 수정이 작은 칩만 고쳐, 모순이 더 큰 뱃지로 이동) · **#3 overallSignal vs assessment**(P1) · **#9 VolumePowerGauge** · **#10 GlobalFutures** · **#11 briefing OR** · **#8 AiStrategy 진단 뱃지**.
- **판정**: **#2가 핵심** — 지난 수정(`resolveTechnicalSignal`)이 `technicalSignal` 칩만 본문과 정합시켰고, **주 판정 `recommendation`은 여전히 키워드+점수 파생**이라 본문과 갈릴 수 있음. 같은 `bodyVerdict` 정합 로직을 `recommendation`에도 태워야 완결.

### 테마 C — 직렬화 키 ↔ 프론트 접근 키 불일치 → 조용한 undefined (원형 ②: `instNet5Days`)
- **⭐ 체계적 근본원인(firsthand 확인)**: MVC 직렬화기가 **커스텀 `new ObjectMapper()` 빈**(`WebClientConfig.java:47-51`)이라 `spring.jackson.*` props 미적용 + 기본 bean 네이밍. → **primitive `boolean isX` 필드는 Jackson이 `is`를 벗김**(`isBothBuying()` → JSON `bothBuying`). **`Boolean`(래퍼) `isX`는 유지**(`getIsArrangedUp()` → `isArrangedUp`) — 그래서 기술지표 플래그는 정상인데 수급 플래그는 깨지는 대조가 설명됨.
- 영향 필드(primitive boolean): `isBothBuying`/`isBothSelling`(#4, 배너 안 뜸) · `isForeignBuying`/`isInstitutionBuying`(같은 family, 프론트가 `is`로 읽으면 동일 깨짐) · `isCurrent`(#16) · `isDangerous`(#17 잠재).
- 별종 키 미스: **#5 `disparity20`**(진단 DTO에 없는 필드) · **#6 `data.rsiStatus`**(중첩에만 존재하는데 최상위로 읽음 → 죽은 RSI 가드).
- **판정**: 이 클래스는 **전수 재발 위험**. `writeValueAsString` 직렬화 테스트로 각 DTO 키를 고정하고, primitive `boolean isX` → `Boolean` 래퍼 or `@JsonProperty("isX")`로 통일 권장. **테스트 픽스처가 틀린 키를 미러링**해 회귀를 못 잡던 것도 동반 수정 대상.

### 테마 D — 프롬프트 기준일 미주입 → 날짜 환각 (원형 ④)
- **#1 forecast**(5거래일 예측, 기준일 없음) · **#13 analyzeStockRecommendation**(TOP PICK 알림) · **#8 NewsService systemPrompt 死**(가드 무력화).
- StockDetail은 `:2306`에서 기준일 주입 완료 — **forecast/TOP PICK은 미적용**.

---

## 정합 확인 (OK, proven — 형제 아님)
- **축1 클린**: `StockCatalystService`(엄격 JSON 파싱·§4c 정직·가짜 없음) · `TradingDiaryService`(실집계·기간 주입·정직 폴백) · `PositionDropMonitorService`("추정" 라벨·실뉴스) · `scoreStockCandidates`(결측 "N/A"·aiScore 랭킹 미사용).
- **축2 클린**: `StockConclusionDto/TradePlan/EntryPosition`·`JudgmentBoardDto`(전 키 일치, 불리언 `is` 안 붙음)·`StockDetailDto`(price/financial/supplyDemand/aiAnalysis 키 일치 — 여기 `instNetBuy`는 정상, 오타는 **진단 DTO의 `institutionNet5Days`였음**)·`TechnicalAnalysisDto` 래퍼 불리언(정상)·`StockCatalystDto`.
- **축3 클린**: `MacroTiltService`(단일 compute·drivers=투표축)·`StockConclusionService`(`verdictFor` 자기 점수·수급 헤드라인 ≥15 게이트)·`JudgmentBoardService`(`supplyInverseSuspect` from supplyDemand)·`MarketTimingService`(condition=ADR + 하락전용 크래시 오버라이드가 진단문도 재작성=의도)·`RiskInfo`(status/score/reason 단일)·`AiStockAnalysisService.opinion`(totalScore)·프론트 `StockConclusionCard`·`SectionJudgmentBoard`(정직 "—"·표본부족 표기).

## 원형별 형제 대비 (StockDetail 카드는 고쳐졌나?)
| 원형 | StockDetail(수정됨) | 남은 형제 |
|---|---|---|
| ① 가짜/0 raw | ✅ 장전 미거래·5일누적·가짜뉴스 삭제 | #1 forecast · #7 screener · #14 peer · #15 앙상블 · #18 volumeSignal |
| ② 키 undefined | ✅ (instNet5Days 자체 수정 완료) | #4·#5·#6·#16·#17 (특히 boolean is-strip 클래스) |
| ③ 라벨↔본문 | ⚠ **칩만** 정합(recommendation 미완) | #2(핵심)·#3·#9·#10·#11 |
| ④ 기준일 | ✅ StockDetail 주입 | #1·#13 · #8(가드 死) |

---

## 권장 수정 그룹 (합의용 — 별도 세션)
1. **P1 묶음 먼저**: #1 forecast(장전 "미집계" 표기 + 기준일 주입 + 2700 하드코딩 제거) · #2 `recommendation`을 `bodyVerdict` 정합 태우기(#2는 지난 fix의 완결편) · #3 `overallSignal`/`buySignalStrength` 소스 통일 or 라벨 근거 명시.
2. **테마 C 체계 수정**: primitive `boolean isX` 직렬화 클래스 일괄 — `@JsonProperty` 명시 or 래퍼 전환 + **DTO별 `writeValueAsString` 키 회귀 테스트** + 틀린 픽스처 교정 (#4·#5·#6·#16·#17).
3. **P2 나머지**: #7 screener 결측→"미수집" · #8 systemPrompt 실배선 or 제거 · #9·#10·#11 라벨 소스 정합 · #12 MarketTiming switch 대상 교정 · #13 기준일 · #14 peer 실데이터/제거.
4. **P3/landmine**: #15 Random 앙상블 **삭제**(死코드·재배선 시 §4c 폭탄) · #18 volumeSignal null 정직화.

## 한계
- 축1은 GeminiService 소비자 전수 + 스크리너/뉴스/브리핑 경유 확인. 축2는 주요 4 DTO + 소비 컴포넌트 전수(boolean is-strip은 다른 DTO에도 잠재 — 전수 스캔은 직렬화 테스트로 대체 권장). 축3은 판정성 라벨 위주(정보성 숫자 색상 등 미세 UI는 spot-check).
- **심각도는 현재 노출 기준** — #15는 패턴상 P0급이나 라이브 소비자 0이라 P3(landmine). 재배선 시 즉시 P0.
