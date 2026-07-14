# 가이드: 종목상세 차트를 실제 HTS 차트로 교체 (lightweight-charts)

> **이 문서는 다음 세션(다른 모델 포함)이 그대로 읽고 작업하도록 쓴 자족적 구현 가이드다.**
> 작성: 2026-07-15. 대상 독자는 이 저장소를 처음 보는 구현자 — CLAUDE.md 를 먼저 읽을 것.
> 사용자 요구: "차트를 실제 HTS 차트처럼" — 십자선+OHLCV 툴팁, 가격/시간 축 눈금, 마우스 줌/팬을
> 갖춘 차트. 현재 차트는 DIV+SVG 수제 렌더링이라 이것들이 없다.

---

## 0. 결론 (기술 선택)

**TradingView `lightweight-charts` (npm, Apache-2.0)** 로 **렌더링 계층만** 교체한다.

- 캔들/거래량/십자선/축 눈금/줌·팬/가격선이 전부 내장. 캔버스 기반 ~50KB gzip. 외부 CDN 불필요(번들 포함 — nginx CSP 무관).
- 대안 비교: chart.js+financial 플러그인(십자선·팬 UX 약함, 이미 chart.js 4 가 있으나 후순위), 수제 SVG 확장(십자선/줌을 직접 구현 — 공수 대비 손해). **lightweight-charts 확정.**
- ⚠ 라이선스: Apache-2.0 이지만 TradingView 가 **어트리뷰션(제품 내 TradingView 링크 고지)** 을 요구한다.
  차트 하단에 작은 링크 한 줄 추가로 충족 (§5 Phase 3 참조).
- ⚠ 버전: `npm i lightweight-charts` 후 **설치된 메이저 버전을 확인**하고 아래 매핑표의 v4/v5 컬럼 중 맞는 쪽을 쓸 것.
  v5 는 `chart.addSeries(CandlestickSeries, opts)` / v4 는 `chart.addCandlestickSeries(opts)` 로 API 가 다르다.

## 1. 절대 지킬 것 (이 저장소의 불변식 — CLAUDE.md 발췌 + 차트 고유)

1. **계산-렌더 분리**: `frontend/src/utils/trendChannel.js`(회귀 채널·이탈), `frontend/src/utils/candleAnatomy.js`(꼬리),
   `useChartCalculations.js` 의 **데이터 계산부는 수정 금지** — 렌더러만 바꾼다. 이 순수 함수들은 백엔드
   (`TrendChannelCalculator`, V49 스냅샷)와 산식이 동기라 임의 변경 시 화면↔보드↔AI↔검증데이터가 어긋난다.
2. **신호색 한국 관례**: 상승=빨강(`#ef4444`/`var(--stock-up)`), 하락=파랑(`#3b82f6`/`var(--stock-down)`).
   lightweight-charts 기본값(상승=초록)을 **반드시 오버라이드**. 채널 방향색도 동일(UP 적/DOWN 청/FLAT 회 `#9ca3af`).
3. **§4c 데이터 정직**: 없는 데이터를 그리지 않는다 — 분봉 없으면 "당일 분봉 없음" 안내 유지, 채널 미성립(<10봉)이면
   채널 안 그림, MA 가 없는 분봉 모드에서 MA 흉내 금지.
4. **표시 전용 원칙**: 차트의 어떤 요소(채널/꼬리/패턴)도 점수·시그널·봇 산식에 새로 편입하지 않는다.
5. **동작 보존**: 데이터 fetch 경로(`chartData`/`intradayData`), 10초 자동갱신, 기간 전환(1/7/30/60),
   전체화면(⛶/Esc), 캡션(채널 해설·이탈 배지·꼬리 배지)은 **기능 그대로** — 렌더링만 교체.
6. **테스트 먼저/유지**: 기존 vitest 207+ green 유지. `useChartCalculations.test.js` 의 SVG 좌표 테스트는
   해당 함수가 실제로 은퇴하는 Phase 3 에서만 함께 정리(그 전엔 건드리지 않음).

## 2. 현재 구조 지도 (2026-07-15, `a301b3f` 기준)

### 렌더링 (교체 대상)
`frontend/src/views/StockDetailDashboard.vue`
- `:146` `<div class="chart-section" :class="{ fullscreen: chartFullscreen }">` — 차트 섹션 루트
- `:147~190` 차트 토글 바 — 기간(1/7/30/60일)·MA/볼린저(분봉 모드 숨김)·S/R·패턴·채널·⛶크게 버튼 → **유지**(재배선만)
- `:191~194` `.intraday-note` 분봉 로딩/없음 안내 → **유지**
- `:195~272` `.candlestick-container` — **DIV 캔들 + SVG 오버레이(MA/BB polyline, S/R 가로선, 패턴 마커, 채널 3선) +
  HTML 가격 라벨(S/R `.sr-line-labels`, 채널 `.channel-line-labels`)** ← 이 블록이 교체 대상 본체
- `:273~281` `.volume-chart` DIV 막대 ← 교체 대상(차트 내 볼륨 시리즈로)
- `:283~296` 채널 캡션 + 꼬리 배지 → **유지**(차트 아래 텍스트)
- CSS `:1701~1830` 부근 — `.candlestick-*`/`.volume-*`/`.sr-line-*`/`.channel-line-*`/dense/fullscreen 스타일

### 데이터/계산 (유지 — 렌더러의 입력)
- `chartData` (일봉): `StockDetailService.getChartData` 가 최대 60봉. `candles[{date:'yyyy-MM-dd',open,high,low,close}]`
  **최신→과거 순**, `volumes[{volume}]`, `maLine5/20/60/120`, `bbUpper/bbLower` (각 캔들과 같은 길이·순서).
- `intradayData` (1일=당일 5분봉): `GET /api/stock/{code}/intraday-candles` → `{candles[{date:'HH:mm',...}], volumes, dataAvailable}`
  최신→과거 순. 백엔드 `IntradayChartService`(페이지네이션+5분 합성, 2분 캐시).
- `useChartCalculations(effectiveChartData, supportResistance, chartPatterns, chartDisplayCount)`:
  - **유지(데이터 계층)**: `displayCandles`(slice+reverse → **과거→최신**), `displayVolumes`, `chartChannel`
    (채널 가격: `upperStart/upperEnd/lowerStart/lowerEnd/midStart/midEnd` + 방향/위치), `chartBreakout`.
  - **은퇴 후보(SVG 좌표 계층)**: `chartPriceRange`, `chartSrLines`(y%), `chartPatternMarkers`(x,y%), `maLinePath`,
    `getCandleStyle/getWickStyle/getBodyStyle/getVolumeHeight` — lightweight-charts 가 좌표를 직접 계산하므로 불필요해진다.
- 대시보드 computed: `channelCaption`/`breakoutText`/`channelColor`/`channelPriceLabels`/`tailSignal`,
  상태: `chartPeriod`/`chartPeriodOptions`(+클램프 watch)/`isIntraday`/`intradayData`/`showSrLines`/`showPatternMarkers`/
  `showChannel`/`activeIndicators`/`chartFullscreen`(Esc 는 `onSearchKeydown`).
- `supportResistance` (지지/저항 가격 레벨), `chartPatterns` (keyPoints date+price).

## 3. 목표 상태 (HTS 기능 체크리스트)

- [x → 내장] 십자선(crosshair) + 호버 시 OHLC/거래량 범례(legend)
- [x → 내장] 가격 축(우측) 눈금 + 시간 축(하단) 눈금
- [x → 내장] 마우스 휠 줌 / 드래그 팬 (kinetic scroll)
- [x → 내장] 마지막 가격선 + 가격 라벨
- [이식] 캔들(한국 관례색) / 거래량(캔들 색 연동)
- [이식] MA5/20/60/120·볼린저 라인 (토글 연동, 일봉만)
- [이식] S/R 수평선 + 가격 라벨 (토글 연동)
- [이식] 추세 채널 3선(상단/하단 실선, 중심 점선) + 방향색
- [이식] 패턴 마커 (토글 연동, 일봉만)
- [유지] 기간 1/7/30/60 전환, 분봉 안내, 전체화면, 캡션/배지(채널·이탈·꼬리)

## 4. 기능 → lightweight-charts API 매핑

| 현재 구현 | v5 API | v4 API | 비고 |
|---|---|---|---|
| DIV 캔들 | `chart.addSeries(CandlestickSeries, opts)` | `chart.addCandlestickSeries(opts)` | `upColor:'#ef4444', downColor:'#3b82f6', borderVisible:false, wickUpColor/wickDownColor` 동일색 — **한국 관례 필수** |
| 거래량 DIV 막대 | `HistogramSeries` | `addHistogramSeries` | v5: `paneIndex:1` 별도 페인 권장 / v4: `priceScaleId:''`+`scaleMargins:{top:0.8,bottom:0}` 오버레이. 막대색 = 해당 봉 상승/하락색(알파 0.5) |
| MA/BB polyline | `LineSeries` ×6 | `addLineSeries` | 색: MA5 `#f59e0b`/MA20 `#3b82f6`/MA60 `#10b981`/MA120 `#a855f7`/BB `#6b7280` 점선(`lineStyle:2`). null 값은 데이터에서 **생략**(whitespace) — §4c |
| S/R 가로선+라벨 | `series.createPriceLine({price, color, lineStyle, title})` | 동일 | 저항 `#ef4444`/지지 `#3b82f6`, HIGH 는 실선·굵게, LOW 는 점선. title 에 가격 — 기존 `.sr-line-labels` 대체 |
| 채널 사선 3개 | **2점 LineSeries** ×3 | 동일 | 첫 봉 time→`upperStart`, 마지막 봉 time→`upperEnd` 두 점만 넣으면 직선. `priceLineVisible:false, lastValueVisible:false, crosshairMarkerVisible:false`. 중심선 `lineStyle:2`(점선), 색=`channelColor` |
| 채널 상/하단 가격 라벨 | 상단/하단 LineSeries 에 `lastValueVisible:true` 또는 `createPriceLine` | 동일 | 기존 `.channel-line-labels` 대체 |
| 패턴 마커 | `createSeriesMarkers(series, markers)` | `series.setMarkers(markers)` | `{time, position:'aboveBar'/'belowBar', shape:'circle', color}` — BULLISH 적/BEARISH 청 |
| 십자선 OHLC 범례 | `chart.subscribeCrosshairMove(cb)` | 동일 | cb 의 `param.seriesData.get(candleSeries)` 로 OHLC 추출 → 차트 좌상단 절대배치 DIV 에 표시 |
| 전체화면 | 기존 CSS `.fullscreen` 유지 + `chart.applyOptions({autoSize:true})` 또는 `ResizeObserver`→`chart.resize()` | 동일 | `autoSize:true` 면 컨테이너 크기만 CSS 로 바꾸면 됨 |
| dense(봉폭) | 불필요 — 줌이 대체 | | `.dense` CSS/판정 제거는 Phase 3 |
| 다크 테마 | `layout:{background:{color:'transparent'}, textColor:'rgba(255,255,255,0.6)'}, grid:{vertLines/horzLines:{color:'rgba(255,255,255,0.06)'}}` | 동일 | 앱 배경(#14142a 계열)과 동화 |

### time 값 규약 (함정 — §6-1 참조)
- **일봉**: `'yyyy-MM-dd'` 문자열 그대로 `time` 으로 사용 가능.
- **분봉('1일')**: `'HH:mm'` 은 불가 — **UTCTimestamp(epoch 초)** 로 변환 필요.
  `Math.floor(new Date(\`${todayYmd}T${hhmm}:00+09:00\`).getTime()/1000)` (KST 고정).
  시간축 표시 형식은 `timeScale:{timeVisible:true, secondsVisible:false}` + `localization:{timeFormatter}` 로 HH:mm.

## 5. 작업 단계 (권장 순서 — 각 Phase 끝에 `npm test` + `npm run build` green 확인)

### Phase 1 — `HtsChart.vue` 컴포넌트 신설 (기존 차트는 아직 그대로)
1. `cd frontend && npm i lightweight-charts` → 설치된 메이저 버전 확인.
2. `frontend/src/components/v2/HtsChart.vue` 생성. **props 로 데이터만 받는 표시 전용 컴포넌트**:
   ```
   props: candles(과거→최신 [{time, open, high, low, close}]), volumes([{time, value, up}]),
          maLines({ma5:[{time,value}],...} | null), bollinger({upper, lower} | null),
          srLevels([{price, type, strength}]), channel(chartChannel 그대로 + firstTime/lastTime),
          markers([{time, position, color, shape}]), intraday(Boolean), fullscreen(Boolean)
   ```
3. `onMounted` 에서 `createChart(el, {autoSize:true, layout/grid/crosshair/timeScale 옵션})` →
   캔들·볼륨 시리즈 생성. `watch(props.candles)` 에서 `setData`(전체 교체 — 10초 갱신 빈도에 충분,
   `series.update()` 최적화는 불필요한 복잡도).
4. 오버레이 시리즈(MA/BB/채널/S/R/마커)는 **"지우고 다시 그리기"** 패턴: props 변경 시 기존 priceLine/시리즈
   remove 후 재생성 (개수 적어 성능 문제 없음). 토글 OFF = 해당 props 에 null/[] 전달.
5. 십자선 범례 DIV(시가/고가/저가/종가/거래량 + 등락률, 상승적/하락청) — `subscribeCrosshairMove`.
6. `onUnmounted` 에서 `chart.remove()` (메모리 누수 방지 — SPA 라 필수).

### Phase 2 — StockDetailDashboard 배선
1. `:195~281` (candlestick-container + volume-chart) 블록을 `<HtsChart ...>` 로 교체.
   토글 바(:147~190)·intraday-note·캡션/배지(:283~296)는 그대로 두고 props 만 연결:
   - `candles` = `displayCandles`(이미 과거→최신) → time 변환(일봉 date 그대로/분봉 epoch) 후 전달.
     변환 헬퍼는 **순수 함수로 분리**(`utils/htsChartData.js` — vitest 대상): `toSeriesData(displayCandles, isIntraday, todayYmd)`.
   - `maLines` = `activeIndicators` 반영해 on 인 것만, `chartData.maLine5...` 를 time 정렬로 변환(분봉이면 null).
   - `srLevels` = `showSrLines ? supportResistance 레벨 : []` — **가격 범위 필터 불필요**(차트가 알아서 스케일).
   - `channel` = `showChannel ? chartChannel : null` + `firstTime/lastTime` = 표시 캔들 첫/마지막 time.
   - `markers` = `showPatternMarkers && !isIntraday ? chartPatterns keyPoints 변환 : []`.
2. 전체화면: 기존 `.fullscreen` CSS 에서 `.candlestick-container` 높이 규칙을 HtsChart 래퍼 높이로 치환.
3. 분봉 빈 상태: `candles.length === 0` 이면 HtsChart 대신 기존 intraday-note 만 (현행 로직 유지).

### Phase 3 — 정리 (동작 확인 후 같은 PR 마지막 커밋)
1. 구 렌더링 잔재 제거: `.candle/.wick/.body/.volume-bar/.chart-overlay/.sr-line-*/.channel-line-*` CSS,
   `useChartCalculations` 의 SVG 좌표 함수들(§2 은퇴 후보) + 해당 테스트 — **데이터 계층은 남긴다**
   (`displayCandles/displayVolumes/chartChannel/chartBreakout` 는 계속 사용).
   `channelPriceLabels` computed(대시보드)도 HtsChart 가격라벨로 대체되면 제거.
2. dense 관련 클래스/CSS 제거(줌이 대체).
3. **어트리뷰션**: 차트 아래 `<a href="https://www.tradingview.com" ...>Charts by TradingView</a>` 소형 링크(라이선스 요구).
4. `CLAUDE.md` 코드 위치 힌트의 차트 항목 갱신 + 이 가이드 상단에 "✅ 완료(커밋)" 표기.

## 6. 함정 (미리 알아야 시간 아끼는 것들)

1. **데이터 순서**: `chartData.candles`/`intradayData.candles` 는 **최신→과거**, `displayCandles` 는 reverse 되어
   **과거→최신**. lightweight-charts `setData` 는 **오름차순 필수 + time 중복 금지** — `displayCandles` 를 쓰면 안전.
   중복 time 이 있으면 조용히 깨지므로 변환 헬퍼에서 dedupe(마지막 값 우선) 방어.
2. **분봉 time**: §4 표 참조 — 'HH:mm' 그대로 넣으면 예외/오표시. KST 고정 epoch 변환.
3. **jsdom 테스트**: lightweight-charts 는 캔버스 필요 — vitest(jsdom)에서 실제 렌더 불가.
   **HtsChart.vue 는 마운트 테스트하지 말고**, ① 변환 순수 함수(`toSeriesData` 등)를 유닛 테스트,
   ② 컴포넌트는 `vi.mock('lightweight-charts')` 로 API 호출(색상/데이터 인자) 검증만. `vitest.setup.js` 에
   전역 mock 을 넣지 말 것(다른 테스트 오염) — 해당 테스트 파일 안에서만 mock.
4. **휴장일 갭**: 일봉 time 이 날짜라 주말이 자동으로 건너뛰어 그려진다(HTS 와 동일). 억지로 채우지 말 것(§4c).
5. **MA null 구간**: maLine60/120 은 앞쪽(과거)이 null — null 인 지점은 데이터에서 **빼고** 넣는다(넣으면 예외).
   기존 `maLinePath` 도 같은 정책이었다.
6. **60봉 한계**: 줌아웃해도 최대 60일치뿐. 원하면 **후속(선택)**: `StockDetailService.getChartData` 의
   `displayCount = Math.min(60, ...)` (:720 부근) 을 늘리기만 하면 됨 — 단 응답 크기·maLine 계산 길이 확인.
   이번 마이그레이션 범위 아님(가이드 독자는 착수 전 사용자에게 물어볼 것).
7. **v4/v5 혼용 금지**: 설치 버전 확인 후 한쪽 API 만. v5 에서 `addCandlestickSeries` 는 없다(런타임 에러).
8. **autoRefresh(10초)**: `chartData` 교체 → `displayCandles` 재계산 → watch 로 `setData` — 줌/스크롤 위치가
   리셋될 수 있다. `chart.timeScale().scrollPosition()` 저장 후 복원하거나, v5 `setData` 는 기본적으로
   가시 범위를 유지하는지 실동작 확인. 거슬리면 "마지막 N봉 고정 뷰"(`timeScale().setVisibleLogicalRange`) 적용.
9. **기간 전환**: 1일↔일봉 전환 시 time 스케일 종류가 바뀐다(날짜↔epoch). 같은 시리즈에 섞어 넣지 말고
   전환 시 `setData` 전체 교체 + `chart.timeScale().fitContent()` 호출.

## 7. 검증 체크리스트 (구현자 완료 조건)

- [ ] `cd frontend && npm test` 전부 green (기존 207+ 유지, 신규 변환 헬퍼 테스트 추가)
- [ ] `npm run build` 통과
- [ ] 수동 확인(브라우저): ① 캔들 상승=빨강/하락=파랑 ② 십자선 호버 시 OHLCV 범례 ③ 휠 줌·드래그 팬
  ④ S/R 토글 ⑤ 채널 토글(사선 3개 + 상/하단 가격) ⑥ 패턴 토글 ⑦ MA/볼린저 토글 ⑧ 1일(분봉) 전환 —
  HH:mm 축, MA 토글 숨김 ⑨ 7/30/60 전환 ⑩ ⛶ 전체화면 + Esc ⑪ 10초 자동갱신 시 줌 유지
  ⑫ 장전에 1일 → "당일 분봉 없음" 안내 ⑬ 채널 캡션·이탈 배지·꼬리 배지 그대로
- [ ] 어트리뷰션 링크 존재
- [ ] 커밋은 Phase 단위 분리, 마지막에 push

## 8. 롤백 전략

렌더러 교체는 한 컴포넌트 안이므로, 문제가 생기면 `HtsChart` 사용 블록을 이전 커밋의 DIV/SVG 블록으로
되돌리는 단일 revert 로 복구된다. Phase 3(구 코드 삭제)를 **별도 커밋**으로 두는 이유가 이것 —
운영 확인 전까지는 Phase 1~2 커밋만 배포하고 Phase 3 은 하루 뒤에 해도 된다.
