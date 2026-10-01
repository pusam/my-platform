import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 주식 화면 4탭 점검 4회차(2026-10-01) — 3회차 배포 뒤 장 마감(15:45) 이후에 다시 재니, 장중엔 없던 상태가 나왔다.
 * 시장 탭 '글로벌'의 코스피200 선물 카드는 장 마감이면 카드 전체를 투명도 0.75 로 흐려 안의 글자가 전부
 * 3.3~4.4 로 떨어졌다(라벨 3.31·등락 3.52·장 마감 배지 3.35). 1·2회차와 같은 원칙 — 흐림은 투명도가 아니라 다른 신호로.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')
const styleOf = (src) => src.slice(src.indexOf('<style')).replace(/\/\*[\s\S]*?\*\//g, '')
const rulesOf = (css, selector) => {
  const out = []
  for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (m[1].split(',').map((s) => s.trim().replace(/\s+/g, ' ')).includes(selector)) out.push(m[2])
  }
  return out.join('\n')
}

describe('시장 탭 — 글로벌(장 마감 상태)', () => {
  const css = styleOf(read('views/GlobalFuturesPage.vue'))

  it('장 마감 카드를 투명도로 흐리지 않는다 — 대신 점선 테두리로 구분한다', () => {
    const rule = rulesOf(css, '.main-card.stale')
    expect(rule).not.toMatch(/opacity/)
    expect(rule).toMatch(/border-style:\s*dashed/)
  })

  it('"장 마감" 배지 글자는 자기 틴트 위에서 한 단계 밝다(#f59e0b 는 흐림을 빼도 4.7 로 빠듯하다)', () => {
    expect(rulesOf(css, '.stale-badge')).not.toMatch(/color:\s*#f59e0b/i)
  })
})
