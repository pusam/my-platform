/**
 * 캔들 꼬리(그림자) 해부 — 마지막 봉의 긴 아래/위 꼬리 관찰 배지. 순수 함수(테스트 대상).
 *
 * 차트쟁이 관례 기준:
 *  - 망치형(긴 아래꼬리): 장중 하방 매도를 소화하고 종가가 위쪽 마감 → 지지 시도 관찰.
 *  - 유성형(긴 위꼬리): 상방 시도가 매물에 밀려 종가가 아래쪽 마감 → 상단 저항/차익 매물 관찰.
 *  판정: 꼬리 ≥ 몸통×2 AND 꼬리 ≥ 전체 범위 50% AND 반대쪽 꼬리 ≤ 범위 15%.
 *
 * ⚠ 표시 전용 관찰 — 점수/시그널 산식 미편입(차트기법 분리 불변식). 꼬리 하나로 방향을 예단하지
 * 않는 문구만 사용(P2-12 교훈 — 차트 모양 신호는 검증 전 매매 신호 아님).
 */

const TAIL_BODY_RATIO = 2;      // 꼬리 ≥ 몸통 × 2
const TAIL_RANGE_RATIO = 0.5;   // 꼬리 ≥ 전체 범위 50%
const OPPOSITE_MAX_RATIO = 0.15; // 반대쪽 꼬리 ≤ 범위 15%

/**
 * @param {{open:number, high:number, low:number, close:number}} candle 마지막 봉
 * @returns {null | { type: 'HAMMER'|'SHOOTING_STAR', label: string }}
 */
export function detectTailSignal(candle) {
  if (!candle) return null;
  const open = Number(candle.open);
  const high = Number(candle.high);
  const low = Number(candle.low);
  const close = Number(candle.close);
  if (![open, high, low, close].every((v) => Number.isFinite(v) && v > 0)) return null;

  const range = high - low;
  if (range <= 0) return null;
  const body = Math.abs(close - open);
  const lowerTail = Math.min(open, close) - low;
  const upperTail = high - Math.max(open, close);

  if (lowerTail >= body * TAIL_BODY_RATIO && lowerTail >= range * TAIL_RANGE_RATIO
      && upperTail <= range * OPPOSITE_MAX_RATIO) {
    return { type: 'HAMMER', label: '🔨 긴 아래꼬리(망치형) — 하방 매도 소화, 지지 시도 관찰' };
  }
  if (upperTail >= body * TAIL_BODY_RATIO && upperTail >= range * TAIL_RANGE_RATIO
      && lowerTail <= range * OPPOSITE_MAX_RATIO) {
    return { type: 'SHOOTING_STAR', label: '💫 긴 위꼬리(유성형) — 상방 매물 확인, 상단 저항 관찰' };
  }
  return null;
}
