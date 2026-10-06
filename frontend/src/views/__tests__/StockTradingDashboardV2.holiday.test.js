import { describe, it, expect, vi } from 'vitest'

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
    const ctx = { marketData: {}, extractData: M.extractData }
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
