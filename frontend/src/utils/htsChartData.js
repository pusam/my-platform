/**
 * lightweight-charts 시리즈 데이터 변환 — 순수 함수(테스트 대상).
 * useChartCalculations 의 데이터 계층(displayCandles 등, 과거→최신)을 lightweight-charts 가 요구하는
 * 시리즈 포맷({time, open, high, low, close} / {time, value})으로 옮긴다. HtsChart.vue 는 렌더만 담당.
 *
 * time 규약(가이드 §4·§6-1):
 *  - 일봉: 'yyyy-MM-dd' 문자열 그대로(BusinessDay).
 *  - 분봉('1일'): 'HH:mm' → UTCTimestamp(epoch 초), KST(+09:00) 고정 변환.
 * setData 는 오름차순 + time 유일 필수라 dedupe(마지막 값 우선) + 정렬로 방어(§6-1).
 * 한국 관례색: 상승=빨강 / 하락=파랑(§1-2).
 */

export const HTS_UP_COLOR = '#ef4444';
export const HTS_DOWN_COLOR = '#3b82f6';
const VOL_ALPHA_HEX = '80'; // ~0.5 — 8자리 hex 알파(#ef444480)

/**
 * 일봉 날짜 정규화 — 'yyyy-MM-dd…' | 'yyyyMMdd' → 'yyyy-mm-dd'. 그 외는 null. 순수.
 *
 * ⚠ lightweight-charts 는 'yyyy-mm-dd' 만 받고 아니면 <b>throw</b> 한다. KIS 일봉
 *   (stck_bsop_date)은 yyyyMMdd 8자라 `substring(0,10)` 으로는 그대로 통과해 버린다 —
 *   그 값이 setData/setMarkers 에 닿는 순간 예외가 나고 차트 렌더가 통째로 중단된다
 *   (2026-09-22 운영 콘솔: "Invalid date string=20260811").
 *   날짜를 만드는 곳이 둘(시리즈·마커)이라 한쪽만 고쳐서는 안 잡힌다 — 그래서 한 곳으로 모았다.
 */
export function toDailyDate(v) {
  if (v == null) return null;
  const s = String(v);
  if (/^\d{8}$/.test(s)) return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
  return s.length >= 10 ? s.substring(0, 10) : null;
}

/** 봉 라벨(date/tradeDate)을 lightweight-charts time 으로 변환. 순수. 실패/결측이면 null. */
export function toSeriesTime(dateLabel, isIntraday, todayYmd) {
  if (dateLabel == null) return null;
  const s = String(dateLabel);
  if (!isIntraday) return toDailyDate(s);
  // 분봉 — 'HH:mm' → KST epoch 초
  const m = s.match(/^(\d{2}):(\d{2})/);
  if (!m || !todayYmd) return null;
  const t = new Date(`${todayYmd}T${m[1]}:${m[2]}:00+09:00`).getTime();
  return Number.isFinite(t) ? Math.floor(t / 1000) : null;
}

/** time(문자열 or 숫자) 오름차순 비교자. 순수. */
function cmpTime(a, b) {
  return a.time < b.time ? -1 : a.time > b.time ? 1 : 0;
}

/**
 * displayCandles(과거→최신) + displayVolumes → 캔들/거래량 시리즈 데이터. 순수.
 * 거래량 색은 해당 봉 상승/하락색(알파). 비정상 봉(NaN)은 건너뜀(§4c). dedupe+정렬.
 */
export function toSeriesData(displayCandles, displayVolumes, isIntraday, todayYmd) {
  const candleMap = new Map();
  const volumeMap = new Map();
  const list = displayCandles || [];
  const vols = displayVolumes || [];
  for (let i = 0; i < list.length; i++) {
    const c = list[i];
    const time = toSeriesTime(c.date ?? c.tradeDate, isIntraday, todayYmd);
    if (time == null) continue;
    const o = Number(c.open), h = Number(c.high), l = Number(c.low), cl = Number(c.close);
    if (![o, h, l, cl].every(Number.isFinite)) continue;
    candleMap.set(time, { time, open: o, high: h, low: l, close: cl });
    const v = Number(vols[i]?.volume);
    if (Number.isFinite(v)) {
      const color = (cl >= o ? HTS_UP_COLOR : HTS_DOWN_COLOR) + VOL_ALPHA_HEX;
      volumeMap.set(time, { time, value: v, color });
    }
  }
  return {
    candles: Array.from(candleMap.values()).sort(cmpTime),
    volumes: Array.from(volumeMap.values()).sort(cmpTime),
  };
}

/**
 * 이동평균/볼린저 원자료(최신→과거, chartData.maLine5 등) → 라인 시리즈 데이터. 순수.
 * displayCandles(과거→최신)에 index 정렬로 맞춰 time 부여, null 지점은 생략(§6-5). dedupe+정렬.
 */
export function toLineData(rawNewestFirst, displayCandles, isIntraday, todayYmd) {
  if (!Array.isArray(rawNewestFirst) || !displayCandles?.length) return [];
  const sliced = rawNewestFirst.slice(0, displayCandles.length).reverse(); // 과거→최신, displayCandles 정렬
  const byTime = new Map();
  for (let i = 0; i < displayCandles.length; i++) {
    const raw = sliced[i];
    if (raw == null) continue;
    const value = Number(raw);
    if (!Number.isFinite(value)) continue;
    const time = toSeriesTime(displayCandles[i].date ?? displayCandles[i].tradeDate, isIntraday, todayYmd);
    if (time == null) continue;
    byTime.set(time, { time, value });
  }
  return Array.from(byTime.values()).sort(cmpTime);
}

/**
 * 추세 채널(가격 필드) → 상단/하단/중심 2점 라인 데이터. 순수.
 * 첫 봉 time→*Start, 마지막 봉 time→*End 두 점만 넣으면 직선(가이드 §4). 채널 없으면 null.
 */
export function toChannelLines(channel, firstTime, lastTime) {
  if (!channel || firstTime == null || lastTime == null || firstTime === lastTime) return null;
  return {
    upper: [{ time: firstTime, value: channel.upperStart }, { time: lastTime, value: channel.upperEnd }],
    lower: [{ time: firstTime, value: channel.lowerStart }, { time: lastTime, value: channel.lowerEnd }],
    mid: [{ time: firstTime, value: channel.midStart }, { time: lastTime, value: channel.midEnd }],
  };
}

/**
 * 차트 패턴 keyPoints → lightweight-charts 마커. 순수. 표시된 봉 날짜에 있는 것만(일봉 전용).
 * BULLISH=빨강 아래·BEARISH=파랑 위, 시간 오름차순(setMarkers 규약).
 */
export function toMarkerData(chartPatterns, displayCandles) {
  if (!chartPatterns?.length || !displayCandles?.length) return [];
  // ⚠ 여기가 두 번째 날짜 생성 지점이다 — substring(0,10) 은 yyyyMMdd 8자를 그대로 통과시켜
  //    time 으로 나가고 setMarkers 가 throw 한다(2026-09-22, 시리즈만 고쳤더니 여기서 재발).
  const dates = new Set(displayCandles.map(c => toDailyDate(c.date ?? c.tradeDate)).filter(Boolean));
  const markers = [];
  for (const p of chartPatterns) {
    if (!p.keyPoints) continue;
    for (const kp of p.keyPoints) {
      const d = toDailyDate(kp.date);
      if (d == null || !dates.has(d)) continue;
      const bull = p.signal === 'BULLISH';
      markers.push({
        time: d,
        position: bull ? 'belowBar' : 'aboveBar',
        color: bull ? HTS_UP_COLOR : (p.signal === 'BEARISH' ? HTS_DOWN_COLOR : '#9ca3af'),
        shape: 'circle',
        text: p.label || '',
      });
    }
  }
  return markers.sort(cmpTime);
}
