import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/utils/api', () => ({
  aiStrategyAPI: { getPerformance: vi.fn() },
  paperTradingAPI: { getStatistics: vi.fn() }
}))

import SectionBacktest from './SectionBacktest.vue'
import { aiStrategyAPI, paperTradingAPI } from '@/utils/api'

// 운영 화면(2026-10-07 10:4x) 값 그대로 — 평균 수익 0.996 → 서버 반올림 1.00 은 JSON 에서 1 이 된다
const PERF = {
  days: 7, retentionDays: 7, sampleFrom: '2026-09-30T11:30:00',
  overall: { totalPicks: 37, winCount: 20, hitRate: 54.1, avgReturn: 1, mdd: 42.65, sharpeRatio: 0.12 },
  strategies: [
    { strategyType: 'SCALPING', label: '스캘핑', totalPicks: 15, winCount: 3, loseCount: 12, hitRate: 20, avgReturn: -1.72,
      mdd: 42.65, bestReturn: -0.5, bestStock: '가나다', worstReturn: -9.1, worstStock: '라마바', picks: [] },
    { strategyType: 'VALUE', label: '가치투자', totalPicks: 0, winCount: 0, loseCount: 0, hitRate: null, avgReturn: null,
      mdd: null, picks: [] }
  ]
}

const mountWith = async (perf = PERF) => {
  aiStrategyAPI.getPerformance.mockResolvedValue({ data: perf })
  const w = mount(SectionBacktest, { global: { mocks: { $router: { push: vi.fn() } } } })
  await flushPromises()
  return w
}

/**
 * 발굴 → 백테스트(AI 전략 성과) 표시 — 2026-10-07 화면 점검.
 * 재현: 같은 데이터를 그리는 다른 패널(BacktestPerformancePanel)은 10/3 에 고쳤는데 발굴 탭에 보이는 이 화면은 그대로였다 —
 * 'MDD 42.65%'(추천별 수익률을 이어 붙인 곡선의 %p 인데 계좌 낙폭처럼), '평균 수익률 +1%'(1.00 이 정수로), 측정 정의 없음.
 */
describe('SectionBacktest', () => {
  it('재현: MDD 는 %p 이고, 평균 수익률은 소수 둘째 자리까지', async () => {
    const w = await mountWith()
    const text = w.text()
    expect(text).toContain('-42.65%p')
    expect(text).not.toMatch(/MDD\s*42\.65%(?!p)/)
    expect(text).toContain('+1.00%')
    expect(text).toContain('-1.72%')
  })

  it('측정 정의를 밝힌다 — 현재가 대비, 정해진 보유 기간 아님, 계좌 곡선 아님, 표본 시작', async () => {
    const w = await mountWith()
    const cap = w.find('.bt-caption').text()
    expect(cap).toContain('09/30 11:30')
    expect(cap).toContain('현재가 대비')
    expect(cap).toContain('실제 계좌 곡선 아님')
  })

  it('추천이 없는 전략은 적중 0%·+0% 가 아니라 —', async () => {
    const w = await mountWith()
    const value = w.findAll('.strategy-card').find(c => c.text().includes('가치투자'))
    expect(value.text()).toContain('적중 —')
    expect(value.text()).not.toMatch(/null|NaN|적중 0%/)
  })

  it('최고 수익이 음수여도 "+-" 로 쓰지 않는다', async () => {
    const w = await mountWith()
    await w.findAll('.strategy-card')[0].trigger('click')
    expect(w.text()).toContain('가나다 -0.50%')
    expect(w.text()).not.toContain('+-')
  })

  it('표본 0 이면 평균 수익률은 —', async () => {
    const w = await mountWith({ ...PERF, overall: { totalPicks: 0, winCount: 0, hitRate: null, avgReturn: null, mdd: null }, strategies: [] })
    expect(w.text()).not.toMatch(/null|NaN/)
  })

  it('재현: 모의 계좌 통계를 "실전 봇"으로 비교하지 않는다 — 봇은 추천 산식을 쓰지 않는다(CLAUDE.md §7)', async () => {
    const w = await mountWith()
    expect(w.text()).not.toContain('실전 봇')
    expect(paperTradingAPI.getStatistics).not.toHaveBeenCalled()
  })
})
