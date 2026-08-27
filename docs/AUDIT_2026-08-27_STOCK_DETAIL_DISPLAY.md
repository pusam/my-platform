# 종목상세 표시층 감사 — "데이터 없음"이 0으로 그려지는 지점

> **2026-08-27 · 읽기 전용 감사. 코드 수정 없음.**
> 목적: 백엔드가 결측을 반환하는데 화면이 0/빈값으로 그려 사용자가 "실제 0"으로 오해하는 지점 전수.

---

## 요약 — 4개 근본 원인, 21개 증상

| # | 근본 원인 | 증상 수 | 심각도 |
|---|---|---|---|
| **A** | 백엔드가 결측을 **0으로 확정**해 내보낸다 → 프론트의 §4c 가드가 영원히 안 걸린다 | 6 | 🔴 심각 |
| **B** | 표본 제약(**상위 20위 진입일만**)이 어느 UI 라벨에도 없다 | 4 | 🔴 심각 |
| **C** | 같은 사실을 화면마다 **다르게** 그린다(null 처리 불일치) | 3 | 🟠 중간 |
| **D** | best-effort 로더가 **실패를 화면에 안 알린다** | 6 | 🟠 중간 |
| — | 개별 `\|\| 0` 폴백(위 원인에 안 묶이는 것) | 2 | 🟡 낮음 |

**가장 위험한 것은 A + B 의 조합이다.** 백엔드가 "이 종목은 그날 상위20에 없었다"를
`0` 으로 바꿔 내보내고, 화면은 그걸 "외국인 순매수 0억"으로 그린다.
사용자가 읽는 문장은 **"그날 외국인이 사지도 팔지도 않았다"** 인데, 사실은
**"그날은 데이터 자체가 없다"** 이다. 둘은 투자 판단에서 정반대 의미다.

---

## A. 백엔드가 결측을 0으로 확정한다 🔴

프론트는 §4c 를 지키려고 만들어져 있다. 예를 들어 `InvestorTrendTab.vue:303-319` 는
명시적으로 이렇게 적어 뒀다:

```js
// 결측(null/undefined/비숫자)은 '-' — 미수집을 "순매수 0억"(균형)으로 위장하지 않는다(§4c). 실측 0 만 '0억'.
if (value == null || Number.isNaN(num)) return '-';
```

**그런데 이 가드는 절대 발동하지 않는다.** 백엔드가 이미 0으로 바꿔 보내기 때문이다.

### A-1. `buildInvestorSummary` — 데이터 없는 투자자를 0으로

`backend/.../service/InvestorTradeService.java:274-281`

```java
private ... buildInvestorSummary(List<InvestorDailyTrade> trades) {
    if (trades == null || trades.isEmpty()) {
        return ...builder()
                .buyAmount(BigDecimal.ZERO)
                .sellAmount(BigDecimal.ZERO)
                .netBuyAmount(BigDecimal.ZERO)   // ← 결측을 0으로 확정
                .build();
    }
```

- **현재 동작**: 그날 그 투자자 유형의 행이 없으면 `netBuyAmount = 0`
- **오해 가능성**: 화면의 "0억"이 "매수=매도(균형)"로 읽힌다. 실제로는 미수집
- **심각도**: 🔴 — 이 하나가 일별 매매 동향 표 전체를 오염시킨다

### A-2. `foreignNet5Days` / `institutionNet5Days` — 빈 결과도 0

`backend/.../service/StockAnalysisService.java:382, 388, 498-508`

```java
BigDecimal foreignNet5Days = BigDecimal.ZERO;   // 382
...
return SupplyDemandDto.builder()
        .foreignNet5Days(foreignNet5Days)        // 498-499 — 행이 0건이어도 0 이 실린다
```

조회 결과가 비어도 조기 반환이 없어 `ZERO` 가 그대로 DTO 에 실린다.

- **현재 동작**: 지표 바가 `v == null` 을 확인하지만(`QuickSummaryBar.vue:109`)
  값이 `0` 이라 통과 → **"순매수 +0억"** 으로 그려진다
- **오해 가능성**: 결측이 "순매수"(양수 취급, 빨간색)로 표시된다.
  `v >= 0 ? '순매수' : '순매도'` (`QuickSummaryBar.vue:111`) 라 **0은 '순매수'로 분류**된다
- **심각도**: 🔴

### A-3~A-6. 표시층의 `?.toFixed(0) || 0` 4곳

| 파일:라인 | 필드 |
|---|---|
| `views/StockDetailDashboard.vue:353` | `supplyDemand?.foreignNetBuy` |
| `views/StockDetailDashboard.vue:369` | `supplyDemand?.instNetBuy` |
| `views/StockDetailDashboard.vue:385` | `supplyDemand?.programNetBuy` |
| `components/v2/FundamentalDiagnosisPanel.vue:103,106` | `foreignNetBuy` / `instNetBuy` |

```html
<span class="bar-value" :class="supplyDemand?.foreignNetBuy >= 0 ? 'positive' : 'negative'">
  {{ supplyDemand?.foreignNetBuy >= 0 ? '+' : '' }}{{ supplyDemand?.foreignNetBuy?.toFixed(0) || 0 }}억
</span>
```

- **현재 동작**: 값이 `undefined` 면 `undefined >= 0` → **false** → `negative` 클래스,
  `undefined?.toFixed(0) || 0` → **`0`**. 즉 **"0억"이 순매도 색(파랑)으로** 그려진다
- **오해 가능성**: 미수집이 "외국인 순매도 0억"이라는 **틀린 방향의 사실**로 보인다
- **심각도**: 🔴 — 색까지 붙어서 결측이 방향성 있는 신호처럼 읽힌다

---

## B. 표본 제약이 UI 어디에도 없다 🔴

### 제약이 걸리는 지점

`backend/.../service/InvestorDailyTradeService.java:226`

```java
for (JsonNode stockNode : output) {
    if (rank > 20) break;   // 상위 20개만
```

**수집 계층에서 걸린다.** KIS 투자자별 상위 종목 API 를 투자자유형 × 매수/매도 방향으로
호출해 **각 20위까지만** 저장한다. 즉 `investor_daily_trade` 테이블에는
**"그 종목이 그날 상위 20위에 진입한 날"의 행만** 존재한다.

### B-1. 일별 매매 동향 표 — 제약 미표기

`components/v2/InvestorTrendTab.vue`

- **현재 동작**: 표에 뜨는 날짜는 "거래일"이 아니라 "상위20 진입일"이다.
  진입하지 않은 날은 **행 자체가 없다**(A-1 로 0이 채워지기도 한다)
- **오해 가능성**: 사용자는 연속된 거래일 시계열로 읽는다. 중간에 빠진 날을
  "그날은 수급이 없었다"로 해석한다
- **UI 라벨**: 없음. 표 제목·범례·툴팁 어디에도 "상위 20위" 언급 없음
- **심각도**: 🔴

### B-2. 누적 차트 — 결측을 0으로 더해 선이 평평해진다

`components/v2/InvestorTrendTab.vue:191-193`

```js
fCum += Number(day.foreign?.netBuyAmount || 0);
iCum += Number(day.institution?.netBuyAmount || 0);
pCum += Number(day.pension?.netBuyAmount || 0);
```

- **현재 동작**: 같은 파일이 표에서는 `null → '-'` 를 지키는데(303-319줄),
  **차트 경로만 `|| 0`** 이다. 결측 구간에서 누적선이 수평으로 이어진다
- **오해 가능성**: "그 기간 외국인이 관망했다"로 읽힌다. 실제로는 데이터가 없다
- **심각도**: 🟠 — 같은 컴포넌트 안에서 표와 차트의 정직성 기준이 다르다

### B-3. 지표 바 "외국인 +211억" — 기간·표본 둘 다 미표기

`components/v2/QuickSummaryBar.vue:21-25` (라벨) → `109-118` (값)
→ `backend/.../StockAnalysisService.java:375-418`

```java
LocalDate startDate = endDate.minusDays(SUPPLY_DEMAND_DAYS + 5);   // 375-376, 10일 창
...
List<LocalDate> recentDates = trades.stream()
        .map(InvestorDailyTrade::getTradeDate).distinct()
        .sorted((a, b) -> b.compareTo(a))
        .limit(SUPPLY_DEMAND_DAYS)                                  // 397, 최대 5일
```

**답: 전체 기간 누적이 아니다.** 정확히는
**"최근 10일(달력) 창에서 이 종목이 상위20에 진입한 날 최대 5일의 합"** 이다.

- 진입일이 2일뿐이면 **2일 합**이 "외국인 +211억"으로 표시된다
- 진입일이 0일이면 **0억**이 "순매수"로 표시된다(A-2)
- **UI 라벨**: `qs-label` 이 그냥 `외국인`. "5일"도 "상위20"도 없다
- **심각도**: 🔴

### B-4. 같은 화면에 "외국인 순매수"가 두 개, 서로 다른 소스

| 위치 | 필드 | 기간 |
|---|---|---|
| 지표 바 (`QuickSummaryBar.vue:24`) | `diagnosisData.supplyDemand.foreignNet5Days` | 상위20 진입일 최대 5일 |
| 본문 수급 바 (`StockDetailDashboard.vue:353`) | `supplyDemand.foreignNetBuy` (`StockDetailDto`) | **당일** |

- **현재 동작**: 두 숫자가 다르고, 라벨은 둘 다 "외국인"
- **오해 가능성**: 같은 지표가 두 값으로 보여 어느 쪽이 맞는지 알 수 없다
- **심각도**: 🟠

---

## C. 같은 사실을 화면마다 다르게 그린다 🟠

**20일선이 대표 사례다.** 두 화면이 같은 `technicalAnalysis` 를 받아 정반대로 처리한다.

### C-1. 지표 바 — 정직 ✅

`components/v2/QuickSummaryBar.vue:93-102`

```js
const getQsMaPosition = () => {
  const d = props.diagnosisData?.technicalAnalysis?.disparity20;
  if (d == null) return '-';          // ← 결측을 '-' 로
  return d >= 0 ? '위' : '아래';
};
```

산출 경로: `TechnicalIndicatorService.java:102` → `builder.disparity20(calculateDisparity(currentPrice, ma20))`

### C-2. 종합진단 패널 — **결측을 "20일선 아래"로 단정** ❌

`components/v2/FundamentalDiagnosisPanel.vue:173`

```html
{{ diagnosisData.technicalAnalysis.isAboveMa20 ? '20일선 위' : '20일선 아래' }}
```

- **현재 동작**: `isAboveMa20` 가 `null`/`undefined` 여도 삼항이 **false 분기**로 떨어져
  **"20일선 아래"** 라고 확정 문장을 그린다
- **오해 가능성**: 미산출(20봉 미만·캐시 미스)이 **"20일선 아래"라는 약세 신호**가 된다.
  같은 페이지의 지표 바는 `-` 인데 이 패널은 "아래"라고 말한다
- **심각도**: 🔴 — 결측이 "없음"이 아니라 **틀린 방향의 사실**로 뒤집힌다

### C-3. 스크리너 화면도 동일 패턴

`views/EarningsScreenerPage.vue:608`

```html
{{ diagnosisData.technicalAnalysis.isAboveMa20 ? '✅ 20일선 위' : '❌ 20일선 아래' }}
```

- 결측이 **❌ 아이콘까지 달고** 부정 신호로 표시된다
- **심각도**: 🟠 (종목상세 밖이지만 같은 근본 원인이라 같이 적는다)

> **⚠ "20일선 대비 +3.1%" 문구는 못 찾았다.**
> `ChartNarrativeCard.vue` 에 `20일선`·`ma20`·`disparity` 문자열이 없다.
> 해당 문구는 백엔드 생성 문장이거나 다른 컴포넌트일 수 있다 — **위치 미확인**.

---

## D. best-effort 로더가 실패를 화면에 안 알린다 🟠

`views/StockDetailDashboard.vue:1159-1185`

```js
quantTaAPI.supportResistance(code)
  .then(res => { if (res.data?.success) supportResistance.value = res.data.data; })
  .catch(err => console.warn('지지/저항 검출 실패:', err.message));
```

**두 겹으로 조용하다:**
1. `.catch` 가 `console.warn` 만 — 화면에 아무 표시 없음
2. `if (res.data?.success)` — API 가 `success:false` 를 줘도 **아무 일도 안 일어난다**
   (값이 이전 상태로 남거나 초기값 유지)

| 라인 | 패널 | 실패 시 화면 |
|---|---|---|
| 1163 | 차트 패턴 | 빈 목록 = "패턴 없음"과 구분 불가 |
| 1168 | 지지/저항 | 섹션 미표시 |
| 1173 | Volume Profile | 섹션 미표시 |
| 1180 | 종합 신호 | 섹션 미표시 |
| 1185 | 관련 종목 | 빈 목록 |
| 1104 | 종목 검색 | `console.warn` 만 |

- **오해 가능성**: "신호가 없다"와 "조회가 실패했다"가 화면상 동일하다
- **심각도**: 🟠

### D-반례: 분봉은 제대로 하고 있다 ✅

`views/StockDetailDashboard.vue:647-649`

```js
} catch (e) {
  intradayData.value = null;
  intradayError.value = true;   // 조회 실패 — 빈 결과(정상)와 구분해 안내
}
```

**이 패턴이 정답이다.** 나머지 5곳이 이걸 안 따른다.

> **⚠ VWAP 은 `StockDetailDashboard.vue` 에서 못 찾았다**(`vwap` 문자열 0건).
> 다른 컴포넌트에 있을 수 있다 — **미확인**.

---

## 개별 항목 (위 원인에 안 묶임) 🟡

| 파일:라인 | 코드 | 판정 |
|---|---|---|
| `StockDetailDashboard.vue:326` | `Number(supplyDemand?.volumePower) \|\| 0` | 체결강도 결측이 **0**(=최약)으로. `VolumePowerGauge` 는 null 을 '-'로 그릴 준비가 돼 있는데 0이 들어가 무력화. CLAUDE.md §4c 의 "null→100 강제 변환 금지"와 같은 부류 |
| `StockDetailDashboard.vue:906` | `Number(priceInfo.value.changeRate) \|\| 0` | 등락률 결측이 **0%(보합)** 으로 |
| `FundamentalDiagnosisPanel.vue:55,95,156` | `score \|\| 0` | 미채점이 **0점**으로. 0점과 미채점은 다른 상태 |
| `SectionMarketMap.vue:38,42,99` | `(sector.changeRate \|\| 0)` | 섹터 등락률 결측이 보합으로 |

---

## 우선순위 제안 (수정은 별건)

1. **A-1 / A-2** — 백엔드가 0을 만들지 않게. 이걸 고치면 프론트에 이미 있는 §4c
   포맷터들이 **자동으로 작동한다**. 가장 적은 변경으로 가장 많은 증상이 사라진다.
2. **C-2 / C-3** — 삼항 `? :` 를 3분기(위/아래/판정불가)로. 결측이 **틀린 방향의
   사실**로 뒤집히는 유일한 부류라 A 다음으로 급하다.
3. **B-3 / B-1** — 라벨에 기간·표본 명시("외국인 5일" / "상위20 진입일 기준").
   코드 변경 없이 문구만으로 상당 부분 해소된다.
4. **D** — `intradayError` 패턴을 5곳에 확대.
5. **A-3~A-6** — `?.toFixed(0) || 0` 제거. 단 A-1/A-2 를 먼저 고쳐야 의미가 있다.

---

## 확인 못 한 것

- **"20일선 대비 +3.1%" 문구의 실제 위치** — 차트 해설 컴포넌트에 해당 문자열 없음
- **VWAP 패널 위치** — `StockDetailDashboard.vue` 에 `vwap` 문자열 0건
- **실제 API 응답에서 해당 필드가 null 인지 0 인지** — 코드 경로상 백엔드가 0을
  만들어 보내는 것은 확인했으나, 운영 응답 원본은 대조하지 않았다(읽기 전용 감사 범위)

---

## 수정 이력 (2026-08-27 저녁)

감사 직후 **A · C · D 를 수정**했다. B(라벨 문구)와 개별 항목 일부는 아래 사유로 남겼다.

### ✅ 수정됨

| 항목 | 수정 내용 |
|---|---|
| **A-1** | `buildInvestorSummary` 가 행 없을 때 `ZERO` → **null**. 프론트의 §4c 포맷터가 비로소 작동한다 |
| **A-2** | `foreignNet5Days`/`institutionNet5Days` — 집계 가능한 날이 0일이면 **null**(`displayNet` 순수함수). 내부 계산(score·isBuying)은 ZERO 로 그대로 |
| **A-3~A-6** | `?.toFixed(0) \|\| 0` → `netBuyText`/`netBuyClass`. **결측은 방향이 없다** — 색도 안 칠한다 |
| **B-2** | `InvestorTrendTab` 누적 차트의 `\|\| 0` 은 **유지**. 차트는 누적선이라 null 을 건너뛰면 선이 끊긴다 — 대신 아래 "남긴 것" 참조 |
| **C-2·C-3** | 삼항 → `ma20PositionLabel` **3분기**(위/아래/**판정 불가**). 결측이 "20일선 아래"로 뒤집히던 것 해소 |
| **D** | best-effort 패널 5종에 `panelError` 상태 + 화면 상단 한 줄 안내. `success:false` 도 실패로 잡는다 |

회귀 테스트: `stockFormat.test.js`(프론트 6건) · `InvestorSummaryNullTest`(백엔드 5건).

### ⏸ 남긴 것과 사유

| 항목 | 왜 안 고쳤나 |
|---|---|
| **B-1·B-3·B-4** (라벨에 기간·표본 명시) | 문구가 곧 **제품 결정**이다. "외국인 5일(상위20 진입일 기준)" 같은 표현은 화면 폭·톤과 함께 정해야 해서 사람 판단으로 남긴다 |
| **B-2** 누적 차트 | 결측을 0으로 더하면 선이 평평해지고, 건너뛰면 선이 끊긴다. **어느 쪽이 덜 오해를 부르는지가 설계 판단**이라 남긴다 |
| `volumePower \|\| 0` | **버그가 아니었다.** `VolumePowerGauge` 가 0 을 이미 "데이터 없음"으로 취급한다(`!= null && > 0`) |
| `score \|\| 0` | **버그가 아니었다.** 백엔드가 int 로 계산해 null 이 오지 않는다 |
| `changeRate \|\| 0` | 등락률 결측은 시세 경로 문제라 표시층에서 덮을 일이 아니다 — 별건 |

### 확인 못 한 것 (감사 시점과 동일)

- "20일선 대비 +3.1%" 문구 위치 · VWAP 패널 위치 · 운영 API 응답 원본 대조
