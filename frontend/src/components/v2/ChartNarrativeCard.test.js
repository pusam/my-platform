import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ChartNarrativeCard from './ChartNarrativeCard.vue'
import apiClient from '../../utils/api'

vi.mock('../../utils/api', () => ({ default: { get: vi.fn() } }))

const waitNarrative = {
  stockCode: '000660',
  verdict: 'WAIT',
  verdictLabel: '관망',
  verdictReason: '하단 터치 없이 첫 반등에 올라타는 자리입니다.',
  unverified: true,
  sections: [
    { title: '지금 위치', lines: ['볼린저 밴드 안쪽입니다(%B 0.42, 0=하단·1=상단).', '최근 20봉 안에 하단을 터치한 적이 없습니다.'] },
    { title: '반등의 성격', lines: ['바닥 이후 첫 반등 구간입니다.', '첫 반등은 저점을 다시 깨는 경우가 많아 신뢰도가 낮습니다.'] }
  ]
}

async function mountCard(data, { success = true } = {}) {
  apiClient.get.mockResolvedValue({ data: { success, data } })
  const w = mount(ChartNarrativeCard, { props: { stockCode: '000660' } })
  await flushPromises()
  return w
}

describe('ChartNarrativeCard — 📖 차트 해설 (관찰용)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('섹션 문장과 결론 근거를 그대로 보여준다', async () => {
    const w = await mountCard(waitNarrative)
    const text = w.text()

    expect(text).toContain('차트 해설')
    expect(text).toContain('관망')
    expect(text).toContain('하단 터치 없이 첫 반등')
    expect(text).toContain('지금 위치')
    expect(text).toContain('반등의 성격')
    expect(text).toContain('첫 반등은 저점을 다시 깨는 경우가 많아')
  })

  it('매수 신호가 아니라는 문구를 항상 붙인다', async () => {
    const w = await mountCard(waitNarrative)
    expect(w.text()).toContain('매수 신호가 아니며')
  })

  it('판단보류(UNKNOWN)면 카드를 렌더하지 않는다 — 빈 해설로 자리 차지 금지', async () => {
    const w = await mountCard({
      stockCode: '000660', verdict: 'UNKNOWN', verdictLabel: '판단보류',
      verdictReason: '일봉 데이터가 충분하지 않아 차트 판단을 하지 않습니다.', sections: []
    })
    expect(w.find('.narrative-card').exists()).toBe(false)
  })

  it('섹션이 비면 렌더하지 않는다', async () => {
    const w = await mountCard({ stockCode: '000660', verdict: 'WAIT', verdictLabel: '관망', sections: [] })
    expect(w.find('.narrative-card').exists()).toBe(false)
  })

  it('API 실패면 조용히 숨긴다 (best-effort)', async () => {
    apiClient.get.mockRejectedValue(new Error('boom'))
    const w = mount(ChartNarrativeCard, { props: { stockCode: '000660' } })
    await flushPromises()
    expect(w.find('.narrative-card').exists()).toBe(false)
  })

  it('결론별로 배지 색 클래스가 갈린다', async () => {
    const w1 = await mountCard(waitNarrative)
    expect(w1.find('.nc-verdict').classes()).toContain('v-wait')

    const w2 = await mountCard({ ...waitNarrative, verdict: 'OVERHEATED', verdictLabel: '과열 경계' })
    expect(w2.find('.nc-verdict').classes()).toContain('v-overheated')

    const w3 = await mountCard({ ...waitNarrative, verdict: 'WATCH', verdictLabel: '조건부 관심' })
    expect(w3.find('.nc-verdict').classes()).toContain('v-watch')
  })
})
