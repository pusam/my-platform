import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SectionQuantTa from './SectionQuantTa.vue'
import { quantTaAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({
  quantTaAPI: {
    resolveNames: vi.fn(), backfillNames: vi.fn(), universeStatus: vi.fn(),
    collectHistoryProgress: vi.fn(), collectHistory: vi.fn(), screen: vi.fn(), correlation: vi.fn()
  },
  recommendationAPI: { getTop5: vi.fn() }
}))

async function mountSection() {
  quantTaAPI.universeStatus.mockResolvedValue({ data: { success: false } })
  quantTaAPI.collectHistoryProgress.mockResolvedValue({ data: { success: false } })
  const w = mount(SectionQuantTa, {
    global: { mocks: { $router: { push: vi.fn() } } }
  })
  await flushPromises()
  return w
}

describe('SectionQuantTa — 스크리너/상관관계 로직', () => {
  beforeEach(() => vi.clearAllMocks())

  it('parsedCodes — 콤마/공백 분리, 4자리 미만·비숫자 제외, 최대 30개', async () => {
    const w = await mountSection()
    w.vm.corrCodesInput = '005930, 000660 abc 12 035420'
    expect(w.vm.parsedCodes).toEqual(['005930', '000660', '035420'])
    w.vm.corrCodesInput = Array.from({ length: 40 }, (_, i) => String(10000 + i)).join(' ')
    expect(w.vm.parsedCodes).toHaveLength(30)
  })

  it('buildPayload — null/false/빈문자열 제거, 0 은 유효값으로 유지', async () => {
    const w = await mountSection()
    w.vm.filter = { ...w.vm.filter, rsiBelow: 30, goldenCross: true, changeRateMin: 0, volumeRatioMin: null }
    expect(w.vm.buildPayload()).toEqual({ rsiBelow: 30, goldenCross: true, changeRateMin: 0 })
  })

  it('applyPreset — 이전 필터를 리셋하고 프리셋만 적용 + screen 호출', async () => {
    const w = await mountSection()
    quantTaAPI.screen.mockResolvedValue({ data: { success: true, data: { results: [], matchedCount: 0, universeSize: 100 } } })
    w.vm.filter.rsiAbove = 70   // 이전 잔여 필터
    w.vm.applyPreset(w.vm.presets.find(p => p.key === 'oversold'))
    await flushPromises()
    expect(w.vm.filter.rsiBelow).toBe(30)
    expect(w.vm.filter.rsiAbove).toBeNull()   // emptyFilter 로 리셋됨
    expect(quantTaAPI.screen).toHaveBeenCalledWith({ rsiBelow: 30, volumeRatioMin: 1.5 }, 50)
  })

  it('빈 필터로 runScreen — API 미호출, 결과 초기화', async () => {
    const w = await mountSection()
    await w.vm.runScreen()
    expect(quantTaAPI.screen).not.toHaveBeenCalled()
    expect(w.vm.results).toEqual([])
  })

  it('numColor — 한국 관례 극성(양수 positive/음수 negative), 결측은 무색(§4c)', async () => {
    const w = await mountSection()
    expect(w.vm.numColor(1.5)).toBe('positive')
    expect(w.vm.numColor(-0.1)).toBe('negative')
    expect(w.vm.numColor(0)).toBe('')
    expect(w.vm.numColor(null)).toBe('')
  })
})
