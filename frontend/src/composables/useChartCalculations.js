import { computed } from 'vue';
import { computeTrendChannel, detectChannelBreakout } from '../utils/trendChannel';

/**
 * 종목 상세 차트(캔들/거래량/MA·볼린저 오버레이/지지저항·패턴 마커)의 좌표·스타일 계산.
 * StockDetailDashboard 에서 분리 (P-IA ③-3차, 동작 변화 0). 입력 ref 3개를 받아 표시용 computed·헬퍼 반환.
 *
 * 토글 상태(activeIndicators/showSrLines/showPatternMarkers)는 UI state 라 부모가 그대로 보유 —
 * 여기선 데이터 → 좌표/스타일 순수 계산만 담당한다.
 *
 * @param {import('vue').Ref} chartData         - { candles, volumes, maLine5/20/60/120, bbUpper, bbLower }
 * @param {import('vue').Ref} supportResistance  - { resistance: [], support: [] } | null
 * @param {import('vue').Ref} chartPatterns      - [{ keyPoints, signal, label }]
 * @param {import('vue').Ref<number>} [displayCount] - 표시 봉 수 (기본 30, 백엔드 최대 60)
 */
export function useChartCalculations(chartData, supportResistance, chartPatterns, displayCount) {
  const barCount = () => displayCount?.value || 30;

  // 차트 표시용 (최근 barCount개)
  const displayCandles = computed(() => {
    if (!chartData.value?.candles) return [];
    return chartData.value.candles.slice(0, barCount()).reverse();
  });

  const displayVolumes = computed(() => {
    if (!chartData.value?.volumes) return [];
    return chartData.value.volumes.slice(0, barCount()).reverse();
  });

  // 차트 가격 범위 (상하 2% 여백)
  const chartPriceRange = computed(() => {
    if (!displayCandles.value.length) return { min: 0, max: 100 };
    const prices = displayCandles.value.flatMap(c => [c.high, c.low]);
    return {
      min: Math.min(...prices) * 0.98,
      max: Math.max(...prices) * 1.02
    };
  });

  const maxVolume = computed(() => {
    if (!displayVolumes.value.length) return 1;
    return Math.max(...displayVolumes.value.map(v => v.volume));
  });

  // 차트 위 지지/저항 가로선 — 차트 가격 범위 안에 있는 레벨만 표시
  const chartSrLines = computed(() => {
    if (!supportResistance.value || !displayCandles.value.length) return [];
    const range = chartPriceRange.value;
    const span = range.max - range.min;
    if (span <= 0) return [];
    const lines = [];
    const toY = (price) => ((range.max - Number(price)) / span) * 100;

    for (const lv of (supportResistance.value.resistance || [])) {
      const price = Number(lv.price);
      if (price < range.min || price > range.max) continue;
      lines.push({ y: toY(price), price, type: 'resistance', strength: lv.strength });
    }
    for (const lv of (supportResistance.value.support || [])) {
      const price = Number(lv.price);
      if (price < range.min || price > range.max) continue;
      lines.push({ y: toY(price), price, type: 'support', strength: lv.strength });
    }
    return lines;
  });

  // 차트 위 패턴 마커 — keyPoints 일자가 표시 30일 안에 있는 것만
  const chartPatternMarkers = computed(() => {
    if (!chartPatterns.value.length || !displayCandles.value.length) return [];
    // displayCandles 의 date 기준 인덱스 매핑 (yyyy-MM-dd 또는 epoch 가능, 둘 다 처리)
    const dateToIndex = new Map();
    displayCandles.value.forEach((c, i) => {
      const d = c.date || c.tradeDate;
      if (d) dateToIndex.set(String(d).substring(0, 10), i);
    });
    const range = chartPriceRange.value;
    const span = range.max - range.min;
    if (span <= 0) return [];
    const count = displayCandles.value.length;

    const markers = [];
    for (const p of chartPatterns.value) {
      if (!p.keyPoints) continue;
      for (const kp of p.keyPoints) {
        const idx = dateToIndex.get(String(kp.date).substring(0, 10));
        if (idx === undefined) continue;
        const price = Number(kp.price);
        if (price < range.min || price > range.max) continue;
        markers.push({
          x: ((idx + 0.5) / count) * 100,
          y: ((range.max - price) / span) * 100,
          signal: p.signal,
          label: p.label,
          role: kp.role
        });
      }
    }
    return markers;
  });

  // 추세 채널(회귀 채널) — 표시 30봉 기준 계산 + SVG 좌표 매핑. 데이터 부족/퇴화 시 null(§4c).
  // 표시 전용 보조선 — 점수/시그널 산식 미편입(차트기법 스코어러 분리 불변식).
  const chartChannel = computed(() => {
    const channel = computeTrendChannel(displayCandles.value);
    if (!channel) return null;
    const range = chartPriceRange.value;
    const span = range.max - range.min;
    if (span <= 0) return null;
    const count = displayCandles.value.length;
    const toY = (price) => ((range.max - price) / span) * 100;
    const x1 = (0.5 / count) * 100;            // 첫 봉 중심
    const x2 = ((count - 0.5) / count) * 100;  // 마지막 봉 중심
    return {
      ...channel,
      x1,
      x2,
      upperY1: toY(channel.upperStart),
      upperY2: toY(channel.upperEnd),
      lowerY1: toY(channel.lowerStart),
      lowerY2: toY(channel.lowerEnd),
      midY1: toY(channel.midStart),
      midY2: toY(channel.midEnd)
    };
  });

  // 채널 이탈(직전 채널 대비 마지막 종가 돌파/붕괴) — 표시 전용 관찰 배지.
  const chartBreakout = computed(() => detectChannelBreakout(displayCandles.value));

  // 이동평균선/볼린저밴드 SVG polyline points
  const maLinePath = (lineKey) => {
    const data = chartData.value?.[lineKey];
    if (!data || !displayCandles.value.length) return null;
    // data는 최신→과거 순 (캔들과 동일), 표시는 reverse
    const lineData = data.slice(0, barCount()).reverse();
    const range = chartPriceRange.value;
    const count = displayCandles.value.length;
    if (range.max === range.min) return null;

    const points = [];
    for (let i = 0; i < Math.min(lineData.length, count); i++) {
      if (lineData[i] == null) continue;
      const x = ((i + 0.5) / count) * 100;
      const y = 100 - ((lineData[i] - range.min) / (range.max - range.min)) * 100;
      points.push(`${x},${y}`);
    }
    return points.length >= 2 ? points.join(' ') : null;
  };

  const getWickStyle = (candle) => {
    const range = chartPriceRange.value;
    const height = ((candle.high - candle.low) / (range.max - range.min)) * 100;
    const bottom = ((candle.low - range.min) / (range.max - range.min)) * 100;
    return { height: height + '%', bottom: bottom + '%' };
  };

  const getBodyStyle = (candle) => {
    const range = chartPriceRange.value;
    const bodyTop = Math.max(candle.open, candle.close);
    const bodyBottom = Math.min(candle.open, candle.close);
    const height = ((bodyTop - bodyBottom) / (range.max - range.min)) * 100;
    const bottom = ((bodyBottom - range.min) / (range.max - range.min)) * 100;
    return { height: Math.max(height, 1) + '%', bottom: bottom + '%' };
  };

  // .candle 래퍼와 .body 가 같은 박스(시가~종가 몸통)를 쓴다 — 동일 계산이라 별도 구현 대신 alias.
  const getCandleStyle = getBodyStyle;

  const getVolumeHeight = (volume) => {
    return (volume / maxVolume.value) * 100;
  };

  return {
    displayCandles,
    displayVolumes,
    chartPriceRange,
    maxVolume,
    chartSrLines,
    chartPatternMarkers,
    chartChannel,
    chartBreakout,
    maLinePath,
    getCandleStyle,
    getWickStyle,
    getBodyStyle,
    getVolumeHeight
  };
}
