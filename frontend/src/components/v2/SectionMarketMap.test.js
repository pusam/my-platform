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

describe('SectionMarketMap — AI 예측 실패는 숫자 없이(2026-10-03)', () => {
  it('재현: fallback 이면 차트·확률 카드를 그리지 않는다 — 예전엔 지수 ±0.5%/일 직선과 30/50/20', async () => {
    const w = mountMap()
    w.vm.activeTab = 'forecast'
    w.vm.forecastLoading = false
    w.vm.forecastData = { baseIndex: 2750, fallback: true, summary: 'AI 예측을 만들지 못했습니다' }
    await w.vm.$nextTick()
    expect(w.find('.fallback-notice').exists()).toBe(true)
    expect(w.find('.chart-stub').exists()).toBe(false)
    expect(w.find('.scenario-cards').exists()).toBe(false)
    expect(w.text()).not.toMatch(/30%|50%|20%/)
  })

  it('정상 예측에는 미검증 표기가 붙는다', async () => {
    const w = mountMap()
    w.vm.activeTab = 'forecast'
    w.vm.forecastLoading = false
    w.vm.forecastData = {
      baseIndex: 2750, summary: 's',
      forecasts: [{ day: 1, bull: 2770, base: 2755, bear: 2735 }],
      scenarios: { bull: { probability: 35, reason: 'a' }, base: { probability: 45, reason: 'b' }, bear: { probability: 20, reason: 'c' } }
    }
    await w.vm.$nextTick()
    expect(w.find('.forecast-note').text()).toContain('미검증')
    expect(w.find('.scenario-cards').exists()).toBe(true)
  })
})
