import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 해외 시세 이름표 — 2026-10-06 화면 점검.
 * 재현: ① 같은 VIX 15.52 가 배지 '보통'인데 눈금 범례는 '경계 (15~25)' ② 등락률을 모르면 '0.00%'(보합)로 그렸다
 * ③ 시장 타이밍의 선물 카드는 등락률이 없으면 Number(null) = 0 으로 '+0.00%'.
 */
const read = (p) => readFileSync(join(process.cwd(), p), 'utf8')
const global = read('src/views/GlobalFuturesPage.vue')
const timing = read('src/views/MarketTimingPage.vue')

describe('GlobalFuturesPage', () => {
  it('재현: VIX 눈금 범례가 배지(공용 기준 15~20 보통)와 같은 말을 한다', () => {
    expect(global).not.toMatch(/<span class="vix-zone-mid">경계 \(15~25\)<\/span>/)
    expect(global).toMatch(/<span class="vix-zone-mid">보통~경계 \(15~25\)<\/span>/)
  })

  it('재현: 모르는 등락률은 0.00 이 아니라 ‘-’', () => {
    expect(global).not.toMatch(/if \(rate == null\) return '0\.00'/)
    expect(global).toMatch(/const formatRate = \(rate\) => signedPercentOrDash\(rate\)/)
    // formatRate 가 % 를 붙이므로 템플릿에서 한 번 더 붙이지 않는다
    expect(global).not.toMatch(/formatRate\([^)]*\) \}\}%/)
  })

  it('가격·차액은 크기에 맞춘 자릿수(quoteNumberText)', () => {
    expect(global).toMatch(/const formatPrice = \(price\) => quoteNumberText\(price\)/)
    expect(global).toMatch(/const formatChange = \(change\) => quoteNumberText\(change, \{ signed: true \}\)/)
  })
})

describe('MarketTimingPage 선물 카드', () => {
  it('재현: 등락률이 없으면 +0.00% 가 아니라 ‘-’', () => {
    expect(timing).not.toMatch(/\{\{ q\.changeRate >= 0 \? '\+' : '' \}\}\{\{ Number\(q\.changeRate\)\.toFixed\(2\) \}\}%/)
    expect(timing).toMatch(/signedPercentOrDash\(q\.changeRate\)/)
  })
})

/**
 * 지난 종가를 지금 값처럼 보이지 않는다 — 2026-10-07 화면 점검.
 * 재현: 한국 시간 10:18 에 글로벌 화면이 VIX 15.01 '-3.29%'·미국 10년물·SOX 를 실시간 선물과 한 표에 같은 모양으로 그렸다 —
 * 미국 현물 지수는 밤에 닫혀 있어 그 등락은 지난밤 것이다. 서버는 이미 stale(마지막 거래 30분 넘게 지남)·dataAgeMinutes 를 준다.
 */
describe('staleQuoteNote — 지난 값이면 얼마나 지났는지', () => {
  it('재현: stale 이면 "N시간 전 값"', async () => {
    const { staleQuoteNote } = await import('../../utils/marketDataLabels')
    expect(staleQuoteNote({ success: true, stale: true, dataAgeMinutes: 312 })).toBe('5시간 전 값')
    expect(staleQuoteNote({ success: true, stale: true, dataAgeMinutes: 45 })).toBe('45분 전 값')
    expect(staleQuoteNote({ success: true, stale: true, dataAgeMinutes: 3000 })).toBe('2일 전 값')
  })

  it('실시간이거나 실패·나이 모름이면 말하지 않는다', async () => {
    const { staleQuoteNote } = await import('../../utils/marketDataLabels')
    expect(staleQuoteNote({ success: true, stale: false, dataAgeMinutes: 2 })).toBe('')
    expect(staleQuoteNote({ success: false, stale: true, dataAgeMinutes: 300 })).toBe('')
    expect(staleQuoteNote({ success: true, stale: true, dataAgeMinutes: 0 })).toBe('')
    expect(staleQuoteNote(null)).toBe('')
  })

  it('VIX·10년물 카드와 전체 시세표가 이 표시를 쓴다', () => {
    expect(global).toMatch(/staleQuoteNote\(vixQuote\)/)
    expect(global).toMatch(/staleQuoteNote\(us10yQuote\)/)
    expect(global).toMatch(/staleQuoteNote\(q\)/)
  })
})
