import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

// lightweight-charts 는 캔버스 필요 → jsdom 렌더 불가. 이 파일 안에서만 mock 해 API 인자를 검증(가이드 §6-3).
const created = { candleOpts: null, series: [], setDataCalls: [], priceLines: [] }
vi.mock('lightweight-charts', () => {
  const makeSeries = () => ({
    setData: (d) => created.setDataCalls.push(d),
    createPriceLine: (o) => { created.priceLines.push(o); return o },
    removePriceLine: () => {},
    priceScale: () => ({ applyOptions: () => {} }),
  })
  return {
    createChart: () => ({
      addSeries: (def, opts) => {
        if (def === 'CANDLE') created.candleOpts = opts
        const s = makeSeries()
        created.series.push({ def, opts, s })
        return s
      },
      removeSeries: () => {},
      priceScale: () => ({ applyOptions: () => {} }),
      timeScale: () => ({ fitContent: () => {}, }),
      subscribeCrosshairMove: () => {},
      applyOptions: () => {},
      remove: () => {},
    }),
    CandlestickSeries: 'CANDLE',
    HistogramSeries: 'HISTO',
    LineSeries: 'LINE',
    createSeriesMarkers: () => ({ setMarkers: () => {} }),
    LineStyle: { Solid: 0, Dashed: 2 },
    CrosshairMode: { Normal: 1 },
    ColorType: { Solid: 'solid' },
  }
})

import HtsChart from './HtsChart.vue'

describe('HtsChart.vue (렌더러 배선 — mock)', () => {
  beforeEach(() => {
    created.candleOpts = null
    created.series = []
    created.setDataCalls = []
    created.priceLines = []
  })

  it('마운트 시 캔들 시리즈를 한국 관례색(상승 빨강/하락 파랑)으로 생성', async () => {
    mount(HtsChart, {
      props: {
        displayCandles: [
          { date: '2026-07-13', open: 100, high: 105, low: 99, close: 104 },
          { date: '2026-07-14', open: 104, high: 106, low: 100, close: 101 },
        ],
        displayVolumes: [{ volume: 1000 }, { volume: 2000 }],
        todayYmd: '2026-07-15',
      },
      attachTo: document.body,
    })
    await new Promise(r => setTimeout(r, 0))   // onMounted+nextTick

    expect(created.candleOpts).toBeTruthy()
    expect(created.candleOpts.upColor).toBe('#ef4444')
    expect(created.candleOpts.downColor).toBe('#3b82f6')
    // 캔들 + 거래량 시리즈 setData 호출됨
    expect(created.setDataCalls.length).toBeGreaterThanOrEqual(2)
    expect(created.setDataCalls[0]).toHaveLength(2)   // 캔들 2개
  })

  it('S/R 레벨 → priceLine 생성(저항 빨강/지지 파랑)', async () => {
    mount(HtsChart, {
      props: {
        displayCandles: [{ date: '2026-07-14', open: 100, high: 105, low: 99, close: 104 }],
        srLevels: [
          { price: 110, type: 'resistance', strength: 'HIGH' },
          { price: 95, type: 'support', strength: 'LOW' },
        ],
        todayYmd: '2026-07-15',
      },
      attachTo: document.body,
    })
    await new Promise(r => setTimeout(r, 0))

    expect(created.priceLines).toHaveLength(2)
    expect(created.priceLines.find(p => p.price === 110).color).toBe('#ef4444')
    expect(created.priceLines.find(p => p.price === 95).color).toBe('#3b82f6')
  })
})
