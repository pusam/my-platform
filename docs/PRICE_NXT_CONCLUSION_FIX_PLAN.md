# 가격/NXT/결론카드 3대 버그 — 수정 실행 계획

> 조사 완료(2026-06-01). 사용자 결정 확정. 코드 수정 착수 직전 단계.
> 세 이슈 모두 "데이터 소스가 화면/모듈마다 제각각이고 단일 진실원이 없다"는 같은 뿌리.

---

## 합의된 결정 (사용자 승인)

- **① 가격 일관성**: 상세도 공용 경로(`StockPriceService.getStockPrice()`, 0·부호 보정 포함)로 통일 + 목록은 스냅샷 기준 시각 명시.
- **② NXT 시간대**: 표시/추천까지만 08:00~20:00 반영. 자동매매 봇은 09:00~15:30 유지(안전).
- **③ 결론 카드 vs 라벨**: 소스는 유지하되 단기/중장기/결론 **역할을 라벨로 명확화**해 혼동 제거.

---

## ① 가격 일관성

### 원인 (확정)
- 목록: `RecommendationService.refreshPrices()` → `StockPriceService.getStockPricesFromCacheOnly()` — 메모리(5분)+DB만, **KIS 호출 안 함**(stale 가능).
- 상세: `StockDetailService.getStockDetail/Quick/Heavy` 가 `kisService.getStockPrice()` **직접 호출** + `parsePriceInfo()` 파싱 — 별도 경로.
- 688,000(+1.78%) vs 676,000(-1.74%)는 **부호 버그 아님**: 각각 내부 정합(688=전일676 대비 +1.78%, 676=전일688 대비 -1.74%). → **서로 다른 날짜/시점 스냅샷**을 본 것. = 소스 불일치.
- 배수오류(삼성전자 317,000 / SK하이닉스 2,333,000): 두 파서 모두 **액면분할/배수 보정 없음**. 모의서버 데이터 또는 stale DB 의심.

### 수정 (StockDetailService.java)
- `getStockDetail`(L90), `getStockDetailQuick`(L312-313/340-342), `getStockDetailHeavy`(L406-410) 의 1차 가격 소스를
  `kisService.getStockPrice()` → **`stockPriceService.getStockPrice(stockCode)`** 로 변경(공용 priceCache/DB 공유 → 목록과 수렴).
- `StockPriceDto` → `PriceInfo` 변환 헬퍼 신설(`convertDtoToPriceInfo`, 기존 `convertNaverToPriceInfo` L1240대 패턴 재사용). 종목명도 dto에서.
- KIS 직접 호출은 폴백/보조로만. `parsePriceInfo`는 유지(다른 호출부 영향 최소).

### 배수오류 (삼성전자/SK하이닉스 ×10) — 조사 결론 (2026-06-01)
- **KIS = 실전 서버 확정**: `.env`에 `KIS_BASE_URL` 없음 + 환경변수 비어 있음 → `application.yml`
  기본값 `openapi.koreainvestment.com:9443`(실전) 사용. 모의서버 아님.
- **앱 코드엔 ×10 로직 없음** (조사 3건 + 직접 확인 일치):
  - `getBigDecimalValue`/`parsePrice`(L1037, L1000): 콤마만 제거 후 `BigDecimal` 변환, 곱셈 없음.
  - `entityToDto`/`dtoToEntity`(L1062, L1079): 단순 복사.
  - `StockPrice.currentPrice` `DECIMAL(15,2)`: 233300 → 233300.00 (×10 아님).
  - 종목코드↔종목명 매핑 정상.
  → 함부로 `÷10` 보정하면 **정상 종목을 훼손**하므로 금지.
- **남은 가설**: (a) KIS 응답 자체가 그 값을 반환, (b) 통합시세(`UN`)에서 엉뚱한 종목/필드 매핑.
  실제 KIS raw 응답을 봐야 확정 가능.
- **적용한 조치 — 진단 가드(`warnIfPriceOutlier`, 가격 미변경)**:
  현재가가 당일 [저가~고가] 범위를 ±10% 벗어나면 `ERROR` 로그 + KIS raw(stck_prpr/hgpr/lwpr/sdpr) 출력.
  현재가만 ×10이면 즉시 탐지, 전 필드 ×10이면 raw 로그로 KIS 응답 문제 확정 가능.
- **다음 단계(운영 로그 확보 후)**: `[가격이상]` 로그로 원인 확정 → 필요 시 특정 종목/필드 보정 또는 KIS 문의.

```sql
-- 참고: DB 실측 (원인 확정 시)
SELECT stock_code,current_price,change_rate,fetched_at,data_source
FROM stock_price WHERE stock_code IN ('006400','005930','000660')
ORDER BY fetched_at DESC LIMIT 5;
```

---

## ② NXT 시간대 (표시/추천만)

### 원인 (확정)
- 시간 정의 파편화:
  - **09:00~15:30 고정**: `MarketTimingService.java:696`(isDuringMarketHours), `MarketCalendarService.java:23-24`(09:00~15:40), `AutoTradingBotService.java:3054-3055`, 섹터 cron `0 */3 9-15`.
  - **프론트 09:00 고정**: `VolumePowerGauge.vue:68/73/78`(540/930분), L28 "09:00 장 시작 후 체결강도가 표시됩니다", L132 "장 시작 대기"; `SectorTradingPage.vue`(정규장 09:00 대기), `EarningsScreenerPage.vue:1586`(hours<9).
  - **이미 08:00~20:00 인식**: `RecommendationService.isTradingHours():1847-1851`, `InvestorTradeService:44-45`, `MarketCacheWarmerService:49-50`. KIS 호출은 `FID_COND_MRKT_DIV_CODE=UN`(KRX+NXT 통합).
- `+0.00%`: 장전엔 `prdy_ctrt`가 0으로 옴(`AiStrategySnapshotService.java:292` 주석) + 스냅샷/캐시 비어 `null`/`0`→프론트가 `+0.00%`로 포맷.
- `NXT`/`ATS`/`대체거래소` 거래 로직 분기 0건(주석만 존재).

### 수정
- **프론트 `VolumePowerGauge.vue`**: 08:00~08:50=프리, 09:00~15:30=정규, 15:30~20:00=애프터로 3구간화. "09:00 장 시작 후…" 문구를 NXT 인지 문구로. 데이터 있으면 표시.
- **`SectorTradingPage.vue` / `EarningsScreenerPage.vue`**: 08:00~ 프리마켓 표시(데이터 있을 때 "장 외 대기" 대신 NXT 표기).
- **백엔드(추천)**: `RecommendationService.isTradingHours()`(이미 08:00~20:05)는 OK. 등락률 0/ null → 표시단에서 "-" 처리(또는 일봉 폴백 적용 일관화).
- **자동매매 봇 / `MarketCalendarService.isRegularSession` 은 손대지 않음** (사용자 결정: 봇 09:00~15:30 유지).
- (선택) 공용 `MarketSession` 유틸 도입해 PRE/REGULAR/AFTER/CLOSED 단일화 — 범위 크면 후속.

---

## ③ 결론 카드 vs 종합점수 라벨

### 원인 (확정)
- 한 상세 화면에 점수 소스 3종:
  1. 좌측 "단기 트레이딩" = `aiAnalysis.overallScore` + `getRecommendationLabel()` (StockDetailDashboard.vue:49-50)
  2. 좌측 "중장기 펀더멘털" = `diagnosisData.overallScore`(재무30+수급35+기술35) + `getAdjustedVerdict()` — **RSI 과열이면 STRONG_BUY/BUY→"관망" 강등**(L2145-2151)
  3. 결론 카드 = `StockConclusionService.getConclusion()` → **`RecommendationSnapshot`(마지막 DB 스냅샷)** `totalScore` 룰체인(StockConclusionService.java:47-103)
- 결론카드가 "관망"(WAIT) = 결론카드가 본 `totalScore` < 75. 화면의 76점과 **다른 값**(시점·산식 다름). 76은 live 진단점수, 결론카드는 stale 5카테고리 스냅샷.

### 수정 (역할 라벨 명확화 — 소스 유지)
- **`StockConclusionCard.vue`**: 카드 상단에 "종합추천 스냅샷 기준 (`dataAt` 시각)" 명시 + 헤더 점수와 다른 질문임을 한 줄로. `dataAvailable=false`/오래된 경우 그대로 노출.
- **`StockDetailDashboard.vue`**: 듀얼 점수 박스("단기 트레이딩"/"중장기 펀더멘털")는 이미 라벨 있음 — 결론카드와의 관계 설명 툴팁/캡션 추가.
- (선택, 별도 합의 필요) 결론카드에도 RSI 과열 주의 일관 표기.

---

## 작업 추적 (TaskList)
1. 가격 일관성: 목록·상세 단일 가격 소스 + 등락률 부호/0 보정 통일
2. NXT 시간대(08:00~20:00) 장중 판정 통일 (표시/추천만)
3. 결론 카드 vs 종합점수 라벨 점수 소스 일원화 (역할 라벨)

## 착수 순서 (권장)
③ 프론트(저위험) → ① 가격 공용경로 → ② NXT 표시. 각 단계 후 백엔드 `./gradlew compileJava` 또는 프론트 빌드로 검증.
