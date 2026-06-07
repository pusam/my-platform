import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// api(default export) 모킹 — 컴포넌트가 마운트 시 api.get 4회 호출.
const mockGet = vi.fn()
vi.mock('../../utils/api', () => ({ default: { get: (...a) => mockGet(...a) } }))
// vue-chartjs Line 을 no-op 컴포넌트로 — jsdom 엔 canvas 미구현이라 실제 chart.js 회피.
vi.mock('vue-chartjs', () => ({ Line: { name: 'Line', template: '<div class="line-stub" />' } }))

import InvestorTrendTab from './InvestorTrendTab.vue'

function mountTab(props = {}) {
  return mount(InvestorTrendTab, {
    props: { stockCode: '005930', ...props }
  })
}

const ok = (data) => ({ data: { success: true, data } })

describe('InvestorTrendTab (P-IA 분리)', () => {
  beforeEach(() => mockGet.mockReset())

  it('마운트 시 일별 + 외국인/기관/연기금 surge 엔드포인트 4회 조회', async () => {
    mockGet.mockResolvedValue(ok([]))
    mountTab()
    await flushPromises()
    expect(mockGet).toHaveBeenCalledTimes(4)
    const urls = mockGet.mock.calls.map(c => c[0])
    expect(urls).toContain('/investor/stock/005930')
    expect(urls.filter(u => u === '/investor/surge/trend/005930')).toHaveLength(3)
  })

  it('데이터 없으면 빈 상태 안내 노출', async () => {
    mockGet.mockResolvedValue(ok([]))
    const w = mountTab()
    await flushPromises()
    expect(w.find('.inv-no-data').exists()).toBe(true)
    expect(w.text()).toContain('투자자 매매 데이터가 없습니다')
  })

  it('surge 데이터 있으면 장중 수급 섹션 + 항목 렌더 (시간 HH:MM)', async () => {
    mockGet.mockImplementation((url, opts) => {
      if (url === '/investor/stock/005930') return Promise.resolve(ok({ dailyTrades: [] }))
      if (opts?.params?.investorType === 'FOREIGN') return Promise.resolve(ok([
        { snapshotTime: '10:30:00', currentRank: 3, netBuyAmount: 120.5, amountChange: 5.2, currentPrice: 70000, changeRate: 1.5 }
      ]))
      return Promise.resolve(ok([]))
    })
    const w = mountTab()
    await flushPromises()
    expect(w.find('.inv-surge-grid').exists()).toBe(true)
    expect(w.findAll('.inv-surge-item')).toHaveLength(1)
    expect(w.find('.inv-time-badge').text()).toBe('10:30')
  })

  it('dailyTrades 있으면 차트 섹션 + 일별 카드 렌더', async () => {
    mockGet.mockImplementation((url) => {
      if (url === '/investor/stock/005930') return Promise.resolve(ok({
        dailyTrades: [
          { tradeDate: '2026-06-05', closePrice: 70000,
            foreign: { netBuyAmount: 10, buyAmount: 100, sellAmount: 90 },
            institution: { netBuyAmount: -5 }, pension: { netBuyAmount: 2 } }
        ]
      }))
      return Promise.resolve(ok([]))
    })
    const w = mountTab()
    await flushPromises()
    expect(w.find('.inv-chart-wrapper').exists()).toBe(true)
    expect(w.findAll('.inv-daily-card')).toHaveLength(1)
  })

  it('stockCode 없으면 조회 안 함', async () => {
    mockGet.mockResolvedValue(ok([]))
    mountTab({ stockCode: '' })
    await flushPromises()
    expect(mockGet).not.toHaveBeenCalled()
  })
})
