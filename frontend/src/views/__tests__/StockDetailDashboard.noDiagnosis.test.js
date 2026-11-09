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

// 2026-11-09 재현(운영 삼성에스디에스 018260, 17:08): 머리 '중장기 펀더멘털 53 · 주의 요망', 게이지 53/100 은 노란색(주의 구간)인데
// 게이지 배지만 '안전' 이었다 — 배지는 리스크 점검 결과(위험 공시 없음 = SAFE)를, 색은 점수 구간을 읽었다. 같은 53 이 한 화면에서
// '주의 요망'·'안전' 둘 다였다. 리스크 점검 결과는 아래 리스크 카드가 말한다 — 배지는 게이지가 보이는 점수 구간(색과 같은 기준).
const gaugeBadge = (w) => w.find('.risk-gauge-section .gauge-header .risk-badge').text()

describe('StockDetailDashboard — 게이지 배지는 게이지의 점수 구간', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stockDetailAPI.getQuick.mockResolvedValue(quick)
  })

  it('재현(11/9): 진단 53 · 리스크 SAFE → 배지 "주의"(노란 게이지와 같은 구간, "안전" 아님)', async () => {
    stockDetailAPI.getHeavy.mockResolvedValue(heavy(0, 'SAFE'))
    stockDetailAPI.getDiagnosis.mockResolvedValue(diagnosis(53))
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()

    expect(gaugeScore(w)).toBe('53')
    expect(gaugeBadge(w)).toBe('주의')
    expect(w.find('.risk-gauge-section .gauge-header .risk-badge').classes()).toContain('warning')
  })

  it('65 이상은 "안전", 40 미만은 "위험" — 게이지 아래 눈금과 같은 말', async () => {
    stockDetailAPI.getHeavy.mockResolvedValue(heavy(0, 'DANGER'))
    stockDetailAPI.getDiagnosis.mockResolvedValue(diagnosis(80))
    const hi = shallowMount(StockDetailDashboard)
    await flushPromises()
    expect(gaugeBadge(hi)).toBe('안전')

    stockDetailAPI.getHeavy.mockResolvedValue(heavy(0, 'SAFE'))
    stockDetailAPI.getDiagnosis.mockResolvedValue(diagnosis(30))
    const lo = shallowMount(StockDetailDashboard)
    await flushPromises()
    expect(gaugeBadge(lo)).toBe('위험')
  })

  it('점수를 모르면 종전대로 리스크 점검 결과(색도 그것으로 폴백)', async () => {
    stockDetailAPI.getHeavy.mockResolvedValue(heavy(20, 'SAFE'))
    stockDetailAPI.getDiagnosis.mockResolvedValue(diagnosis(null))
    const w = shallowMount(StockDetailDashboard)
    await flushPromises()
    expect(gaugeBadge(w)).toBe('안전')
  })
})
