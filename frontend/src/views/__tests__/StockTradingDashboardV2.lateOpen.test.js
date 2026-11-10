import { describe, it, expect, vi, afterEach } from 'vitest'

vi.mock('../../utils/api', async (importOriginal) => ({
  ...(await importOriginal()),
  marketAPI: { getStatus: vi.fn() }
}))

import Comp from '../StockTradingDashboardV2.vue'
import { marketAPI } from '../../utils/api'

const M = Comp.methods
/** 2026-11-19(목, 수능일) 09:30 KST — 정규장 10:00, NXT 프리마켓 없음 */
const CSAT_0930 = Date.UTC(2026, 10, 19, 0, 30)
const CSAT_1000 = Date.UTC(2026, 10, 19, 1, 0)
/** 2026-11-18(수) 08:30 KST — 평소 거래일 */
const WED_0830 = Date.UTC(2026, 10, 17, 23, 30)

/**
 * 장이 늦게 열리는 날(2026-11-19 수능일)엔 거래가 열리기 전에 '장 진행 중'이라 하지 않는다(2026-11-10 리허설).
 *
 * 재현: 허브 시간대 국면이 08:00~20:00 을 고정으로 'during'(🟢 장 진행 중 · 실시간 추적 중)이라 했다 — 그날은 NXT 프리마켓도 없어
 * 10:00 에 거래가 열린다. 거래 시작 시각은 백엔드 달력(시장 상태 응답의 tradingStartsAt)이 정한다 — 화면에 두 번째 장 시각표를 두지 않는다.
 */
describe('장이 늦게 열리는 날 — 시간대 국면', () => {
  afterEach(() => { vi.useRealTimers() })

  it('재현: 시장 상태가 오늘 거래 시작을 10:00 이라 하면 09:30 은 장 준비(pre)다', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(CSAT_0930)
    marketAPI.getStatus.mockResolvedValue({ data: { success: true, data: { marketClosedToday: false, tradingStartsAt: '10:00', diagnosis: null } } })
    const ctx = { marketData: {}, closedTodayInfo: null, extractData: M.extractData, noteClosedToday: M.noteClosedToday }

    await M.loadMarketLine.call(ctx)

    expect(Comp.computed.currentPhaseKey.call({ phaseNow: CSAT_0930, ...ctx })).toBe('pre')
    expect(Comp.computed.currentPhaseKey.call({ phaseNow: CSAT_1000, ...ctx })).toBe('during')
  })

  it('평소 거래일(08:00 시작)·시작 시각을 모를 때는 종전대로 08:00 부터 장중', () => {
    const known = { phaseNow: WED_0830, marketData: {}, closedTodayInfo: { date: '2026-11-18', closed: false, startsAt: '08:00' } }
    expect(Comp.computed.currentPhaseKey.call(known)).toBe('during')
    expect(Comp.computed.currentPhaseKey.call({ phaseNow: WED_0830, marketData: {}, closedTodayInfo: null })).toBe('during')
  })

  it('어제 받은 거래 시작 시각은 쓰지 않는다', () => {
    const stale = { phaseNow: WED_0830, marketData: {}, closedTodayInfo: { date: '2026-11-17', closed: false, startsAt: '10:00' } }
    expect(Comp.computed.currentPhaseKey.call(stale)).toBe('during')
  })
})
