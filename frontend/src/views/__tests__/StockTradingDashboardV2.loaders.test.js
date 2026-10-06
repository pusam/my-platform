import { describe, it, expect, vi } from 'vitest'

// 허브 로더가 쓰는 API 만 바꿔 끼운다 — 나머지는 그대로(호출하지 않는다)
vi.mock('../../utils/api', async (importOriginal) => ({
  ...(await importOriginal()),
  marketAPI: { getStatus: vi.fn() },
  sectorAPI: { getSectorTrading: vi.fn() },
  tradingIndicatorAPI: { getNasdaqFutures: vi.fn() },
  investorAPI: { getAllConsecutiveBuy: vi.fn(), getTopTradesRealtime: vi.fn(), getTopTrades: vi.fn() }
}))

import Comp from '../StockTradingDashboardV2.vue'
import { marketAPI, sectorAPI, tradingIndicatorAPI, investorAPI } from '../../utils/api'

const M = Comp.methods
const ok = (data) => ({ data: { success: true, data } })
const STATUS = {
  kospi: { indexClose: 2900, indexChangeRate: 0.5 },
  kosdaq: { indexClose: 850, indexChangeRate: -0.2 },
  combinedAdr: null, diagnosis: '진단'
}
const hub = () => ({
  marketData: {}, sectorData: [], globalData: {}, supplyPanelData: null,
  sections: { marketMap: { loading: false, error: false } },
  extractData: M.extractData, hasSectorData: M.hasSectorData, flattenInvestorMap: M.flattenInvestorMap
})

/**
 * 허브 로더는 받았는지(true/false)를 돌려준다(2026-10-07).
 *
 * 재현: 60초 갱신이 전부 실패해도 갱신 시각이 '방금'으로 바뀌었다(2026-10-04 점검의 남은 LOW) — 로더가 실패를 삼키고 아무것도
 * 돌려주지 않아 허브가 받았는지 알 수 없었다. 응답 모양은 설정(restoreMocks)이 테스트마다 되돌리므로 각 테스트에서 정한다.
 */
describe('시장 한 줄 — 받았는지', () => {
  it('응답을 받으면 true', async () => {
    marketAPI.getStatus.mockResolvedValue(ok(STATUS))
    expect(await M.loadMarketLine.call(hub())).toBe(true)
  })

  it('재현: 실패·빈 응답이면 false', async () => {
    marketAPI.getStatus.mockRejectedValue(new Error('network'))
    expect(await M.loadMarketLine.call(hub())).toBe(false)
    marketAPI.getStatus.mockResolvedValue(ok(null))
    expect(await M.loadMarketLine.call(hub())).toBe(false)
  })
})

describe('시장 지도 — 받았는지', () => {
  it('재현: 세 호출이 다 실패하면 false', async () => {
    sectorAPI.getSectorTrading.mockRejectedValue(new Error('x'))
    marketAPI.getStatus.mockRejectedValue(new Error('x'))
    tradingIndicatorAPI.getNasdaqFutures.mockRejectedValue(new Error('x'))
    const ctx = hub()
    expect(await M.loadMarketMap.call(ctx)).toBe(false)
    expect(ctx.sections.marketMap.loading).toBe(false)
  })

  it('하나라도 화면에 쓸 값이 오면 true', async () => {
    sectorAPI.getSectorTrading.mockRejectedValue(new Error('x'))
    marketAPI.getStatus.mockResolvedValue(ok(STATUS))
    tradingIndicatorAPI.getNasdaqFutures.mockRejectedValue(new Error('x'))
    expect(await M.loadMarketMap.call(hub())).toBe(true)
  })
})

describe('수급 패널 — 받았는지', () => {
  it('재현: 세 호출이 다 실패하면 false', async () => {
    investorAPI.getAllConsecutiveBuy.mockRejectedValue(new Error('x'))
    investorAPI.getTopTradesRealtime.mockRejectedValue(new Error('x'))
    investorAPI.getTopTrades.mockRejectedValue(new Error('x'))
    const ctx = hub()
    expect(await M.loadSupplyPanel.call(ctx)).toBe(false)
    expect(ctx.supplyPanelData.state).toBe('ready')   // 화면은 종전대로 실패를 보인다
  })

  it('빈 순위도 응답이다 — 10시 전 기관 순위는 비는 게 정상', async () => {
    investorAPI.getAllConsecutiveBuy.mockRejectedValue(new Error('x'))
    investorAPI.getTopTradesRealtime.mockResolvedValue(ok([]))
    expect(await M.loadSupplyPanel.call(hub())).toBe(true)
  })
})
