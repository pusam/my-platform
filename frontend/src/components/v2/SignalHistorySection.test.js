import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SignalHistorySection from './SignalHistorySection.vue'
import apiClient, { manualJournalAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() },
  manualJournalAPI: { listByStock: vi.fn() }
}))

function historyResp(data) {
  return { data: { success: true, data } }
}

const fullHistory = {
  stockCode: '005930',
  windowDays: 90,
  summary: { evaluatedCount: 3, hitCount: 2, avgAlpha: 1.2, pendingCount: 1 },
  items: [
    { signalDate: '2026-07-06', signalType: 'BUY', signalScore: 60, hit: null, pending: true, alpha3d: null, pctChange3d: null },
    { signalDate: '2026-07-01', signalType: 'STRONG_BUY', signalScore: 78, hit: true, pending: false, alpha3d: 2.4, pctChange3d: 3.1 },
    { signalDate: '2026-06-20', signalType: 'BUY', signalScore: 58, hit: false, pending: false, alpha3d: -1.2, pctChange3d: -0.5 }
  ]
}

async function mountSection(data, myTrades = []) {
  apiClient.get.mockResolvedValue(historyResp(data))
  manualJournalAPI.listByStock.mockResolvedValue({ data: { success: true, data: myTrades } })
  const w = mount(SignalHistorySection, { props: { stockCode: '005930' } })
  await flushPromises()
  return w
}

describe('SignalHistorySection — 📜 신호 이력 (signal_outcome 90일 read-only)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('요약을 제목에 병기 — "최근 90일 3회 중 2회 적중 · 평균 α +1.2% · 평가 대기 1건"', async () => {
    const w = await mountSection(fullHistory)
    const title = w.find('.ds-title')
    expect(title.exists()).toBe(true)
    expect(title.text()).toContain('최근 90일 3회 중 2회 적중')
    expect(title.text()).toContain('평균 α +1.2%')
    expect(title.text()).toContain('평가 대기 1건')
  })

  it('타임라인 — 적중 ✅ / 미적중 ❌ / 평가 대기 ⏳ 구분(§4c: 미평가≠미스) + 수익률 색상', async () => {
    const w = await mountSection(fullHistory)
    const rows = w.findAll('.sh-row')
    expect(rows).toHaveLength(3)

    // pending 행 — 평가 대기, 수익률 미표시
    expect(rows[0].text()).toContain('평가 대기')
    expect(rows[0].find('.sh-pct').exists()).toBe(false)
    expect(rows[0].classes()).toContain('row-pending')

    // 적중 행 — ✅ + 양수 초록
    expect(rows[1].text()).toContain('적중')
    expect(rows[1].text()).toContain('강력매수')
    expect(rows[1].find('.sh-pct').classes()).toContain('positive')
    expect(rows[1].text()).toContain('+3.1%')
    expect(rows[1].text()).toContain('α +2.4%')

    // 미적중 행 — ❌ + 음수 적색
    expect(rows[2].text()).toContain('미적중')
    expect(rows[2].find('.sh-pct').classes()).toContain('negative')
  })

  it('이력 0건이면 섹션 자체 미렌더', async () => {
    const w = await mountSection({
      stockCode: '005930', windowDays: 90,
      summary: { evaluatedCount: 0, hitCount: 0, avgAlpha: null, pendingCount: 0 }, items: []
    })
    expect(w.find('.detail-section').exists()).toBe(false)
  })

  it('API 실패 시 조용히 미렌더', async () => {
    apiClient.get.mockRejectedValue(new Error('down'))
    const w = mount(SignalHistorySection, { props: { stockCode: '005930' } })
    await flushPromises()
    expect(w.find('.detail-section').exists()).toBe(false)
  })

  it('📔 내 수동 매매 마커 병기 — 평가·매도 상태 표시, 저널만 있어도 섹션 렌더', async () => {
    const myTrades = [
      { id: 1, buyAt: '2026-07-01T10:00:00', buyPrice: 65000, evaluatedAt: '2026-07-04T19:40:00',
        hit: true, pctChange3d: 3.1, alpha3d: 2.0, sellAt: '2026-07-05T10:00:00', sellPrice: 67000, realizedPct: 3.08 },
      { id: 2, buyAt: '2026-07-06T09:30:00', buyPrice: 66000, evaluatedAt: null, hit: null, sellAt: null }
    ]
    const w = await mountSection(fullHistory, myTrades)
    const myRows = w.findAll('.sh-my-row')
    expect(myRows).toHaveLength(2)
    expect(myRows[0].text()).toContain('내 매수')
    expect(myRows[0].text()).toContain('적중')
    expect(myRows[0].text()).toContain('매도')
    expect(myRows[1].text()).toContain('평가 대기')
    expect(myRows[1].text()).toContain('보유 중')
    // 시그널 이력 행(3) + 내 매매 행(2) 공존
    expect(w.findAll('.sh-row')).toHaveLength(5)

    // 시그널 이력 0건이어도 내 기록이 있으면 섹션 렌더
    const w2 = await mountSection(
      { stockCode: '005930', windowDays: 90,
        summary: { evaluatedCount: 0, hitCount: 0, avgAlpha: null, pendingCount: 0 }, items: [] },
      myTrades)
    expect(w2.findAll('.sh-my-row')).toHaveLength(2)
  })

  it('stockCode 변경 시 재조회', async () => {
    const w = await mountSection(fullHistory)
    expect(apiClient.get).toHaveBeenCalledWith('/stock/005930/signal-history')
    await w.setProps({ stockCode: '000660' })
    await flushPromises()
    expect(apiClient.get).toHaveBeenCalledWith('/stock/000660/signal-history')
  })
})
