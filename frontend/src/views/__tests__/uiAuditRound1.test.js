import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 주식 화면 4탭 점검 1회차(2026-10-01) — 운영 화면을 375px·1280px 에서 재서 나온 것만 고정한다.
 * 기준: 글자 대비 4.5 미만 · 24px 미만 터치 영역 · 마우스로만 열리는 요소. 매수·매도 색(4.19~4.3)은
 * 한국 관례 단일 출처라 의도된 예외로 둔다.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')
const styleOf = (src) => src.slice(src.indexOf('<style'))
const ruleOf = (css, selector) => {
  const i = css.indexOf(selector + ' {')
  return i < 0 ? '' : css.slice(i, css.indexOf('}', i))
}

describe('오늘 탭 — 차트 타이밍 관찰', () => {
  const css = styleOf(read('components/v2/TodayBriefingTab.vue'))

  it('경고 문구를 투명도로 흐리지 않는다 — 섹션 0.82 × 문장 0.72 가 겹쳐 "31%" 대비가 3.01 이었다', () => {
    expect(ruleOf(css, '.today-observe')).not.toMatch(/opacity/)
    expect(ruleOf(css, '.observe-collapsed')).not.toMatch(/opacity/)
  })

  it('"펼치기" 버튼은 24px 이상이다(WCAG 2.5.8)', () => {
    expect(ruleOf(css, '.ts-toggle')).toMatch(/min-height:\s*(2[4-9]|[3-9]\d)px/)
  })
})

describe('허브 — 발굴·시장 표기', () => {
  const src = read('views/StockTradingDashboardV2.vue')

  it('"(베타)" 같은 보조 글자를 인라인 투명도로 흐리지 않는다 — 대비 2.66 이었다', () => {
    const template = src.slice(0, src.indexOf('<script'))
    expect(template).not.toMatch(/style="opacity:\s*0?\.\d+"/)
  })

  it('강세 섹터 종목 칩은 24px 이상이다', () => {
    expect(ruleOf(styleOf(src), '.ss-stock')).toMatch(/min-height:\s*(2[4-9]|[3-9]\d)px/)
  })
})

describe('시장 탭 — 키보드로 연다', () => {
  it('섹터 카드는 머리가 펼침 버튼이다(aria-expanded · Enter·Space) — 카드 전체가 마우스로만 열렸다', () => {
    const src = read('views/SectorTradingPage.vue')
    const head = (src.match(/<div class="sector-header"[^>]*>/) || [''])[0]
    expect(head).toMatch(/role="button"/)
    expect(head).toMatch(/tabindex="0"/)
    expect(head).toMatch(/:aria-expanded=/)
    expect(head).toMatch(/@keydown\.enter/)
    expect(head).toMatch(/@keydown\.space/)
  })

  it('펼친 섹터의 종목 행도 키보드로 연다', () => {
    const src = read('views/SectorTradingPage.vue')
    const row = (src.match(/<div v-for="stock in sector\.topStocks"[^>]*>/) || [''])[0]
    expect(row).toMatch(/tabindex="0"/)
    expect(row).toMatch(/@keydown\.enter/)
    expect(row).toMatch(/@keydown\.space/)
  })

  it('투자자 수급 거래 행도 키보드로 연다', () => {
    const src = read('views/InvestorAnalysisPage.vue')
    const row = (src.match(/<tr v-for="\(trade, index\) in currentTrades"[^>]*>/) || [''])[0]
    expect(row).toMatch(/tabindex="0"/)
    expect(row).toMatch(/@keydown\.enter/)
    expect(row).toMatch(/@keydown\.space/)
  })
})
