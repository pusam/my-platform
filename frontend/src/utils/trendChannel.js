/**
 * 추세 채널(회귀 채널) 계산 — 차트쟁이들이 손으로 긋는 평행 채널의 자동화. 순수 함수(테스트 대상).
 *
 * 산출 방식(표준 회귀 채널):
 *  1. 표시 구간 종가에 선형회귀 → 중심선(기울기·절편).
 *  2. 상단선 = 중심선을 "고가가 중심선 위로 가장 많이 이탈한 만큼" 평행 이동(모든 고가가 상단선 이하).
 *  3. 하단선 = 저가 최대 이탈만큼 평행 이동(모든 저가가 하단선 이상).
 *  4. 방향 = 기울기를 평균 종가 대비 %/봉으로 정규화해 ±0.15%/봉 임계로 UP/DOWN/FLAT(박스권).
 *
 * ⚠ 표시 전용 보조 도구 — 점수/시그널/봇 산식에 편입하지 않는다(CLAUDE.md §4 차트기법 스코어러 분리 불변식).
 * 데이터 부족(MIN_BARS 미만)이면 null — 그럴듯한 채널로 위장하지 않는다(§4c).
 */

/** 채널 성립 최소 봉 수 — 이보다 적으면 회귀선이 노이즈라 안 그린다. */
export const MIN_CHANNEL_BARS = 10;

/** 방향 판정 임계 — 기울기 %/봉. ±0.15%/봉 ≈ 30봉 기준 ±4.5% (그 이내면 박스권). */
export const SLOPE_FLAT_THRESHOLD = 0.15;

/**
 * @param {Array<{high:number, low:number, close:number}>} candles - 과거→최신 순(표시 순서)
 * @returns {null | {
 *   direction: 'UP'|'DOWN'|'FLAT',
 *   slopePctPerBar: number,        // 기울기 (%/봉, 평균 종가 대비)
 *   upperStart: number, upperEnd: number,   // 상단선 가격 (첫 봉/마지막 봉)
 *   lowerStart: number, lowerEnd: number,   // 하단선 가격
 *   midStart: number, midEnd: number,       // 중심선 가격
 *   position: number,              // 마지막 종가의 채널 내 위치 0(하단)~1(상단), clamp
 *   widthPct: number               // 채널 폭 (마지막 봉 기준 중심선 대비 %)
 * }}
 */
export function computeTrendChannel(candles) {
  if (!Array.isArray(candles) || candles.length < MIN_CHANNEL_BARS) return null;

  const n = candles.length;
  const closes = candles.map(c => Number(c.close));
  if (closes.some(v => !Number.isFinite(v) || v <= 0)) return null;

  // 종가 선형회귀 (x = 0..n-1)
  const meanX = (n - 1) / 2;
  const meanY = closes.reduce((a, b) => a + b, 0) / n;
  let sxx = 0, sxy = 0;
  for (let i = 0; i < n; i++) {
    sxx += (i - meanX) * (i - meanX);
    sxy += (i - meanX) * (closes[i] - meanY);
  }
  if (sxx === 0) return null;
  const slope = sxy / sxx;
  const intercept = meanY - slope * meanX;
  const regAt = (i) => intercept + slope * i;

  // 평행 이동 오프셋 — 모든 고가/저가를 감싸는 최대 이탈
  let offsetUp = 0, offsetDown = 0;
  for (let i = 0; i < n; i++) {
    const high = Number(candles[i].high);
    const low = Number(candles[i].low);
    if (!Number.isFinite(high) || !Number.isFinite(low)) return null;
    offsetUp = Math.max(offsetUp, high - regAt(i));
    offsetDown = Math.max(offsetDown, regAt(i) - low);
  }

  const midEnd = regAt(n - 1);
  const upperEnd = midEnd + offsetUp;
  const lowerEnd = midEnd - offsetDown;
  const channelSpan = upperEnd - lowerEnd;
  if (channelSpan <= 0 || midEnd <= 0) return null;

  const slopePctPerBar = (slope / meanY) * 100;
  const direction = slopePctPerBar >= SLOPE_FLAT_THRESHOLD ? 'UP'
    : slopePctPerBar <= -SLOPE_FLAT_THRESHOLD ? 'DOWN' : 'FLAT';

  const lastClose = closes[n - 1];
  const position = Math.min(1, Math.max(0, (lastClose - lowerEnd) / channelSpan));

  return {
    direction,
    slopePctPerBar,
    upperStart: regAt(0) + offsetUp,
    upperEnd,
    lowerStart: regAt(0) - offsetDown,
    lowerEnd,
    midStart: regAt(0),
    midEnd,
    position,
    widthPct: (channelSpan / midEnd) * 100
  };
}

/**
 * 채널 이탈(돌파/붕괴) 감지 — <b>마지막 봉을 제외한</b> 구간으로 채널을 그린 뒤, 마지막 종가가
 * 그 채널의 연장선 밖으로 닫혔는지 본다(전체 구간 채널은 정의상 모든 봉을 감싸므로 이탈 감지 불가).
 * 순수 함수(테스트 대상). 표시 전용 관찰 배지 — 산식 미편입.
 *
 * @returns {null | { type: 'UP_BREAKOUT'|'DOWN_BREAKDOWN' }} 채널 내이거나 데이터 부족이면 null
 */
export function detectChannelBreakout(candles) {
  if (!Array.isArray(candles) || candles.length < MIN_CHANNEL_BARS + 1) return null;
  const prior = candles.slice(0, -1);
  const ch = computeTrendChannel(prior);
  if (!ch) return null;
  // 직전 채널을 마지막 봉 위치까지 1봉 연장
  const n = prior.length;
  const upperAtLast = ch.upperEnd + (ch.upperEnd - ch.upperStart) / (n - 1);
  const lowerAtLast = ch.lowerEnd + (ch.lowerEnd - ch.lowerStart) / (n - 1);
  const lastClose = Number(candles[candles.length - 1].close);
  if (!Number.isFinite(lastClose) || lastClose <= 0) return null;
  if (lastClose > upperAtLast) return { type: 'UP_BREAKOUT' };
  if (lastClose < lowerAtLast) return { type: 'DOWN_BREAKDOWN' };
  return null;
}

/** 이탈 배지 라벨 — 순수 함수(테스트 대상). null 이탈은 null. */
export function breakoutLabel(breakout) {
  if (!breakout) return null;
  return breakout.type === 'UP_BREAKOUT'
    ? '⚡ 채널 상단 돌파 — 직전 채널 저항을 종가로 넘음'
    : '⚠️ 채널 하단 이탈 — 직전 채널 지지를 종가로 깸';
}

/**
 * 채널 해설 한 줄 — 차트쟁이 스타일 코멘트(방향 + 위치 + 대응 관점). 순수 함수(테스트 대상).
 * 표시 전용 관찰 문구 — 매수/매도 지시가 아니라 "지금 채널 어디쯤인지"를 읽어준다.
 */
export function channelComment(channel) {
  if (!channel) return null;
  const slope = channel.slopePctPerBar;
  const pos = Math.round(channel.position * 100);
  const width = channel.widthPct.toFixed(1);

  const head = channel.direction === 'UP'
    ? `📈 상승 채널 (+${slope.toFixed(2)}%/일)`
    : channel.direction === 'DOWN'
      ? `📉 하락 채널 (${slope.toFixed(2)}%/일)`
      : `↔️ 박스권 (기울기 ${slope >= 0 ? '+' : ''}${slope.toFixed(2)}%/일)`;

  let posText;
  if (channel.position >= 0.8) {
    posText = channel.direction === 'DOWN'
      ? `채널 상단 ${pos}% — 반등이 채널 저항에 근접, 추세 전환 확인 전 주의`
      : `채널 상단 ${pos}% — 저항권 근접, 추격 매수 주의`;
  } else if (channel.position <= 0.2) {
    posText = channel.direction === 'UP'
      ? `채널 하단 ${pos}% — 추세 지지선 부근, 지지 확인 시 눌림목 관찰`
      : `채널 하단 ${pos}% — 하단 지지 시험 중, 이탈 시 추세 가속 주의`;
  } else {
    posText = `채널 중단 ${pos}% 위치`;
  }

  return `${head} · ${posText} · 폭 ${width}%`;
}
