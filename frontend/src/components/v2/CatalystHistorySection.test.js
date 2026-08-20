import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CatalystHistorySection from './CatalystHistorySection.vue'
import apiClient from '../../utils/api'

vi.mock('../../utils/api', () => ({ default: { get: vi.fn() } }))

function historyResp(data) {
  return { data: { success: true, data } }
}

const fullHistory = {
  stockCode: '005930',
  windowDays: 30,
  items: [
    { catalystDate: '2026-07-06', catalystType: 'ORDER_WIN', typeLabel: '수주', direction: 'POSITIVE', headline: '대규모 수주', summary: '5000억 공급계약', changeRate: 4.2, newsLink: 'https://news.example/1' },
    { catalystDate: '2026-07-02', catalystType: 'LITIGATION', typeLabel: '소송', direction: 'NEGATIVE', headline: '특허 소송', summary: '피소', changeRate: null },
    { catalystDate: '2026-06-28', catalystType: 'GOVERNANCE', typeLabel: '지배구조', direction: 'NEUTRAL', headline: '자사주', summary: '자사주 매입', changeRate: -1.1 }
  ]
}

async function mountSection(data) {
  apiClient.get.mockResolvedValue(historyResp(data))
  const w = mount(CatalystHistorySection, { props: { stockCode: '005930' } })
  await flushPromises()
  return w
}

describe('CatalystHistorySection — 📰 재료 이력 (stock_catalyst 30일 read-only)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('요약을 제목에 병기 — "최근 30일 3건 · 호재 1 · 악재 1 · 중립 1"', async () => {
    const w = await mountSection(fullHistory)
    const title = w.find('.ds-title')
    expect(title.exists()).toBe(true)
    expect(title.text()).toContain('최근 30일 3건')
    expect(title.text()).toContain('호재 1')
    expect(title.text()).toContain('악재 1')
    expect(title.text()).toContain('중립 1')
  })

  it('타임라인 — 호재🔥/악재⚠️/중립➖ 배지 + 날짜별 등락률(없으면 생략, §4c)', async () => {
    const w = await mountSection(fullHistory)
    const rows = w.findAll('.ch-row')
    expect(rows).toHaveLength(3)

    // 호재 행 — 🔥 + 등락률 양수 초록
    expect(rows[0].text()).toContain('호재')
    expect(rows[0].text()).toContain('수주')
    expect(rows[0].find('.ch-badge').classes()).toContain('pos')
    expect(rows[0].find('.ch-pct').classes()).toContain('positive')
    expect(rows[0].text()).toContain('+4.2%')

    // 악재 행 — ⚠️ + changeRate null → 등락률 미표시
    expect(rows[1].text()).toContain('악재')
    expect(rows[1].find('.ch-badge').classes()).toContain('neg')
    expect(rows[1].find('.ch-pct').exists()).toBe(false)

    // 중립 행 — ➖ + 음수 적색
    expect(rows[2].text()).toContain('중립')
    expect(rows[2].find('.ch-badge').classes()).toContain('neu')
    expect(rows[2].find('.ch-pct').classes()).toContain('negative')
  })

  it('근거 기사 링크(V53) — newsLink 있으면 📰 링크, 없으면 생략(§4c)', async () => {
    const w = await mountSection(fullHistory)
    const rows = w.findAll('.ch-row')
    const link = rows[0].find('a.ch-link')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('https://news.example/1')
    expect(link.attributes('target')).toBe('_blank')
    expect(rows[1].find('a.ch-link').exists()).toBe(false)   // newsLink 없음 → 생략
  })

  it('이력 0건이면 섹션 자체 미렌더', async () => {
    const w = await mountSection({ stockCode: '005930', windowDays: 30, items: [] })
    expect(w.find('.detail-section').exists()).toBe(false)
  })

  it('API 실패 시 조용히 미렌더', async () => {
    apiClient.get.mockRejectedValue(new Error('down'))
    const w = mount(CatalystHistorySection, { props: { stockCode: '005930' } })
    await flushPromises()
    expect(w.find('.detail-section').exists()).toBe(false)
  })

  it('stockCode 변경 시 재조회 (catalyst-history 엔드포인트)', async () => {
    const w = await mountSection(fullHistory)
    expect(apiClient.get).toHaveBeenCalledWith('/stock/005930/catalyst-history')
    await w.setProps({ stockCode: '000660' })
    await flushPromises()
    expect(apiClient.get).toHaveBeenCalledWith('/stock/000660/catalyst-history')
  })
})
