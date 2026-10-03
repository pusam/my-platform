import { describe, it, expect } from 'vitest'
import { botSummarySignal, investorReason, withUnifiedChange, signalCodes } from './phaseSignals'

describe('botSummarySignal — 봇 성과는 누적이라고 말한다', () => {
  it('재현: 오늘 결산에 누적 "56승 58패 (승률 49.12%) · 손익비 -"를 오늘 성적처럼 띄웠다', () => {
    const s = botSummarySignal({ totalTrades: 228, winCount: 56, loseCount: 58, winRate: 49.12, todayTrades: 0 })
    expect(s.badge).toBe('🤖 봇 성과(누적)')
    expect(s.stockName).toBe('56승 58패 · 승률 49.12%')
    expect(s.reason).toBe('오늘 매매 없음')
    expect(s.reason).not.toContain('손익비')
  })

  it('오늘 매매가 있으면 그 건수, 매도가 없으면 승률은 "-"(0% 아님)', () => {
    expect(botSummarySignal({ totalTrades: 3, winCount: 0, loseCount: 0, winRate: 0, todayTrades: 3 }))
      .toMatchObject({ stockName: '0승 0패 · 승률 -', reason: '오늘 3건 매매' })
  })

  it('거래가 없거나 응답이 없으면 카드를 만들지 않는다', () => {
    expect(botSummarySignal(null)).toBeNull()
    expect(botSummarySignal({ totalTrades: 0 })).toBeNull()
  })
})

describe('withUnifiedChange — 등락률은 시세 단일 경로 값만', () => {
  const signals = [
    { type: 'investor', stockCode: '009150', changeRate: 1.28 },   // 수급 수집 시각(15:50) 값
    { type: 'ai', stockCode: '373220', changeRate: 2.5 },          // 스냅샷 시각 값
    { type: 'bot', stockCode: null, changeRate: null }
  ]

  it('재현: 같은 화면에서 삼성전기가 +1.28%(외국인 카드) / +1.02%(매수 후보)로 갈렸다 → 단일 경로 값으로', () => {
    const out = withUnifiedChange(signals, { '009150': { changeRate: 1.02 }, '373220': { changeRate: '2.50' } })
    expect(out.map(s => s.changeRate)).toEqual([1.02, 2.5, null])
  })

  it('시세를 못 받으면 옛 값으로 채우지 않고 비운다', () => {
    expect(withUnifiedChange(signals, {}).map(s => s.changeRate)).toEqual([null, null, null])
    expect(withUnifiedChange(signals, { '009150': { changeRate: null } })[0].changeRate).toBeNull()
  })

  it('종목코드 목록은 중복 없이', () => {
    expect(signalCodes([...signals, { stockCode: '009150' }])).toEqual(['009150', '373220'])
  })
})

describe('investorReason — 외국인 순매수에 집계 기준일', () => {
  it('주말·장전엔 지난 거래일 값이라 날짜를 붙인다', () => {
    expect(investorReason({ netBuyAmount: 731.5, tradeDate: '2026-10-02' })).toBe('순매수 731.5억 · 10.02 집계')
    expect(investorReason({ netBuyAmount: 731.5 })).toBe('순매수 731.5억')
  })
})
