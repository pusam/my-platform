<template>
  <!-- lightweight-charts 렌더 컨테이너 + 십자선 OHLCV 범례(좌상단 절대배치). 표시 전용. -->
  <div class="hts-chart" ref="containerRef">
    <div class="hts-legend" v-if="legend">
      <span class="hts-legend-time">{{ legend.time }}</span>
      <span :class="['hts-legend-ohlc', legend.up ? 'up' : 'down']">
        시 {{ legend.open }} · 고 {{ legend.high }} · 저 {{ legend.low }} · 종 {{ legend.close }}
        <span v-if="legend.changePct != null" class="hts-legend-chg">({{ legend.changePct }}%)</span>
      </span>
      <span class="hts-legend-vol" v-if="legend.volume != null">거래량 {{ legend.volume }}</span>
    </div>
    <!-- TradingView 라이선스(Apache-2.0) 어트리뷰션 -->
    <a class="hts-attribution" href="https://www.tradingview.com" target="_blank" rel="noopener noreferrer">
      Charts by TradingView
    </a>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue';
import {
  createChart, CandlestickSeries, HistogramSeries, LineSeries, createSeriesMarkers,
  LineStyle, CrosshairMode, ColorType,
} from 'lightweight-charts';
import { toSeriesData, toLineData, toChannelLines, toMarkerData, HTS_UP_COLOR, HTS_DOWN_COLOR } from '../../utils/htsChartData';

/**
 * props — StockDetailDashboard 가 useChartCalculations 데이터 계층을 그대로 넘긴다(계산 미포함).
 * 렌더러만 담당: 캔들·거래량·MA/BB·S/R·채널·패턴 마커·십자선 범례. 한국 관례색(상승 빨강/하락 파랑).
 */
const props = defineProps({
  displayCandles: { type: Array, default: () => [] },   // 과거→최신 [{date, open, high, low, close}]
  displayVolumes: { type: Array, default: () => [] },   // 과거→최신 [{volume}]
  maSeries: { type: Object, default: () => ({}) },       // { ma5:[raw 최신→과거]|null, ... } — 활성 것만 (분봉이면 {})
  bollinger: { type: Object, default: null },            // { upper:[raw], lower:[raw] } | null
  srLevels: { type: Array, default: () => [] },          // [{price, type:'resistance'|'support', strength}]
  channel: { type: Object, default: null },              // chartChannel(가격 필드) | null
  channelColor: { type: String, default: '#9ca3af' },
  markersInput: { type: Array, default: () => [] },      // chartPatterns [{signal,label,keyPoints}]
  isIntraday: { type: Boolean, default: false },
  todayYmd: { type: String, default: '' },
});

const containerRef = ref(null);
const legend = ref(null);

// lightweight-charts 인스턴스 — 반응형 불필요(watch 로 명령형 갱신). setup 스코프 변수로 보관.
let chart = null;
let candleSeries = null;
let volumeSeries = null;
let markerPrimitive = null;
let overlaySeries = [];     // MA/BB/채널 라인 시리즈 — 재그리기 시 remove 대상
let srPriceLines = [];      // S/R 수평선 — 재그리기 시 remove 대상
let lastCandleData = [];    // 마지막 setData 캔들 — 오버레이만 재그릴 때 재사용
let prevCloseByTime = new Map();   // time → 직전 봉 종가 — 범례 등락률(전봉 종가 대비, HTS 관례)

const MA_META = {
  ma5: { color: '#f59e0b', width: 2 },
  ma20: { color: '#3b82f6', width: 2 },
  ma60: { color: '#10b981', width: 2 },
  ma120: { color: '#a855f7', width: 2 },
};

function initChart() {
  chart = createChart(containerRef.value, {
    autoSize: true,
    layout: {
      background: { type: ColorType.Solid, color: 'transparent' },
      textColor: 'rgba(255,255,255,0.55)',
      fontSize: 11,
    },
    grid: {
      vertLines: { color: 'rgba(255,255,255,0.05)' },
      horzLines: { color: 'rgba(255,255,255,0.05)' },
    },
    crosshair: { mode: CrosshairMode.Normal },
    rightPriceScale: {
      borderColor: 'rgba(255,255,255,0.1)',
      scaleMargins: { top: 0.08, bottom: 0.24 },   // 하단 24% 는 거래량 오버레이 공간
    },
    timeScale: {
      borderColor: 'rgba(255,255,255,0.1)',
      timeVisible: props.isIntraday,
      secondsVisible: false,
      rightOffset: 3,
    },
    localization: {
      priceFormatter: (p) => Math.round(p).toLocaleString(),
    },
  });

  candleSeries = chart.addSeries(CandlestickSeries, {
    upColor: HTS_UP_COLOR, downColor: HTS_DOWN_COLOR,
    wickUpColor: HTS_UP_COLOR, wickDownColor: HTS_DOWN_COLOR,
    borderVisible: false,
  });

  // 거래량 — 별도 hidden 스케일 오버레이(하단 18%)
  volumeSeries = chart.addSeries(HistogramSeries, {
    priceFormat: { type: 'volume' },
    priceScaleId: 'vol',
    lastValueVisible: false,
    priceLineVisible: false,
  });
  chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });

  chart.subscribeCrosshairMove(onCrosshair);
  redrawAll();
}

/** 캔들/거래량 setData + 오버레이 재그리기 — props 변경 시 호출. */
function redrawAll() {
  if (!chart || !candleSeries) return;
  const { candles, volumes } = toSeriesData(
    props.displayCandles, props.displayVolumes, props.isIntraday, props.todayYmd);

  candleSeries.setData(candles);
  volumeSeries.setData(volumes);

  lastCandleData = candles;
  prevCloseByTime = new Map();
  for (let i = 1; i < candles.length; i++) prevCloseByTime.set(candles[i].time, candles[i - 1].close);

  drawOverlays(candles);

  // 전환(기간/종목) 시 전체 뷰 맞춤 — 오버레이 토글은 별도 watch 로 drawOverlays 만 호출해 사용자 줌/팬 보존
  chart.timeScale().fitContent();
}

/** MA/BB/채널/S/R/마커 재그리기 — "지우고 다시" 패턴(개수 적어 성능 무관). */
function drawOverlays(candlesSeriesData) {
  // 기존 오버레이 라인/수평선 제거
  for (const s of overlaySeries) chart.removeSeries(s);
  overlaySeries = [];
  for (const pl of srPriceLines) candleSeries.removePriceLine(pl);
  srPriceLines = [];

  const firstTime = candlesSeriesData.length ? candlesSeriesData[0].time : null;
  const lastTime = candlesSeriesData.length ? candlesSeriesData[candlesSeriesData.length - 1].time : null;

  // 이동평균 (활성 것만 props.maSeries 로 전달됨)
  for (const key of Object.keys(MA_META)) {
    const raw = props.maSeries?.[key];
    if (!raw) continue;
    const data = toLineData(raw, props.displayCandles, props.isIntraday, props.todayYmd);
    if (data.length < 2) continue;
    addLine(data, { color: MA_META[key].color, lineWidth: MA_META[key].width });
  }
  // 볼린저 상/하단(점선 회색)
  if (props.bollinger?.upper && props.bollinger?.lower) {
    for (const band of ['upper', 'lower']) {
      const data = toLineData(props.bollinger[band], props.displayCandles, props.isIntraday, props.todayYmd);
      if (data.length >= 2) addLine(data, { color: '#6b7280', lineWidth: 1, lineStyle: LineStyle.Dashed });
    }
  }

  // 추세 채널 3선(상/하단 실선, 중심 점선) + 상/하단 가격 라벨
  const lines = toChannelLines(props.channel, firstTime, lastTime);
  if (lines) {
    addLine(lines.upper, { color: props.channelColor, lineWidth: 2, lastValueVisible: true, title: '상단' });
    addLine(lines.lower, { color: props.channelColor, lineWidth: 2, lastValueVisible: true, title: '하단' });
    addLine(lines.mid, { color: props.channelColor, lineWidth: 1, lineStyle: LineStyle.Dashed, lastValueVisible: false });
  }

  // 지지/저항 수평선 (캔들 시리즈의 priceLine)
  for (const lv of (props.srLevels || [])) {
    const price = Number(lv.price);
    if (!Number.isFinite(price)) continue;
    const high = lv.strength === 'HIGH';
    srPriceLines.push(candleSeries.createPriceLine({
      price,
      color: lv.type === 'resistance' ? HTS_UP_COLOR : HTS_DOWN_COLOR,
      lineWidth: high ? 2 : 1,
      lineStyle: high ? LineStyle.Solid : LineStyle.Dashed,
      axisLabelVisible: true,
      title: lv.type === 'resistance' ? '저항' : '지지',
    }));
  }

  // 패턴 마커 (일봉 전용 — 부모가 분봉이면 markersInput=[])
  const markers = toMarkerData(props.markersInput, props.displayCandles);
  if (markerPrimitive) {
    markerPrimitive.setMarkers(markers);
  } else if (markers.length) {
    markerPrimitive = createSeriesMarkers(candleSeries, markers);
  }
}

function addLine(data, opts) {
  const s = chart.addSeries(LineSeries, {
    lineWidth: 1.5,
    priceLineVisible: false,
    lastValueVisible: false,
    crosshairMarkerVisible: false,
    ...opts,
  });
  s.setData(data);
  overlaySeries.push(s);
}

/** 십자선 이동 → 좌상단 OHLCV 범례. */
function onCrosshair(param) {
  if (!param.time || !param.seriesData?.get(candleSeries)) {
    legend.value = null;
    return;
  }
  const c = param.seriesData.get(candleSeries);
  const vNode = param.seriesData.get(volumeSeries);
  const up = c.close >= c.open;
  // 등락률 = 직전 봉 종가 대비(HTS 관례) — 첫 봉은 기준 없음 → 미표시
  const prevClose = prevCloseByTime.get(param.time);
  const pct = prevClose > 0 ? ((c.close - prevClose) / prevClose) * 100 : null;
  const changePct = pct == null ? null : (pct > 0 ? '+' : '') + pct.toFixed(2);
  legend.value = {
    time: formatTime(param.time),
    open: Math.round(c.open).toLocaleString(),
    high: Math.round(c.high).toLocaleString(),
    low: Math.round(c.low).toLocaleString(),
    close: Math.round(c.close).toLocaleString(),
    volume: vNode ? Math.round(vNode.value).toLocaleString() : null,
    up, changePct,
  };
}

function formatTime(time) {
  if (typeof time === 'string') return time;   // 일봉 yyyy-MM-dd
  const d = new Date(time * 1000);             // 분봉 epoch → HH:mm (KST)
  return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' });
}

onMounted(() => { nextTick(initChart); });
onUnmounted(() => {
  if (chart) { chart.remove(); chart = null; }   // SPA 메모리 누수 방지(가이드 Phase1-6)
});

// 데이터/토글 변경 시 재그리기. isIntraday 는 time 스케일 종류가 바뀌므로 timeVisible 도 갱신(§6-9).
watch(() => props.isIntraday, (v) => {
  if (chart) chart.applyOptions({ timeScale: { timeVisible: v } });
});
// 데이터(캔들/거래량) 변경 = 기간/종목 전환 → 전체 재그리기 + fitContent.
watch(
  () => [props.displayCandles, props.displayVolumes],
  () => { if (chart) redrawAll(); },
  { deep: true }
);
// 오버레이(MA/BB/S/R/채널/마커) 토글 → 오버레이만 재그리기 — fitContent 미호출로 사용자 줌/팬 보존.
watch(
  () => [props.maSeries, props.bollinger, props.srLevels, props.channel,
         props.channelColor, props.markersInput],
  () => { if (chart && candleSeries) drawOverlays(lastCandleData); },
  { deep: true }
);
</script>

<style scoped>
.hts-chart {
  position: relative;
  width: 100%;
  height: 100%;
}
.hts-legend {
  position: absolute;
  top: 6px; left: 8px;
  z-index: 3;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
  background: rgba(15,15,26,0.6);
  padding: 2px 6px;
  border-radius: 4px;
}
.hts-legend-time { color: rgba(255,255,255,0.75); font-weight: 600; }
.hts-legend-ohlc.up { color: #f87171; }
.hts-legend-ohlc.down { color: #60a5fa; }
.hts-legend-chg { opacity: 0.85; }
.hts-legend-vol { color: rgba(255,255,255,0.5); }
.hts-attribution {
  position: absolute;
  bottom: 2px; right: 6px;
  z-index: 3;
  font-size: 9px;
  color: rgba(255,255,255,0.28);
  text-decoration: none;
  pointer-events: auto;
}
.hts-attribution:hover { color: rgba(255,255,255,0.5); }
</style>
