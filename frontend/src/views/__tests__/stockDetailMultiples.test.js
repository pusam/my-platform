import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { multipleOrDash, recentMultipleLine, recentMultipleTitle } from '../../utils/marketDataLabels'

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

/**
 * PER·PBR 아래 '최근 실적 기준' 줄(2026-10-07, 사용자 결정 '둘 다 표시').
 * 재현(10/7 운영 삼성전자): 상세 PER 41.4·PBR 4.25 는 KIS 최근 결산(연간) 기준이고, 점수·목록이 쓰는 값은 최근 4분기 이익 기준
 * PER 10.6·PBR 2.81 — 같은 회사가 화면마다 4배 비싸 보였고, 툴팁은 상세 값이 종목별로 TTM 일 수 있다고 잘못 안내했다.
 */
describe('recentMultipleLine', () => {
  const samsung = { per: 41.4, pbr: 4.25, perRecent: 10.6, pbrRecent: 2.81, recentBasis: 'CTRL', recentAsOf: '2026-10-07' }
  it('재현: 결산 기준 값 아래에 최근 실적 기준 값을 기준과 함께', () => {
    expect(recentMultipleLine(samsung, 'per')).toBe('결산 기준 · 최근 4분기 10.6배')
    expect(recentMultipleLine(samsung, 'pbr')).toBe('결산 기준 · 최근 분기 2.81배')
    expect(recentMultipleTitle(samsung)).toContain('DART 지배주주')
    expect(recentMultipleTitle(samsung)).toContain('2026-10-07')
  })
  it('비지배 포함 연결이면 그렇게 적고, 결산 값이 없으면 앞말 없이', () => {
    const consol = { per: null, pbr: 2.08, perRecent: null, pbrRecent: 2.08, recentBasis: 'CONSOL' }
    expect(recentMultipleLine(consol, 'per')).toBeNull()
    expect(recentMultipleLine(consol, 'pbr')).toBe('결산 기준 · 최근 분기 2.08배 · 연결')
    expect(recentMultipleLine({ per: 0, perRecent: 10.6, recentBasis: 'CTRL' }, 'per')).toBe('최근 4분기 10.6배')
    expect(recentMultipleTitle(consol)).toContain('비지배지분 포함')
  })
  it('모르면 줄을 숨긴다 — 0·없음·재무 없음', () => {
    expect(recentMultipleLine(null, 'per')).toBeNull()
    expect(recentMultipleLine({ per: 41.4, perRecent: 0 }, 'per')).toBeNull()
    expect(recentMultipleLine({ per: 41.4 }, 'per')).toBeNull()
    expect(recentMultipleTitle({ per: 41.4 })).toBeNull()
  })
  it('화면: 두 카드가 이 줄을 쓰고, 틀린 툴팁("종목별로 … 중 하나")은 없다', () => {
    const src = readFileSync(join(process.cwd(), 'src/views/StockDetailDashboard.vue'), 'utf8')
    expect(src).toContain("recentMultipleLine(financial, 'per')")
    expect(src).toContain("recentMultipleLine(financial, 'pbr')")
    expect(src).not.toContain('PER·PBR 기준은 종목별로 DART 지배주주 TTM · KIS 연결 TTM · KIS 연간 중 하나입니다')
  })
})
