import { describe, it, expect, vi, afterEach } from 'vitest'

// 시장 상태 응답만 바꿔 끼운다 — 나머지 API 는 그대로(호출하지 않는다)
vi.mock('../../utils/api', async (importOriginal) => ({
  ...(await importOriginal()),
  marketAPI: { getStatus: vi.fn() }
}))

import Comp from '../StockTradingDashboardV2.vue'
import { marketAPI } from '../../utils/api'

const M = Comp.methods
/** 2026-10-08(목) 10:00 KST — 평일 장중 시각 */
const THU_10AM = Date.UTC(2026, 9, 8, 1, 0)

/**
 * 평일 공휴일엔 '장 진행 중'이라고 하지 않는다(2026-10-07).
 *
 * 재현: 허브 시간대 배너가 주말만 알아서 10/9(금, 한글날) 같은 평일 공휴일엔 08~20시 내내 '🟢 장 진행 중 · 실시간 추적 중'이었다.
 * 휴장 판정은 백엔드 달력 한 곳(시장 상태 응답의 marketClosedToday) — 화면에 두 번째 달력을 두지 않는다. 휴장일은 주말과 같은 국면(post).
 */
describe('평일 공휴일 — 시간대 국면', () => {
  it('재현: 시장 상태가 오늘 휴장이라고 하면 평일 장중 시각이어도 장 진행 중(during)이 아니다', () => {
    const key = Comp.computed.currentPhaseKey.call({ phaseNow: THU_10AM, marketData: { marketClosedToday: true } })
    expect(key).toBe('post')
  })

  it('거래일이면 종전대로 장중', () => {
    expect(Comp.computed.currentPhaseKey.call({ phaseNow: THU_10AM, marketData: { marketClosedToday: false } })).toBe('during')
    expect(Comp.computed.currentPhaseKey.call({ phaseNow: THU_10AM, marketData: {} })).toBe('during')
  })

  it('재현: 시장 상태 응답의 휴장 판정이 화면 데이터로 넘어온다', async () => {
    const ctx = { marketData: {}, extractData: M.extractData, closedTodayInfo: null, noteClosedToday: M.noteClosedToday }
    // 설정(restoreMocks)이 테스트마다 목을 되돌리므로 응답은 여기서 정한다
    marketAPI.getStatus.mockResolvedValue({
      data: {
        success: true,
        data: {
          kospi: { indexClose: 6900, indexChangeRate: 0.5 },
          kosdaq: { indexClose: 920, indexChangeRate: -0.2 },
          combinedAdr: null,
          diagnosis: '휴장일 진단',
          marketClosedToday: true
        }
      }
    })
    await M.loadMarketLine.call(ctx)
    expect(ctx.marketData.marketClosedToday).toBe(true)
    expect(ctx.marketData.kospiIndex).toBeTruthy()
  })
})

/**
 * 휴장 판정은 지워지지 않는다(2026-10-19, 리뷰 에이전트 지적).
 *
 * 재현: 판정을 시장 데이터 객체에만 두어 ① 페이지를 연 직후 상태 응답이 오기 전 ② 60초 갱신에서 상태 조회가 실패해 객체를 비운 뒤
 * ③ 지수가 없는 빈 진단(createEmptyTiming)이 온 뒤엔 평일 공휴일에 다시 '장 진행 중'이었고, 국면 데이터(오늘 결산의 봇 성과 줄)는
 * 처음 한 번 'during' 으로 읽힌 채였다. 발굴 탭을 열어 둔 채 날짜가 바뀌면 시장 상태를 다시 읽지 않아 전날 판정이 남았다.
 */
describe('평일 공휴일 — 판정이 지워지지 않는다', () => {
  afterEach(() => { vi.useRealTimers() })

  it('재현: 상태 조회가 실패해 시장 데이터를 비워도 오늘 휴장 판정은 남는다', () => {
    const key = Comp.computed.currentPhaseKey.call({
      phaseNow: THU_10AM, marketData: {}, closedTodayInfo: { date: '2026-10-08', closed: true }
    })
    expect(key).toBe('post')
  })

  it('재현: 지수가 없는 빈 진단이어도 휴장 판정을 받는다', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(THU_10AM)
    marketAPI.getStatus.mockResolvedValue({ data: { success: true, data: { marketClosedToday: true, diagnosis: null } } })
    const ctx = { marketData: {}, closedTodayInfo: null, extractData: M.extractData, noteClosedToday: M.noteClosedToday }

    await M.loadMarketLine.call(ctx)

    expect(ctx.closedTodayInfo).toEqual({ date: '2026-10-08', closed: true })
    expect(Comp.computed.currentPhaseKey.call({ phaseNow: THU_10AM, ...ctx })).toBe('post')
  })

  it('어제 받은 휴장 판정은 오늘엔 쓰지 않는다', () => {
    const key = Comp.computed.currentPhaseKey.call({
      phaseNow: THU_10AM, marketData: {}, closedTodayInfo: { date: '2026-10-07', closed: true }
    })
    expect(key).toBe('during')
  })

  it('재현: 국면이 바뀌면(휴장 판정이 늦게 와도) 국면 데이터를 다시 읽는다', async () => {
    const ctx = { loadPhaseData: vi.fn().mockResolvedValue(), phaseLoading: false }
    await Comp.watch.currentPhaseKey.call(ctx, 'post', 'during')
    expect(ctx.loadPhaseData).toHaveBeenCalledTimes(1)
    await Comp.watch.currentPhaseKey.call(ctx, 'post', 'post')
    expect(ctx.loadPhaseData).toHaveBeenCalledTimes(1)
  })

  it('재현: 날짜가 바뀌어 오늘 판정이 없으면 시장 상태를 한 번 묻는다(발굴 탭처럼 상태를 다시 읽지 않는 화면)', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(THU_10AM)
    marketAPI.getStatus.mockResolvedValue({ data: { success: true, data: { marketClosedToday: false } } })
    const ctx = { closedTodayInfo: { date: '2026-10-07', closed: true }, extractData: M.extractData, noteClosedToday: M.noteClosedToday }

    await M._refreshClosedTodayIfStale.call(ctx)
    await M._refreshClosedTodayIfStale.call(ctx)

    expect(marketAPI.getStatus).toHaveBeenCalledTimes(1)
    expect(ctx.closedTodayInfo).toEqual({ date: '2026-10-08', closed: false })
  })
})
