import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'

/**
 * 종목 상세 '진단 점수 (리스크 감점)' 게이지 — 진단 점수를 모를 때(2026-10-19, 리뷰 에이전트 지적).
 * 재현: 재무 데이터가 없어 진단이 '분석 불가'면 서버는 종합점수 0 을 줬고 게이지는 '0 · 위험'이었다. 그 0 을 모름(null)으로 고치면
 * 게이지는 진단 점수 대신 '100 − 리스크 점수'를 진단 점수라며 보였다(리스크 20 → 80 · 안전) — 둘 다 지어낸 값이다(§4c).
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

import { stockDetailAPI } from '../../utils/api'
import StockDetailDashboard from '../StockDetailDashboard.vue'

const quick = { data: { success: true, data: {
  stockName: '분석불가회사', price: { currentPrice: 1000, changePrice: 0, changeRate: 0 },
  supplyDemand: null, financial: null, chartData: null
} } }
const heavy = (riskScore, riskStatus) => ({ data: { success: true, data: { risk: { riskScore, riskStatus } } } })
const diagnosis = (overallScore) => ({ data: { success: true, data: {
  overallScore, verdict: '분석 불가', verdictLevel: 'NEUTRAL', warnings: ['재무 데이터가 없습니다.'], positives: []
} } })

const gaugeScore = (w) => w.find('.risk-gauge-section .gauge-value .score').text()

describe('StockDetailDashboard — 진단 점수를 모를 때의 게이지', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stockDetailAPI.getQuick.mockResolvedValue(quick)
  })

  it('재현: 진단 점수가 모름(null)이면 게이지는 "-" — 100 − 리스크 점수를 진단 점수라며 보이지 않는다', async () => {
    stockDetailAPI.getHeavy.mockResolvedValue(heavy(20, 'SAFE'))
    stockDetailAPI.getDiagnosis.mockResolvedValue(diagnosis(null))
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()

    expect(gaugeScore(w)).toBe('-')
  })

  it('진단 조회가 실패해도 같다 — "-"', async () => {
    stockDetailAPI.getHeavy.mockResolvedValue(heavy(20, 'SAFE'))
    stockDetailAPI.getDiagnosis.mockResolvedValue({ data: { success: false } })
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()

    expect(gaugeScore(w)).toBe('-')
  })

  it('진단 점수가 있으면 종전대로 진단 점수 − 리스크×0.3', async () => {
    stockDetailAPI.getHeavy.mockResolvedValue(heavy(20, 'SAFE'))
    stockDetailAPI.getDiagnosis.mockResolvedValue(diagnosis(70))
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()

    expect(gaugeScore(w)).toBe('64')
  })
})
