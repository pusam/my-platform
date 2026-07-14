import { describe, it, expect } from 'vitest'
import { computeTrendChannel, channelComment, detectChannelBreakout, breakoutLabel, MIN_CHANNEL_BARS } from './trendChannel'

/** 등차 상승 캔들 생성 — close = base + step*i, high/low = close ±spread */
function candles(n, { base = 100, step = 0, spread = 1, noise = () => 0 } = {}) {
  return Array.from({ length: n }, (_, i) => {
    const close = base + step * i + noise(i)
    return { close, high: close + spread, low: close - spread, open: close }
  })
}

describe('computeTrendChannel (회귀 채널, 표시 전용)', () => {
  it('명확한 상승 추세 → UP + 모든 고가 ≤ 상단선, 저가 ≥ 하단선', () => {
    const data = candles(30, { base: 100, step: 1, spread: 1.5 })  // +1원/봉 ≈ +0.9%/봉
    const ch = computeTrendChannel(data)
    expect(ch).not.toBeNull()
    expect(ch.direction).toBe('UP')
    expect(ch.slopePctPerBar).toBeGreaterThan(0.15)
    // 평행 채널이 모든 봉을 감싼다 (첫/끝 선형보간으로 검증)
    const n = data.length
    data.forEach((c, i) => {
      const t = i / (n - 1)
      const upperAt = ch.upperStart + (ch.upperEnd - ch.upperStart) * t
      const lowerAt = ch.lowerStart + (ch.lowerEnd - ch.lowerStart) * t
      expect(c.high).toBeLessThanOrEqual(upperAt + 1e-9)
      expect(c.low).toBeGreaterThanOrEqual(lowerAt - 1e-9)
    })
  })

  it('명확한 하락 추세 → DOWN', () => {
    const ch = computeTrendChannel(candles(30, { base: 200, step: -1, spread: 2 }))
    expect(ch.direction).toBe('DOWN')
    expect(ch.slopePctPerBar).toBeLessThan(-0.15)
  })

  it('횡보(기울기 ≈ 0) → FLAT(박스권)', () => {
    const ch = computeTrendChannel(candles(30, { base: 100, step: 0, spread: 2, noise: i => (i % 2 ? 0.5 : -0.5) }))
    expect(ch.direction).toBe('FLAT')
  })

  it('position: 저가 꼬리가 길면(하단이 멀면) 종가는 채널 상단 쪽 — 0~1 clamp', () => {
    // 고가는 종가 바로 위(+0.5), 저가는 멀리 아래(-4) → 채널 하단이 멀어져 종가가 상단 근접
    const data = Array.from({ length: 30 }, (_, i) => {
      const close = 100 + i
      return { close, high: close + 0.5, low: close - 4, open: close }
    })
    const ch = computeTrendChannel(data)
    expect(ch.position).toBeGreaterThan(0.7)
    expect(ch.position).toBeLessThanOrEqual(1)
    expect(ch.position).toBeGreaterThanOrEqual(0)
  })

  it('데이터 부족(MIN_CHANNEL_BARS 미만)/빈 입력 → null (§4c: 위장 금지)', () => {
    expect(computeTrendChannel(candles(MIN_CHANNEL_BARS - 1))).toBeNull()
    expect(computeTrendChannel([])).toBeNull()
    expect(computeTrendChannel(null)).toBeNull()
  })

  it('비정상 가격(0/NaN 종가) → null', () => {
    const bad = candles(15)
    bad[7] = { close: 0, high: 1, low: 0, open: 0 }
    expect(computeTrendChannel(bad)).toBeNull()
  })

  it('고저가 0/음수(거래정지일 봉) → null — 백엔드 TrendChannelCalculator 와 동일 극성', () => {
    // 종가는 정상인데 저가=0 인 봉(거래정지일 KIS 일봉 형태) → 채널 하단이 0 근처로 붕괴해
    // position≈1(상단 100%) 오판을 만들므로 채널 미산출이 정직(§4c)
    const haltLow = candles(15)
    haltLow[7] = { close: 100, high: 100, low: 0, open: 100 }
    expect(computeTrendChannel(haltLow)).toBeNull()

    const haltHigh = candles(15)
    haltHigh[7] = { close: 100, high: -1, low: 99, open: 100 }
    expect(computeTrendChannel(haltHigh)).toBeNull()
  })

  it('widthPct: 채널 폭 = (상단-하단)/중심 % — spread 클수록 넓다', () => {
    const narrow = computeTrendChannel(candles(30, { base: 100, step: 0.5, spread: 1 }))
    const wide = computeTrendChannel(candles(30, { base: 100, step: 0.5, spread: 5 }))
    expect(wide.widthPct).toBeGreaterThan(narrow.widthPct)
  })
})

describe('detectChannelBreakout (직전 채널 대비 이탈)', () => {
  it('마지막 종가가 직전 채널 위로 급등 마감 → UP_BREAKOUT', () => {
    const data = candles(29, { base: 100, step: 0.2, spread: 1 })
    const prevClose = data[data.length - 1].close
    data.push({ close: prevClose + 15, high: prevClose + 16, low: prevClose, open: prevClose })
    const bo = detectChannelBreakout(data)
    expect(bo?.type).toBe('UP_BREAKOUT')
    expect(breakoutLabel(bo)).toContain('상단 돌파')
  })

  it('마지막 종가가 직전 채널 아래로 급락 마감 → DOWN_BREAKDOWN', () => {
    const data = candles(29, { base: 100, step: 0.2, spread: 1 })
    const prevClose = data[data.length - 1].close
    data.push({ close: prevClose - 15, high: prevClose, low: prevClose - 16, open: prevClose })
    const bo = detectChannelBreakout(data)
    expect(bo?.type).toBe('DOWN_BREAKDOWN')
    expect(breakoutLabel(bo)).toContain('하단 이탈')
  })

  it('채널 안에서 마감(정상 추세 지속) → null (배지 없음)', () => {
    expect(detectChannelBreakout(candles(30, { base: 100, step: 0.5, spread: 2 }))).toBeNull()
    expect(breakoutLabel(null)).toBeNull()
  })

  it('봉 부족(직전 채널 성립 불가) → null', () => {
    expect(detectChannelBreakout(candles(MIN_CHANNEL_BARS))).toBeNull()
  })
})

describe('channelComment (차트쟁이식 해설)', () => {
  it('상승 채널 + 상단 근접 → 저항권/추격 주의 문구', () => {
    const text = channelComment({ direction: 'UP', slopePctPerBar: 0.6, position: 0.9, widthPct: 8.2 })
    expect(text).toContain('상승 채널')
    expect(text).toContain('+0.60%/일')
    expect(text).toContain('추격 매수 주의')
    expect(text).toContain('폭 8.2%')
  })

  it('상승 채널 + 하단 근접 → 눌림목 관찰 문구', () => {
    const text = channelComment({ direction: 'UP', slopePctPerBar: 0.4, position: 0.1, widthPct: 6 })
    expect(text).toContain('눌림목 관찰')
  })

  it('하락 채널 + 하단 → 이탈 시 가속 주의 문구', () => {
    const text = channelComment({ direction: 'DOWN', slopePctPerBar: -0.5, position: 0.05, widthPct: 10 })
    expect(text).toContain('하락 채널')
    expect(text).toContain('추세 가속 주의')
  })

  it('박스권 + 중단 → 박스권/중단 위치 문구', () => {
    const text = channelComment({ direction: 'FLAT', slopePctPerBar: 0.02, position: 0.5, widthPct: 5 })
    expect(text).toContain('박스권')
    expect(text).toContain('채널 중단 50%')
  })

  it('null 채널 → null (캡션 숨김)', () => {
    expect(channelComment(null)).toBeNull()
  })
})
