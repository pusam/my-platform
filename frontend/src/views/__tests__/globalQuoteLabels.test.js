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
