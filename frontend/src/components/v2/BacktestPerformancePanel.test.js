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

  // 2026-10-03: 30·60·90일 버튼이 있었지만 추천 스냅샷은 7일만 보존돼 어느 버튼이든 실제 표본은 최근 7일이었다.
  it('재현: 보존 기간(7일)만 요청하고, 실제 표본 시작과 세금 0.15% 를 말한다 — 기간 버튼 없음', async () => {
    apiClient.get.mockResolvedValue({ data: { success: true, data: {
      ...performanceResponse.data.data, days: 7, retentionDays: 7, sampleFrom: '2026-09-28T11:30:00'
    } } })
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    expect(apiClient.get).toHaveBeenLastCalledWith('/backtest/performance', { params: { days: 7 } })
    expect(w.findAll('.bt-day-btn')).toHaveLength(0)
    expect(w.text()).toContain('최근 7일')
    expect(w.find('.bt-caption').text()).toContain('09/28 11:30 이후')
    expect(w.find('.bt-caption').text()).toContain('세금 0.15%')
    expect(w.find('.bt-caption').text()).not.toContain('0.18%')
  })

  it('재현: 표본이 적어 MDD·Sharpe 를 못 내면 0 이 아니라 —', async () => {
    apiClient.get.mockResolvedValue({ data: { success: true, data: {
      overall: { totalPicks: 1, winCount: 1, hitRate: 100, avgReturn: 2.0, mdd: null, sharpeRatio: null },
      strategies: [], retentionDays: 7
    } } })
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    const text = w.find('.bt-overall').text()
    expect(text).not.toContain('-0%')
    expect(text).not.toMatch(/Sharpe\s*0/)
    expect(text).toContain('—')
  })

  it('API 실패 시 에러 상태 (콘솔 폭발 없음)', async () => {
    apiClient.get.mockRejectedValue(new Error('500'))
    const w = mount(BacktestPerformancePanel)
    await flushPromises()

    expect(w.find('.bt-state').text()).toContain('불러오지 못했습니다')
  })
})
