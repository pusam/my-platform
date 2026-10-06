import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { asOfTimeText } from '../../utils/marketDataLabels'

/**
 * 금·은·원유 시세 이름표 — 2026-10-06 화면 점검.
 * 재현: ① '종가' 칸은 백엔드가 조회 시점 가격을 넣은 것(closePrice = 현재가)이라 종가가 아니고, 위 큰 숫자와 같은 값이다
 * ② 기준 시각이 시(時)만 남아 60초마다 갱신되는 원유 시세가 09:41 에도 '오전 9시 기준'이었다.
 */
const read = (p) => readFileSync(join(process.cwd(), p), 'utf8')
const pages = {
  gold: read('src/views/GoldPricePage.vue'),
  silver: read('src/views/SilverPricePage.vue'),
  oil: read('src/views/OilPricePage.vue')
}

describe('금·은·원유 화면', () => {
  it.each(Object.keys(pages))('재현: %s — 조회 시점 가격을 ‘종가’라 하지 않는다', (k) => {
    expect(pages[k]).not.toMatch(/<span class="label">종가/)
  })

  it.each(Object.keys(pages))('%s — 기준 시각은 분까지(asOfTimeText)', (k) => {
    expect(pages[k]).toMatch(/asOfTimeText\(/)
    expect(pages[k]).not.toMatch(/\$\{displayHour\}시 기준/)
  })
})

describe('asOfTimeText', () => {
  it('재현: 09:41 은 ‘오전 9시 기준’이 아니라 분까지', () => {
    expect(asOfTimeText('2026-10-06T09:41:07')).toBe('오전 9:41 기준')
    expect(asOfTimeText('2026-10-06T15:05:00')).toBe('오후 3:05 기준')
    expect(asOfTimeText('2026-10-06T00:10:00')).toBe('오전 12:10 기준')
  })

  it('모르면 빈 문자열', () => {
    expect(asOfTimeText(null)).toBe('')
    expect(asOfTimeText('not-a-date')).toBe('')
  })
})
