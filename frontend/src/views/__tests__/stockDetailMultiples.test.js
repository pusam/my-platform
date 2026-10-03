import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { multipleOrDash } from '../../utils/marketDataLabels'

/**
 * 종목 상세 '핵심 재무' PER·PBR(2026-10-03).
 * 재현: 값이 없으면 '-배', 0(파싱 실패가 0 으로 저장된 결측 — CLAUDE.md §4c)이면 '0.0배'였다. 'TTM' 배지는 정의가
 * 종목마다 다른데(DART 지배주주 TTM / KIS 연결 TTM / KIS 연간 — per_basis) 늘 TTM 이라 했다.
 */
describe('multipleOrDash', () => {
  it('재현: 없거나 0 이하이면 - (단위 없이)', () => {
    expect(multipleOrDash(null, 1)).toBe('-')
    expect(multipleOrDash(undefined, 2)).toBe('-')
    expect(multipleOrDash(0, 1)).toBe('-')
    expect(multipleOrDash(-3, 1)).toBe('-')
  })
  it('값은 소수 자리대로 배', () => {
    expect(multipleOrDash(12.345, 1)).toBe('12.3배')
    expect(multipleOrDash(0.4, 2)).toBe('0.40배')
  })
})

describe('StockDetailDashboard 핵심 재무', () => {
  const src = readFileSync(join(process.cwd(), 'src/views/StockDetailDashboard.vue'), 'utf8')
  it('재현: PER·PBR 은 multipleOrDash 로 — "-배"·"0.0배" 없음, TTM 단정 없음', () => {
    expect(src).not.toMatch(/financial\?\.per\?\.toFixed\(1\) \|\| '-' \}\}배/)
    expect(src).not.toMatch(/<h2>핵심 재무 <span class="ttm-label">TTM<\/span><\/h2>/)
  })
})
