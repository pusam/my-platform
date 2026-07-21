import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SectionMarketMap from './SectionMarketMap.vue'

// chart.js 는 캔버스 필요 → jsdom 렌더 불가. 차트/모달은 스텁으로 대체(HtsChart.test 와 동일 원칙).
vi.mock('vue-chartjs', () => ({ Line: { name: 'Line', template: '<div class="chart-stub" />' } }))
vi.mock('chart.js', () => ({
  Chart: { register: () => {} },
  CategoryScale: {}, LinearScale: {}, PointElement: {}, LineElement: {},
  Title: {}, Tooltip: {}, Legend: {}, Filler: {}
}))
vi.mock('../../utils/api', () => ({
  marketAPI: { getForecast: vi.fn() },
  sectorAPI: { getSectorRotation: vi.fn() }
}))

function mountMap(props = {}) {
  return mount(SectionMarketMap, {
    props,
    global: { stubs: { ForecastDetailModal: true, SkeletonLoader: true, 'router-link': true } }
  })
}

describe('SectionMarketMap — 히트맵/예측 계산', () => {
  it('maxTradingValue — totalTradingValue 우선, 폴백 tradingValue, 빈 데이터는 1(0-나누기 방지)', () => {
    const w = mountMap({ sectorData: [
      { sectorName: '반도체', totalTradingValue: 5000 },
      { sectorName: '2차전지', tradingValue: 8000 },   // 구 필드명 폴백
      { sectorName: '바이오' }                          // 결측 → 1
    ] })
    expect(w.vm.maxTradingValue).toBe(8000)
    expect(mountMap({ sectorData: [] }).vm.maxTradingValue).toBe(1)
  })

  it('forecastChartData — 오늘(baseIndex) 기점 + D+1~5, Bull/기본 시나리오 데이터 배선', async () => {
    const w = mountMap()
    w.vm.forecastData = {
      baseIndex: 2700,
      forecasts: [
        { bull: 2720, base: 2705 }, { bull: 2740, base: 2710 }, { bull: 2760, base: 2715 },
        { bull: 2780, base: 2720 }, { bull: 2800, base: 2725 }
      ]
    }
    const chart = w.vm.forecastChartData
    expect(chart.labels).toEqual(['오늘', 'D+1', 'D+2', 'D+3', 'D+4', 'D+5'])
    const bull = chart.datasets.find(d => d.label.includes('Bull'))
    expect(bull.data).toEqual([2700, 2720, 2740, 2760, 2780, 2800])   // 첫 점 = 오늘 지수
    const base = chart.datasets.find(d => d.label.includes('기본'))
    expect(base.data[0]).toBe(2700)
  })

  it('forecastData 없으면 빈 차트(§4c — 가짜 곡선 없음)', () => {
    const w = mountMap()
    expect(w.vm.forecastChartData).toEqual({ labels: [], datasets: [] })
  })
})
