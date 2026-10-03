import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 종목 상세 '트레이딩 지표' 탭 — 실패를 안전으로, 하락을 '+'로 보이지 않는다(2026-10-03).
 * 재현: 글로벌 악재 필터 조회가 실패해 haltCheck 가 비면 '✅ 매수 가능'(초록)이었고, 상위 섹터 등락률은 값과 무관하게
 * 앞에 '+'를 붙여 하락장의 상위 섹터가 '+-0.85%'로 보였다(하위 섹터는 상승이어도 빨강).
 */
const src = readFileSync(join(process.cwd(), 'src/views/TradingIndicatorsPage.vue'), 'utf8')

describe('TradingIndicatorsPage', () => {
  it('재현: 악재 필터를 못 받았으면 확인 불가 — 매수 가능이 아니다', () => {
    expect(src).toMatch(/v-if="!haltCheck"[^>]*>[^<]*확인 불가/)
    expect(src).not.toMatch(/✅ 매수 가능/)
  })

  it('재현: 섹터 등락률은 값의 부호대로 — 앞에 + 를 박아 두지 않는다', () => {
    expect(src).not.toMatch(/>\+\{\{ sector\.averageChangeRate/)
    expect(src).toMatch(/signedPercentOrDash\(sector\.averageChangeRate\)/)
  })
})
