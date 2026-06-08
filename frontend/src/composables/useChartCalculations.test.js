import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useChartCalculations } from './useChartCalculations'

function setup() {
  const chartData = ref({
    // 최신 → 과거 순 (컴포저블이 reverse 하여 과거 → 최신으로 표시)
    candles: [
      { date: '2026-06-05', open: 110, close: 115, high: 120, low: 108 },
      { date: '2026-06-04', open: 100, close: 105, high: 106, low: 99 }
    ],
    volumes: [{ volume: 200 }, { volume: 100 }],
    maLine5: [114, 103]
  })
  const supportResistance = ref({
    resistance: [{ price: 118, strength: 'HIGH' }, { price: 999, strength: 'HIGH' }], // 999는 범위 밖
    support: [{ price: 100, strength: 'LOW' }]
  })
  const chartPatterns = ref([
    { keyPoints: [{ date: '2026-06-04', price: 105, role: 'low' }], signal: 'BULLISH', label: '더블바텀' }
  ])
  return { chartData, supportResistance, chartPatterns, ...useChartCalculations(chartData, supportResistance, chartPatterns) }
}

describe('useChartCalculations (P-IA ③-3차 분리)', () => {
  it('displayCandles/Volumes: slice(30) + reverse (과거→최신)', () => {
    const { displayCandles, displayVolumes } = setup()
    expect(displayCandles.value.map(c => c.date)).toEqual(['2026-06-04', '2026-06-05'])
    expect(displayVolumes.value.map(v => v.volume)).toEqual([100, 200])
  })

  it('chartPriceRange: min×0.98 / max×1.02', () => {
    const { chartPriceRange } = setup()
    expect(chartPriceRange.value.min).toBeCloseTo(99 * 0.98, 5)   // 최저가 99
    expect(chartPriceRange.value.max).toBeCloseTo(120 * 1.02, 5)  // 최고가 120
  })

  it('maxVolume / getVolumeHeight: 최대 거래량 대비 %', () => {
    const { maxVolume, getVolumeHeight } = setup()
    expect(maxVolume.value).toBe(200)
    expect(getVolumeHeight(100)).toBe(50)
  })

  it('chartSrLines: 범위 안 레벨만 (저항 118 + 지지 100, 999 제외)', () => {
    const { chartSrLines } = setup()
    expect(chartSrLines.value).toHaveLength(2)
    const types = chartSrLines.value.map(l => l.type)
    expect(types).toContain('resistance')
    expect(types).toContain('support')
    expect(chartSrLines.value.find(l => l.price === 999)).toBeUndefined()
  })

  it('chartPatternMarkers: 표시 30일 내 keyPoint 매핑', () => {
    const { chartPatternMarkers } = setup()
    expect(chartPatternMarkers.value).toHaveLength(1)
    const m = chartPatternMarkers.value[0]
    expect(m.label).toBe('더블바텀')
    expect(m.signal).toBe('BULLISH')
    expect(m.x).toBeCloseTo(25, 5) // (0+0.5)/2*100
  })

  it('maLinePath: 2점 이상이면 "x,y x,y" 문자열', () => {
    const { maLinePath } = setup()
    const path = maLinePath('maLine5')
    expect(typeof path).toBe('string')
    expect(path.split(' ')).toHaveLength(2)
    // 없는 라인키는 null
    expect(maLinePath('maLine999')).toBeNull()
  })

  it('getCandleStyle/getWickStyle/getBodyStyle: %단위 height/bottom 반환', () => {
    const { displayCandles, getCandleStyle, getWickStyle, getBodyStyle } = setup()
    const c = displayCandles.value[1]
    for (const fn of [getCandleStyle, getWickStyle, getBodyStyle]) {
      const s = fn(c)
      expect(s.height.endsWith('%')).toBe(true)
      expect(s.bottom.endsWith('%')).toBe(true)
    }
  })

  it('빈/널 입력 방어: 예외 없이 기본값', () => {
    const empty = useChartCalculations(ref(null), ref(null), ref([]))
    expect(empty.displayCandles.value).toEqual([])
    expect(empty.maxVolume.value).toBe(1)
    expect(empty.chartSrLines.value).toEqual([])
    expect(empty.chartPatternMarkers.value).toEqual([])
    expect(empty.maLinePath('maLine5')).toBeNull()
  })
})
