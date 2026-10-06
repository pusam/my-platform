import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SectionTotalRecommendation from './SectionTotalRecommendation.vue'
import { quantTaAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({ quantTaAPI: { compositeRanking: vi.fn() } }))

const item = (code, matched) => ({
  stockCode: code, stockName: `종목${code}`, matchedCount: matched, totalCount: 5,
  signals: [{ id: 'PATTERN', label: '패턴', matched: matched > 0 }]
})
const ok = (data) => ({ data: { success: true, data } })

async function mountSection() {
  const w = mount(SectionTotalRecommendation, { global: { mocks: { $router: { push: vi.fn() } } } })
  await flushPromises()
  return w
}

/**
 * 🎯종합 — 실패·대기를 '종목 없음'으로 보이지 않는다(2026-10-07, 2026-10-04 점검의 남은 LOW).
 *
 * 재현: 조회가 실패하면(네트워크·success=false) 목록이 비어 '조건에 맞는 종목이 없습니다'가 떴다 — 실패가 '종목 없음'으로 보였다.
 * 빈 결과는 백엔드가 백그라운드 평가를 시작했다는 뜻이라 30초마다 다시 묻는데, 평가가 실패해도 응답은 같은 빈 목록이라
 * '데이터 준비 중'이 끝없이 이어졌다 — 5분이 지나면 멈추고 그렇게 말한다.
 */
describe('SectionTotalRecommendation — 실패는 실패라고', () => {
  afterEach(() => { vi.useRealTimers() })

  it('재현: 조회가 실패하면 "조건에 맞는 종목이 없습니다"가 아니라 불러오지 못했다고 말한다', async () => {
    quantTaAPI.compositeRanking.mockRejectedValue(new Error('500'))
    const w = await mountSection()
    expect(w.text()).toContain('불러오지 못했습니다')
    expect(w.text()).not.toContain('조건에 맞는 종목이 없습니다')
    expect(w.find('.tr-retry').exists()).toBe(true)
  })

  it('서버가 실패로 답해도(success=false) 같은 실패 문구', async () => {
    quantTaAPI.compositeRanking.mockResolvedValue({ data: { success: false, message: 'x' } })
    const w = await mountSection()
    expect(w.text()).toContain('불러오지 못했습니다')
  })

  it('갱신이 실패하면 받아 둔 목록은 그대로 두고 갱신 실패를 알린다', async () => {
    quantTaAPI.compositeRanking.mockResolvedValue(ok([item('005930', 4)]))
    const w = await mountSection()
    quantTaAPI.compositeRanking.mockRejectedValue(new Error('500'))
    await w.find('.tr-refresh').trigger('click')
    await flushPromises()
    expect(w.findAll('.tr-row')).toHaveLength(1)
    expect(w.find('.tr-stale').text()).toContain('갱신 실패')
  })

  it('다시 시도로 받으면 목록이 뜬다', async () => {
    quantTaAPI.compositeRanking.mockRejectedValue(new Error('500'))
    const w = await mountSection()
    quantTaAPI.compositeRanking.mockResolvedValue(ok([item('005930', 4)]))
    await w.find('.tr-retry').trigger('click')
    await flushPromises()
    expect(w.findAll('.tr-row')).toHaveLength(1)
    expect(w.text()).not.toContain('불러오지 못했습니다')
  })

  it('재현: 빈 결과(준비 중)가 5분 넘게 이어지면 다시 묻기를 멈추고 그렇게 말한다', async () => {
    vi.useFakeTimers()
    quantTaAPI.compositeRanking.mockResolvedValue(ok([]))
    const w = await mountSection()
    expect(w.text()).toContain('데이터 준비 중')
    for (let i = 0; i < 12; i++) {
      vi.advanceTimersByTime(30_000)
      await flushPromises()
    }
    expect(w.text()).not.toContain('데이터 준비 중')
    expect(w.text()).toContain('결과를 받지 못했습니다')
    const calls = quantTaAPI.compositeRanking.mock.calls.length
    vi.advanceTimersByTime(120_000)
    await flushPromises()
    expect(quantTaAPI.compositeRanking.mock.calls.length).toBe(calls)   // 더 묻지 않는다
  })

  it('준비 중이다가 결과가 오면 목록', async () => {
    vi.useFakeTimers()
    quantTaAPI.compositeRanking.mockResolvedValue(ok([]))
    const w = await mountSection()
    quantTaAPI.compositeRanking.mockResolvedValue(ok([item('005930', 4), item('000660', 2)]))
    vi.advanceTimersByTime(30_000)
    await flushPromises()
    expect(w.findAll('.tr-row')).toHaveLength(2)
    expect(w.text()).not.toContain('데이터 준비 중')
  })

  it('받은 목록이 필터에 하나도 안 맞을 때만 "조건에 맞는 종목이 없습니다"', async () => {
    quantTaAPI.compositeRanking.mockResolvedValue(ok([item('005930', 2)]))
    const w = await mountSection()
    await w.findAll('.tr-filter-btn')[1].trigger('click')   // 4점+
    expect(w.text()).toContain('조건에 맞는 종목이 없습니다')
  })
})
