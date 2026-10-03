import { describe, it, expect } from 'vitest'
import { toMonthDay, tradeDataLabel, commodityFromResponse, krwFromFuturesQuote, storedPriceLabel, stockNameOnce, signedPercentOrDash, marketTotalOf, impactLabel, impactTone } from './marketDataLabels'

describe('marketTotalOf — 섹터 총 거래대금은 종목을 한 번만 센다', () => {
  it('재현: 섹터 합계를 더하면 겹치는 종목이 두 번 — 백엔드 marketTotalTradingValue 를 쓴다', () => {
    const sectors = [
      { totalTradingValue: 100, marketTotalTradingValue: 110 },
      { totalTradingValue: 70, marketTotalTradingValue: 110 }
    ]
    expect(marketTotalOf(sectors)).toBe(110)   // 예전 합산이면 170
  })

  it('필드가 없는 옛 응답은 종전처럼 합산, 빈 목록은 0', () => {
    expect(marketTotalOf([{ totalTradingValue: '5' }, { totalTradingValue: 7 }])).toBe(12)
    expect(marketTotalOf([])).toBe(0)
    expect(marketTotalOf(null)).toBe(0)
  })
})

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

  it('가격이 없으면 칸을 만들지 않는다(null) · 등락률이 없으면 0 이 아니라 null', () => {
    expect(commodityFromResponse({ success: true, data: { changeRate: 1 } }, 'OIL')).toBeNull()
    expect(commodityFromResponse({ success: false, data: { pricePerBarrel: 90 } }, 'OIL')).toBeNull()
    expect(commodityFromResponse({ success: true, data: { pricePerBarrel: 90 } }, 'OIL').changeRate).toBeNull()
    expect(commodityFromResponse(null, 'OIL')).toBeNull()
  })
})

describe('krwFromFuturesQuote — USD/KRW 는 글로벌 시세 한 곳에서', () => {
  it('재현: /exchange-rate 는 키·폴백이 다 죽어 늘 비었다 — 이미 같은 화면 요약이 쓰는 KRW 시세(10/2 실측 모양)를 쓴다', () => {
    const q = { symbol: 'KRW', currentPrice: 1357.08, changeRate: -0.20, success: true, stale: false }
    expect(krwFromFuturesQuote(q)).toEqual({ symbol: 'KRW', name: 'USD/KRW', currentPrice: 1357.08, changeRate: -0.2, unit: '원' })
  })

  it('시세가 없거나 실패면 칸을 만들지 않는다 · 등락률 결측은 null', () => {
    expect(krwFromFuturesQuote(null)).toBeNull()
    expect(krwFromFuturesQuote({ success: false, currentPrice: 1300 })).toBeNull()
    expect(krwFromFuturesQuote({ success: true })).toBeNull()
    expect(krwFromFuturesQuote({ success: true, currentPrice: 1300 }).changeRate).toBeNull()
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

  it('재현: 금·은 등락률은 출처가 안 줘서 늘 0.00% 였다 — 이제 없으면 "-"(null 을 그대로 그리면 "%"만 남는다)', () => {
    expect(signedPercentOrDash(null)).toBe('-')
    expect(signedPercentOrDash(undefined)).toBe('-')
    expect(signedPercentOrDash('')).toBe('-')
    expect(signedPercentOrDash(0)).toBe('0.00%')      // 진짜 0 은 0 — 결측과 다르다
    expect(signedPercentOrDash(1.234)).toBe('+1.23%')
    expect(signedPercentOrDash(-0.5)).toBe('-0.50%')
  })

  it('toMonthDay 는 날짜가 아니면 null', () => {
    expect(toMonthDay('2026-10-01')).toBe('10.01')
    expect(toMonthDay('10/01')).toBeNull()
  })
})

describe('impactLabel / impactTone — 종합 시장 방향성 등급(백엔드 alertLevel 단일 출처, 2026-10-03)', () => {
  it('재현: 해외 시세를 못 받은 UNKNOWN 은 판단 보류 — 보합 예상이 아니다', () => {
    expect(impactLabel('UNKNOWN')).toBe('판단 보류')
    expect(impactLabel(undefined)).toBe('판단 보류')
    expect(impactTone('UNKNOWN')).toBe('unknown')
  })

  it('등급 문구는 글로벌 화면과 시장 타이밍 배너가 같다 — 점수로 다시 나누지 않는다', () => {
    expect(impactLabel('WEAK_POSITIVE')).toBe('소폭 강세')
    expect(impactLabel('NEUTRAL')).toBe('보합 예상')
    expect(impactLabel('CRISIS')).toBe('폭락 경계')
    expect(impactTone('WEAK_POSITIVE')).toBe('positive')
    expect(impactTone('CRISIS')).toBe('negative')
    expect(impactTone('NEUTRAL')).toBe('neutral')
  })
})
