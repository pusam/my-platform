import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'

/**
 * 시장 탭 수급 화면의 갱신 시각(2026-11-09).
 * 재현(운영 17:06): 매매 동향 표는 이미 떠 있는데 머리의 갱신 시각이 '대기 중...' 이었다 — 첫 조회를 자동 갱신 장치 밖에서
 * 불러 시각이 남지 않았고, 2분 뒤 첫 자동 갱신 때에야 시각이 생겼다. 반대로 조회가 실패해도 화면 함수가 오류를 삼켜
 * 자동 갱신 장치는 '성공'으로 보고 '방금'을 찍었다(허브는 2026-10-07 에 같은 부류를 고쳤다).
 */
vi.mock('../../utils/api', () => ({
  investorAPI: {
    getAllTopTrades: vi.fn(),
    getAllConsecutiveBuy: vi.fn(() => Promise.resolve({ data: { success: true, data: {} } }))
  },
  shortSellingAPI: { getTop: vi.fn(() => Promise.resolve({ data: { success: true, data: [] } })) }
}))
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { investorAPI } from '../../utils/api'
import InvestorAnalysisPage from '../InvestorAnalysisPage.vue'
import DataFreshness from '../../components/DataFreshness.vue'

const mountPage = async () => {
  const w = shallowMount(InvestorAnalysisPage, {
    props: { embedded: true },
    global: { stubs: { LoadingSpinner: true, BackButton: true } }
  })
  await flushPromises()
  return w
}

describe('InvestorAnalysisPage — 갱신 시각', () => {
  // 중괄호 필수 — 화살표가 mockReset() 의 반환값(목 함수)을 돌려주면 vitest 가 그걸 정리 단계에서 한 번 더 부른다
  beforeEach(() => { investorAPI.getAllTopTrades.mockReset() })
  afterEach(() => { vi.useRealTimers() })

  it('재현: 처음 연 순간 받은 데이터의 시각을 남긴다 — 2분 동안 "대기 중..." 이 아니다', async () => {
    investorAPI.getAllTopTrades.mockResolvedValue({ data: { success: true, data: { FOREIGN: [], INSTITUTION: [] } } })
    const w = await mountPage()

    expect(investorAPI.getAllTopTrades).toHaveBeenCalledTimes(1)          // 첫 조회는 한 번만
    expect(w.findComponent(DataFreshness).props('lastUpdated')).toBeInstanceOf(Date)
    w.unmount()
  })

  it('조회가 실패하면 시각을 남기지 않는다 — 실패를 "방금 갱신"으로 보이지 않는다', async () => {
    investorAPI.getAllTopTrades.mockRejectedValue(new Error('network'))
    const w = await mountPage()

    expect(w.findComponent(DataFreshness).props('lastUpdated')).toBeNull()
    w.unmount()
  })

  it('서버가 success=false 를 주어도 실패다', async () => {
    investorAPI.getAllTopTrades.mockResolvedValue({ data: { success: false } })
    const w = await mountPage()

    expect(w.findComponent(DataFreshness).props('lastUpdated')).toBeNull()
    w.unmount()
  })
})
