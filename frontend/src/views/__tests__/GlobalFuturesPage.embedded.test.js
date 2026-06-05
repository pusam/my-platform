import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'

// onMounted → globalFuturesAPI.* 호출. 어떤 메서드든 빈 결과 resolve 하도록 Proxy mock.
vi.mock('../../utils/api', () => ({
  globalFuturesAPI: new Proxy({}, { get: () => () => Promise.resolve({ data: {} }) })
}))
// vue-router useRouter 스텁
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import GlobalFuturesPage from '../GlobalFuturesPage.vue'

function mountPage(props = {}) {
  return shallowMount(GlobalFuturesPage, { props })
}

describe('GlobalFuturesPage embedded (P-IA 1단계)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('일반 모드: 자체 GlobalNav + DashboardHeader 노출', () => {
    const w = mountPage({ embedded: false })
    expect(w.findComponent({ name: 'GlobalNav' }).exists()).toBe(true)
    expect(w.findComponent({ name: 'DashboardHeader' }).exists()).toBe(true)
  })

  it('embedded 모드: 자체 GlobalNav/GNB 숨김 (부모 대시보드가 보유)', () => {
    const w = mountPage({ embedded: true })
    expect(w.findComponent({ name: 'GlobalNav' }).exists()).toBe(false)
    expect(w.findComponent({ name: 'DashboardHeader' }).exists()).toBe(false)
  })

  it('embedded 여부와 무관하게 내부 시세 탭 바는 유지', () => {
    expect(mountPage({ embedded: true }).find('.main-tab-bar').exists()).toBe(true)
    expect(mountPage({ embedded: false }).find('.main-tab-bar').exists()).toBe(true)
  })
})
