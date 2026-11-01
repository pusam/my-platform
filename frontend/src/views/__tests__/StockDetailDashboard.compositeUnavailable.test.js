import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'

/**
 * 종목 상세 '신호 m/5' 배지 — 조회 실패한 신호(2026-10-19, 리뷰 에이전트 지적).
 * 재현: 복합 신호 5개는 조회가 실패하면 '안 맞음'과 같은 값이라, 차트 조회가 실패한 종목도 '신호 0/5'로 보였다. 서버가 이제
 * 실패한 신호 수(unavailableCount)를 따로 주고, 배지는 그 수를 함께 적는다.
 */
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const route = reactive({ params: { stockCode: '900001' }, query: {} })
  const router = { push: vi.fn(), replace: vi.fn() }
  return { useRoute: () => route, useRouter: () => router }
})

vi.mock('../../utils/api', async (importOriginal) => {
  const actual = await importOriginal()
  const none = () => Promise.resolve({ data: { success: false } })
  return {
    ...actual,
    default: { get: vi.fn(none), post: vi.fn(none) },
    stockDetailAPI: { getQuick: vi.fn(), getHeavy: vi.fn(), getDiagnosis: vi.fn() },
    stockAPI: { searchStocks: vi.fn(none) },
    quantTaAPI: {
      patterns: vi.fn(none), supportResistance: vi.fn(none), volumeProfile: vi.fn(none),
      compositeSignal: vi.fn(none), relatedStocks: vi.fn(none)
    }
  }
})
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() } }))

import { stockDetailAPI, quantTaAPI } from '../../utils/api'
import StockDetailDashboard from '../StockDetailDashboard.vue'

const quick = { data: { success: true, data: {
  stockName: '실패종목', price: { currentPrice: 1000, changePrice: 0, changeRate: 0 },
  supplyDemand: null, financial: null, chartData: null
} } }

const composite = (matchedCount, unavailableCount) => ({ data: { success: true, data: {
  matchedCount, totalCount: 5, unavailableCount, signals: []
} } })

describe('StockDetailDashboard — 신호 배지의 조회 실패', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stockDetailAPI.getQuick.mockResolvedValue(quick)
    stockDetailAPI.getHeavy.mockResolvedValue({ data: { success: false } })
    stockDetailAPI.getDiagnosis.mockResolvedValue({ data: { success: false } })
  })

  it('재현: 조회 실패한 신호가 있으면 배지가 그 수를 적는다 — "신호 2/5 · 조회 실패 2"', async () => {
    quantTaAPI.compositeSignal.mockResolvedValue(composite(2, 2))
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()

    expect(w.find('.composite-badge').text()).toContain('신호 2/5 · 조회 실패 2')
  })

  it('실패가 없으면 종전대로 "신호 2/5"만', async () => {
    quantTaAPI.compositeSignal.mockResolvedValue(composite(2, 0))
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()

    expect(w.find('.composite-badge').text()).toContain('신호 2/5')
    expect(w.find('.composite-badge').text()).not.toContain('조회 실패')
  })
})
