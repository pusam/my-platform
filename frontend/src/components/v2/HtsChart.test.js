import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

// lightweight-charts 는 캔버스 필요 → jsdom 렌더 불가. 이 파일 안에서만 mock 해 API 인자를 검증(가이드 §6-3).
const created = { candleOpts: null, series: [], setDataCalls: [], priceLines: [], fitContentCalls: 0, crosshairCb: null }
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
      timeScale: () => ({ fitContent: () => { created.fitContentCalls++ } }),
      subscribeCrosshairMove: (cb) => { created.crosshairCb = cb },
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
    created.fitContentCalls = 0
    created.crosshairCb = null
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

  it('오버레이 토글은 fitContent 미호출(사용자 줌/팬 보존), 캔들 데이터 변경은 호출', async () => {
    const wrapper = mount(HtsChart, {
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
    await new Promise(r => setTimeout(r, 0))
    expect(created.fitContentCalls).toBe(1)   // 마운트 시 1회

    // 오버레이(S/R) 토글 — 줌 리셋하면 안 됨
    await wrapper.setProps({ srLevels: [{ price: 110, type: 'resistance', strength: 'HIGH' }] })
    await new Promise(r => setTimeout(r, 0))
    expect(created.fitContentCalls).toBe(1)   // 그대로

    // 캔들 데이터 변경(기간/종목 전환) — 전체 뷰 맞춤
    await wrapper.setProps({
      displayCandles: [
        { date: '2026-07-12', open: 98, high: 101, low: 97, close: 100 },
        { date: '2026-07-13', open: 100, high: 105, low: 99, close: 104 },
        { date: '2026-07-14', open: 104, high: 106, low: 100, close: 101 },
      ],
    })
    await new Promise(r => setTimeout(r, 0))
    expect(created.fitContentCalls).toBe(2)
  })

  it('십자선 범례 등락률 = 직전 봉 종가 대비(HTS 관례), 첫 봉은 미표시', async () => {
    const wrapper = mount(HtsChart, {
      props: {
        displayCandles: [
          { date: '2026-07-13', open: 100, high: 105, low: 99, close: 104 },
          { date: '2026-07-14', open: 103, high: 106, low: 100, close: 101 },   // 갭하락 시가 103 — 시가 대비(-1.94%)와 구분
        ],
        displayVolumes: [{ volume: 1000 }, { volume: 2000 }],
        todayYmd: '2026-07-15',
      },
      attachTo: document.body,
    })
    await new Promise(r => setTimeout(r, 0))

    const candleSeries = created.series.find(s => s.def === 'CANDLE').s
    // 2번째 봉: 전봉 종가 104 대비 종가 101 = -2.88% (구 구현의 당봉 시가 대비면 -1.94%)
    created.crosshairCb({
      time: '2026-07-14',
      seriesData: { get: (s) => (s === candleSeries ? { open: 103, high: 106, low: 100, close: 101 } : null) },
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.hts-legend-chg').text()).toContain('-2.88')

    // 첫 봉 — 직전 봉 없음 → 등락률 미표시
    created.crosshairCb({
      time: '2026-07-13',
      seriesData: { get: (s) => (s === candleSeries ? { open: 100, high: 105, low: 99, close: 104 } : null) },
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.hts-legend-chg').exists()).toBe(false)
  })
})
