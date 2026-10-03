import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 금·은 '최근 한 달 시세 추이'는 저장된 이력으로만 그린다(2026-10-03) — 예전엔 조회 실패 시 현재가 ±5% 난수로 30일치를 그렸다.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')

describe.each(['views/GoldPricePage.vue', 'views/SilverPricePage.vue'])('%s', (page) => {
  const src = read(page)

  it('재현: 난수로 이력을 만들지 않는다', () => {
    expect(src).not.toMatch(/Math\.random/)
    expect(src).not.toMatch(/generateMonthlyData/)
  })

  it('이력 없음·조회 실패를 다른 문구로 말한다', () => {
    expect(src).toMatch(/chartState === 'empty'/)
    expect(src).toMatch(/chartState === 'error'/)
  })
})
