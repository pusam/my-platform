import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 주식 화면 4탭 점검 2회차(2026-10-01) — 1회차 배포 뒤 다시 재니 그때는 화면에 없던 상태에서 나왔다
 * (매수 후보가 생겨 종목명 버튼이 보였고, 보드에 행이 채워졌다). 같은 모양이 다른 상태에서 나올 곳도 같이 막는다.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')
const styleOf = (src) => src.slice(src.indexOf('<style'))
const ruleOf = (css, selector) => {
  const i = css.indexOf(selector + ' {')
  return i < 0 ? '' : css.slice(i, css.indexOf('}', i))
}
const MIN24 = /min-height:\s*(2[4-9]|[3-9]\d)px/

describe('오늘 탭 — 매수 후보 종목명 버튼', () => {
  it('24px 이상이다 — 후보가 생기자 22px 로 잡혔다', () => {
    expect(ruleOf(styleOf(read('components/v2/TodayBriefingTab.vue')), '.cc-open')).toMatch(MIN24)
  })
})

describe('발굴 탭 — 종합판단 보드', () => {
  const css = styleOf(read('components/v2/SectionJudgmentBoard.vue'))

  it('종목코드·"/등락"을 투명도로 흐리지 않는다(대비 4.1·3.99 였다)', () => {
    expect(ruleOf(css, '.rc')).not.toMatch(/opacity/)
    expect(ruleOf(css, '.th-price small')).not.toMatch(/opacity/)
  })

  it('"현재 산식" 배지는 자기 색 틴트 위에서 한 단계 밝은 글자를 쓴다(#94a3b8 4.21 였다)', () => {
    expect(ruleOf(css, '.unv-badge')).not.toMatch(/color:\s*#94a3b8/)
  })

  it('표본부족 이력 칸도 투명도로 흐리지 않는다 — 이력이 채워지면 보일 칸(#64748b × 0.7 은 계산상 미달)', () => {
    expect(ruleOf(css, '.td-track.track-insufficient')).not.toMatch(/opacity/)
  })

  it('체크박스 라벨(누르는 영역)은 24px 이상이다 — 21px 였다', () => {
    expect(ruleOf(css, '.jb-filters label')).toMatch(MIN24)
  })
})
