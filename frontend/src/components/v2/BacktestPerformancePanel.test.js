import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BacktestPerformancePanel from './BacktestPerformancePanel.vue'
import apiClient from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() }
}))

const performanceResponse = {
  data: {
    success: true,
    data: {
      days: 30,
      overall: { totalPicks: 24, winCount: 14, hitRate: 58.3, avgReturn: 1.42, mdd: 6.21, sharpeRatio: 0.45 },
      strategies: [
        {
          strategyType: 'SWING', label: '스윙', totalPicks: 10, winCount: 7, loseCount: 3,
          hitRate: 70.0, avgReturn: 2.1, bestReturn: 9.8, bestStock: '한화에어로스페이스',
          worstReturn: -4.2, worstStock: '카카오', mdd: 4.0
        }
      ]
    }
  }
}

describe('BacktestPerformancePanel — 추천 트랙레코드', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('전체 통계(적중률/평균수익/MDD/Sharpe) + 전략별 행 렌더', async () => {
    apiClient.get.mockResolvedValue(performanceResponse)
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    expect(w.find('.bt-overall').text()).toContain('58.3%')
    expect(w.find('.bt-overall').text()).toContain('+1.42%')
    expect(w.find('.bt-overall').text()).toContain('-6.21%')
    const row = w.find('.bt-table tbody tr')
    expect(row.text()).toContain('스윙')
    expect(row.text()).toContain('70%')
    expect(row.text()).toContain('+9.8%')
  })

  it('표본 0건이면 누적 안내 표시', async () => {
    apiClient.get.mockResolvedValue({
      data: { success: true, data: { overall: { totalPicks: 0 }, strategies: [] } }
    })
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    expect(w.find('.bt-state').text()).toContain('추천 이력이 아직 없습니다')
  })

  it('기간 버튼 클릭 → 해당 days 로 재조회', async () => {
    apiClient.get.mockResolvedValue(performanceResponse)
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    const btn90 = w.findAll('.bt-day-btn').find(b => b.text() === '90일')
    await btn90.trigger('click')
    await flushPromises()

    expect(apiClient.get).toHaveBeenLastCalledWith('/backtest/performance', { params: { days: 90 } })
  })

  it('API 실패 시 에러 상태 (콘솔 폭발 없음)', async () => {
    apiClient.get.mockRejectedValue(new Error('500'))
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    expect(w.find('.bt-state').text()).toContain('불러오지 못했습니다')
  })
})
