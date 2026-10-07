import { describe, it, expect } from 'vitest'
import { toMonthDay, tradeDataLabel, commodityFromResponse, krwFromFuturesQuote, storedPriceLabel, stockNameOnce, signedPercentOrDash, marketTotalOf, impactLabel, impactTone, marketConditionText, topNetSum, latestTradeDay, quoteNumberText } from './marketDataLabels'

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

describe('marketConditionText — 시장 상태 enum 이름 → 문구', () => {
  it('재현: 응답의 condition 은 문자열 — 문구를 돌려준다(예전 화면은 .emoji 로 읽어 빈칸)', () => {
    expect(marketConditionText('NORMAL')).toBe('☁️ 보통')
    expect(marketConditionText('CRASH')).toBe('🚨 폭락/패닉 (관망 필수)')
    expect(marketConditionText('OVERHEATED')).toContain('과열')
  })

  it('모르면 null — 지어내지 않는다', () => {
    expect(marketConditionText(null)).toBeNull()
    expect(marketConditionText('WHATEVER')).toBeNull()
  })
})

describe('topNetSum / latestTradeDay — 시장 탭 수급 패널(2026-10-03)', () => {
  it('재현: 조회 실패·빈 응답은 0 이 아니라 null — 예전엔 "+0억"으로 보였다', () => {
    expect(topNetSum(undefined)).toBeNull()
    expect(topNetSum([])).toBeNull()
    expect(topNetSum([{ netBuyAmount: null }])).toBeNull()
    expect(topNetSum([{ netBuyAmount: 100.5 }, { netBuyAmount: -20 }])).toBeCloseTo(80.5)
  })

  it('데이터의 거래일로 말한다 — 가장 최근 tradeDate', () => {
    expect(latestTradeDay([{ tradeDate: '2026-10-01' }, { tradeDate: '2026-10-02' }])).toBe('10.02')
    expect(latestTradeDay([])).toBeNull()
  })
})

describe('quoteNumberText — 해외 시세 숫자 자릿수(2026-10-06)', () => {
  it('재현: 유로/달러 1.1218 이 1.12 로, 등락 −0.0005 가 0.00 으로 잘렸다 — 10 미만은 넷째 자리까지', () => {
    expect(quoteNumberText(1.1218)).toBe('1.1218')
    expect(quoteNumberText(-0.0005, { signed: true })).toBe('-0.0005')
    expect(quoteNumberText(6.65)).toBe('6.65')
  })

  it('큰 값은 종전처럼 둘째 자리 · 부호 · 천 단위', () => {
    expect(quoteNumberText(7834.5)).toBe('7,834.50')
    expect(quoteNumberText(8.25, { signed: true })).toBe('+8.25')
    expect(quoteNumberText(53.5, { signed: true })).toBe('+53.50')
  })

  it('모르면 \'-\' — 0 으로 그리지 않는다', () => {
    expect(quoteNumberText(null)).toBe('-')
    expect(quoteNumberText(undefined, { signed: true })).toBe('-')
    expect(quoteNumberText('abc')).toBe('-')
  })
})

/**
 * 시장 타이밍 KOSPI·KOSDAQ 카드의 등락비 — 2026-10-07 화면 점검.
 * 재현: 장중 10:16 에 카드가 '당일 등락비 92.0'이라 했는데 16:30 확정치라 10/06 값이었다(같은 화면의 진단 문장은 이미 '코스피
 * 등락비(10/06)'로 고쳐져 있었다 — 10/6 ④). 이름표는 데이터의 날짜로.
 */
describe('dailyRatioLabel — 등락비 이름표는 그 값의 거래일로', () => {
  it('재현: 분석일이 있으면 "등락비(MM/DD)" — "당일"이라고 하지 않는다', async () => {
    const { dailyRatioLabel } = await import('./marketDataLabels')
    expect(dailyRatioLabel('2026-10-06')).toBe('등락비(10/06)')
    expect(dailyRatioLabel('2026-10-06T16:30:00')).toBe('등락비(10/06)')
  })

  it('분석일을 모르면 날짜 없이', async () => {
    const { dailyRatioLabel } = await import('./marketDataLabels')
    expect(dailyRatioLabel(null)).toBe('등락비')
    expect(dailyRatioLabel('')).toBe('등락비')
  })

  it('시장 타이밍 카드가 이 이름표를 쓴다', async () => {
    const { readFileSync } = await import('node:fs')
    const { join } = await import('node:path')
    const src = readFileSync(join(process.cwd(), 'src/views/MarketTimingPage.vue'), 'utf8')
    expect(src).not.toMatch(/<span class="stat-label">당일 등락비<\/span>/)
    expect(src).toMatch(/dailyRatioLabel\(marketData\.analysisDate\)/)
  })
})

/**
 * 시장 탭 수급 패널 — 줄마다 그 값의 시점(2026-10-07 화면 점검).
 * 재현: 10:29 패널이 '외국인 상위 10 +3,930억 · 10.07 기준'이라 했다 — 그 합은 장중 잠정(KIS 순위, 30초 워머)이고, 같은 패널의
 * 연속 순매수는 10.06 장 마감까지 확정치였다. 10시 전엔 기관 순위가 비어 기관 줄만 DB(전일)로 떨어져 한 이름표 아래 날짜가 섞였다.
 */
describe('supplyBasisLabel / consecutiveThroughLabel', () => {
  it('재현: KIS 순위 행(asOf 있음)은 장중이면 "MM.DD HH:mm 장중 잠정"', async () => {
    const { supplyBasisLabel } = await import('./marketDataLabels')
    expect(supplyBasisLabel([
      { tradeDate: '2026-10-07', asOf: '2026-10-07T10:29:44.123' },
      { tradeDate: '2026-10-07', asOf: '2026-10-07T10:29:44.123' }
    ])).toBe('10.07 10:29 장중 잠정')
  })

  it('15:50 이후에 받은 값과 DB 확정치(asOf 없음)는 장 마감 집계', async () => {
    const { supplyBasisLabel } = await import('./marketDataLabels')
    expect(supplyBasisLabel([{ tradeDate: '2026-10-07', asOf: '2026-10-07T16:05:00' }])).toBe('10.07 장 마감 집계')
    expect(supplyBasisLabel([{ tradeDate: '2026-10-06' }])).toBe('10.06 장 마감 집계')
  })

  it('행이 없으면 null', async () => {
    const { supplyBasisLabel } = await import('./marketDataLabels')
    expect(supplyBasisLabel([])).toBeNull()
    expect(supplyBasisLabel(null)).toBeNull()
  })

  it('연속 순매수는 마지막 날까지 — "MM.DD까지"', async () => {
    const { consecutiveThroughLabel } = await import('./marketDataLabels')
    expect(consecutiveThroughLabel([{ endDate: '2026-10-05' }, { endDate: '2026-10-06' }])).toBe('10.06까지')
    expect(consecutiveThroughLabel([])).toBeNull()
    expect(consecutiveThroughLabel([{ endDate: null }])).toBeNull()
  })
})

/**
 * 종목 진단 5일 수급 — 며칠 사고 며칠 팔았는지(2026-10-07 화면 점검).
 * 재현: 삼성전자 기관이 10/1·10/2·10/6 3일 연속 순매수인데 5일 합이 음수라 '(연속 순매도 주의!)'였다.
 */
describe('supplyDaysNote', () => {
  it('재현: 순매수 3일·순매도 2일을 그대로 말한다', async () => {
    const { supplyDaysNote } = await import('./marketDataLabels')
    expect(supplyDaysNote(3, 2)).toBe('(순매수 3일 · 순매도 2일)')
    expect(supplyDaysNote(0, 5)).toBe('(순매도 5일)')
    expect(supplyDaysNote(4, 0)).toBe('(순매수 4일)')
  })

  it('모르면(옛 응답에 매도일이 없음) 아는 것만, 둘 다 없으면 빈 문자열', async () => {
    const { supplyDaysNote } = await import('./marketDataLabels')
    expect(supplyDaysNote(3, undefined)).toBe('(순매수 3일)')
    expect(supplyDaysNote(undefined, undefined)).toBe('')
    expect(supplyDaysNote(0, 0)).toBe('')
  })
})
