import { describe, it, expect } from 'vitest'
import { toMonthDay, tradeDataLabel, commodityFromResponse, storedPriceLabel, stockNameOnce } from './marketDataLabels'

/**
 * 화면 점검(2026-10-02)에서 나온 이름표 오류들 — 값은 맞는데 시점·단위·이름이 틀리게 붙어 있었다.
 */
describe('tradeDataLabel — 매매 동향 머리말은 데이터의 거래일로', () => {
  it('재현: 장중(10/2 11:24)에 10/1 확정치를 "10.02 11:24 (잠정)"이라 불렀다 → 이제 "10.01 장 마감 집계"', () => {
    const data = {
      FOREIGN: [{ stockCode: '272210', tradeDate: '2026-10-01' }],
      INSTITUTION: [{ stockCode: '000660', tradeDate: '2026-10-01' }]
    }
    expect(tradeDataLabel(data)).toEqual({ text: '10.01 장 마감 집계', status: 'status-confirmed' })
  })

  it('유형마다 날짜가 다르면 가장 최근 거래일', () => {
    const data = { FOREIGN: [{ tradeDate: '2026-09-30' }], PENSION: [{ tradeDate: '2026-10-01' }] }
    expect(tradeDataLabel(data).text).toBe('10.01 장 마감 집계')
  })

  it('행이 없거나 날짜가 없으면 "-" — 지어낸 시각을 붙이지 않는다', () => {
    expect(tradeDataLabel({})).toEqual({ text: '-', status: 'status-unknown' })
    expect(tradeDataLabel(null).text).toBe('-')
    expect(tradeDataLabel({ FOREIGN: [{ stockCode: 'x' }] }).text).toBe('-')
  })
})

describe('commodityFromResponse — 원자재·환율 카드', () => {
  it('재현: 유가는 pricePerBarrel 로 온다 — 예전 화면은 없는 price 를 읽어 "$-"였다', () => {
    const oil = commodityFromResponse({ success: true, data: { pricePerBarrel: 92.76, changeRate: -0.12 } }, 'OIL')
    expect(oil).toEqual({ symbol: 'OIL', name: 'WTI 원유', currentPrice: 92.76, changeRate: -0.12, unit: '$' })
  })

  it('재현: 환율은 감싸지 않은 DTO — 예전 화면은 success 를 기다려 항목이 조용히 빠졌다', () => {
    const krw = commodityFromResponse({ rate: 1358.98, changeRate: -0.06 }, 'KRW')
    expect(krw.currentPrice).toBe(1358.98)
    expect(krw.name).toBe('USD/KRW')
  })

  it('가격이 없으면 칸을 만들지 않는다(null) · 등락률이 없으면 0 이 아니라 null', () => {
    expect(commodityFromResponse({ success: true, data: { changeRate: 1 } }, 'OIL')).toBeNull()
    expect(commodityFromResponse({ success: false, data: { pricePerBarrel: 90 } }, 'OIL')).toBeNull()
    expect(commodityFromResponse({ rate: 1300 }, 'KRW').changeRate).toBeNull()
    expect(commodityFromResponse(null, 'KRW')).toBeNull()
  })
})

describe('저장된 가격·종목 이름', () => {
  it('저장된 가격은 "현재가"가 아니라 그날 종가', () => {
    expect(storedPriceLabel('2026-10-01')).toBe('종가(10.01)')
    expect(storedPriceLabel(null)).toBe('종가')
  })

  it('재현: 이름이 코드로 저장된 기록은 코드를 두 번 쓰지 않는다(예전 "003490003490")', () => {
    expect(stockNameOnce('003490', '003490')).toBe('003490')
    expect(stockNameOnce('', '003490')).toBe('003490')
    expect(stockNameOnce('대한항공', '003490')).toBe('대한항공')
  })

  it('toMonthDay 는 날짜가 아니면 null', () => {
    expect(toMonthDay('2026-10-01')).toBe('10.01')
    expect(toMonthDay('10/01')).toBeNull()
  })
})
