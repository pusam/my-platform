import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 주식 화면 4탭 점검 3회차(2026-10-01). 그라데이션 배경 위 글자는 판정을 보류하던 점검을 고쳐 색 지점마다 재니
 * 그동안 빠져 있던 것이 나왔다 — 흰 배경 HOT 뉴스 카드(제목 대비 1.07), #444 AI 리포트 본문(1.75),
 * 밝은 보라·빨강 그라데이션 위 흰 글자 버튼. 같은 날 시장·발굴 탭 패널 전체가 장중 배지 색(초록 배경·글자)으로
 * 칠해져 있던 것도 찾았다.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')
const styleOf = (src) => src.slice(src.indexOf('<style')).replace(/\/\*[\s\S]*?\*\//g, '')
const templateOf = (src) => src.slice(0, src.indexOf('<script'))
// 선택자 목록(쉼표)에 그 선택자가 들어 있는 규칙 본문을 전부 모은다 — 첫 일치만 보면 묶음 규칙에 걸려 엉뚱한 본문을 본다
const rulesOf = (css, selector) => {
  const out = []
  for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (m[1].split(',').map((s) => s.trim().replace(/\s+/g, ' ')).includes(selector)) out.push(m[2])
  }
  return out.join('\n')
}
const MIN24 = /min-height:\s*(2[4-9]|[3-9]\d)px/
// background-color 는 빼고 글자색 선언만
const TEXT_RED_BLUE = /(^|[;{\s])color:\s*#(ef4444|3b82f6)\b/i

describe('허브 — 장 단계 표시', () => {
  const css = styleOf(read('views/StockTradingDashboardV2.vue'))

  it('장 단계 클래스가 탭 패널 전체를 칠하지 않는다 — 배지용 배경·글자색이 시장·발굴 탭 전체에 번졌다', () => {
    expect(css).not.toMatch(/(^|\n)\s*\.phase-(pre|during|post)\s*\{/)
    expect(rulesOf(css, '.phase-badge.phase-during')).toMatch(/background/)
  })
})

describe('매매 탭 — 주간 리포트', () => {
  const css = styleOf(read('views/PaperTradingPage.vue'))

  it('지표 칸·표 머리는 밝은 배경(#f8f9fa)이 아니다 — 흰 글자 대비 1.08 이었다', () => {
    expect(rulesOf(css, '.weekly-stat')).not.toMatch(/#f8f9fa/i)
    expect(rulesOf(css, '.weekly-history-table th')).not.toMatch(/#f8f9fa/i)
  })

  it('AI 리포트 본문·안내문은 어두운 글자가 아니다(#444 → 1.75)', () => {
    expect(rulesOf(css, '.weekly-ai-body')).not.toMatch(/#444\b/)
    expect(rulesOf(css, '.weekly-no-ai')).not.toMatch(/#92400e/i)
  })

  it('"모의" 배지 글자는 자기 틴트 위에서 밝은 보라다(#667eea 는 계산상 4.07)', () => {
    expect(rulesOf(css, '.weekly-mode-badge')).not.toMatch(/color:\s*#667eea/i)
  })

  it('리포트 생성 버튼은 흰 글자 + 밝은 보라 그라데이션이 아니다(3.66)', () => {
    expect(rulesOf(css, '.btn-generate-report')).not.toMatch(/#667eea/i)
  })

  it('AI 리포트 서식은 순수 함수 하나에서 만든다(### 제목이 원문 그대로 보였다)', () => {
    const src = read('views/PaperTradingPage.vue')
    expect(src).toMatch(/import \{ formatAiReport \} from '(@\/|\.\.\/)utils\/aiReportFormat(\.js)?'/)
    expect(src).not.toMatch(/const formatAiReport\s*=/)
  })
})

describe('매매 탭 — 빨간·초록 버튼', () => {
  const css = styleOf(read('views/PaperTradingPage.vue'))
  const safety = styleOf(read('components/v2/TradingSafetyWidget.vue'))

  it('실전 시작·실전 수동거래·정지 버튼은 흰 글자에 밝은 빨강(#e53e3e, 4.13)을 쓰지 않는다', () => {
    expect(rulesOf(css, '.start-btn.real-btn')).not.toMatch(/#e53e3e/i)
    expect(rulesOf(css, '.real-trade-btn')).not.toMatch(/#e53e3e/i)
    expect(rulesOf(css, '.stop-btn')).not.toMatch(/#e53e3e/i)
  })

  it('실전 수동거래 버튼은 흰 글자를 명시한다 — .trade-btn 의 어두운 글자가 빨강 위에 남았다(3.53)', () => {
    expect(rulesOf(css, '.real-trade-btn')).toMatch(/color:\s*(#fff\b|#ffffff|white)/i)
  })

  it('거래·매도·초기화 창 버튼도 같은 규칙 — 어두운 글자는 #c53030(hover·그라데이션 끝) 위 3.53, 흰 글자는 #e53e3e 4.13 · #3182ce 4.03', () => {
    for (const sel of ['.submit-btn.sell', '.submit-btn.danger', '.submit-btn.real-submit']) {
      expect(rulesOf(css, sel), sel).not.toMatch(/#e53e3e/i)
      expect(rulesOf(css, sel), sel).toMatch(/color:\s*(#fff\b|#ffffff|white)/i)
    }
    expect(rulesOf(css, '.trade-type-buttons button:first-child.active')).not.toMatch(/#e53e3e/i)
    expect(rulesOf(css, '.trade-type-buttons button:last-child.active')).not.toMatch(/#3182ce/i)
  })

  it('비상 정지는 흰 글자에 밝은 빨강(#ef4444, 3.76)으로 시작하지 않는다', () => {
    expect(rulesOf(safety, '.btn-kill')).not.toMatch(/#ef4444/i)
  })

  it('재개 버튼은 밝은 초록 위 흰 글자가 아니다(#22c55e 위 2.28) — 어두운 글자 규약', () => {
    expect(rulesOf(safety, '.btn-resume')).toMatch(/--text-on-accent/)
  })
})

describe('매매 탭 — 수동 매매 표', () => {
  const css = styleOf(read('components/v2/ManualJournalSection.vue'))

  it('표 머리를 투명도로 흐리지 않는다(보조 글자색 × 0.6 = 3.74)', () => {
    expect(rulesOf(css, '.mj-table th')).not.toMatch(/opacity/)
  })

  it('매도 기록 창의 "확정" 버튼은 흰 글자에 밝은 파랑(#3b82f6, 3.68)이 아니다', () => {
    expect(rulesOf(css, '.mj-btn.primary')).not.toMatch(/#3b82f6/i)
  })
})

describe('발굴 탭 — 백테스트', () => {
  const src = read('components/v2/SectionBacktest.vue')
  const css = styleOf(src)
  const template = templateOf(src)

  it('전략 카드는 머리가 펼침 버튼이다(aria-expanded · Enter·Space) — 마우스로만 펼쳐졌다', () => {
    const head = (template.match(/<div[^>]*class="strategy-header"[^>]*>/) || [''])[0]
    expect(head).toMatch(/role="button"/)
    expect(head).toMatch(/tabindex="0"/)
    expect(head).toMatch(/:aria-expanded=/)
    expect(head).toMatch(/@keydown\.enter/)
    expect(head).toMatch(/@keydown\.space/)
  })

  it('펼친 종목 행도 키보드로 연다', () => {
    const row = (template.match(/<div[^>]*class="pick-row"[^>]*>/) || [''])[0]
    expect(row).toMatch(/tabindex="0"/)
    expect(row).toMatch(/@keydown\.enter/)
    expect(row).toMatch(/@keydown\.space/)
  })

  it('적중·수익 배지와 요약 수치는 등락 토큰을 쓴다(#ef4444·#3b82f6 은 틴트 위 3.77~4.33)', () => {
    for (const sel of ['.hit-badge.high', '.return-badge.positive', '.return-badge.negative', '.stat-value.positive', '.stat-value.negative']) {
      expect(rulesOf(css, sel), sel).not.toMatch(/#ef4444|#3b82f6/i)
    }
  })
})

describe('시장 탭 — 시장타이밍', () => {
  const css = styleOf(read('views/MarketTimingPage.vue'))

  it('게이지 눈금·보합 수·빈 막대 문구는 #71717a·#52525b 가 아니다(3.72·2.33·2.16)', () => {
    for (const sel of ['.progress-labels', '.progress-markers .marker', '.unchanged-count', '.empty-bar-overlay', '.empty-bar-message', '.rising-count.no-data']) {
      expect(rulesOf(css, sel), sel).not.toMatch(/#71717a|#52525b/i)
    }
  })

  it('해외 선물 등락은 등락 토큰을 쓴다(#ef4444 4.27)', () => {
    expect(rulesOf(css, '.futures-change.positive')).toMatch(/var\(--stock-up/)
    expect(rulesOf(css, '.futures-change.negative')).toMatch(/var\(--stock-down/)
  })

  it('데이터 수집 버튼은 흰 글자 + 그라데이션이 아니라 액센트 위 어두운 글자다(2.72)', () => {
    expect(rulesOf(css, '.btn-collect')).not.toMatch(/linear-gradient/)
    expect(rulesOf(css, '.btn-collect')).toMatch(/--text-on-accent/)
    expect(rulesOf(css, '.btn-collect-now')).toMatch(/--text-on-accent/)
  })
})

describe('시장 탭 — 글로벌', () => {
  const css = styleOf(read('views/GlobalFuturesPage.vue'))

  it('상승·하락 글자색은 등락 토큰이다 — 보라 카드 위 #ef4444 는 3.82~4.03', () => {
    expect(css).not.toMatch(TEXT_RED_BLUE)
  })

  it('선택된 시세 탭은 흰 글자 + 밝은 보라 그라데이션이 아니다(2.72)', () => {
    expect(rulesOf(css, '.main-tab.active')).not.toMatch(/linear-gradient/)
    expect(rulesOf(css, '.main-tab.active')).toMatch(/--text-on-accent/)
  })

  it('자기 틴트 위 배지는 한 단계 밝은 글자다(장마감 3.42 · 보합 예상 4.14 · KRX 4.05)', () => {
    expect(rulesOf(css, '.market-status-badge.status-closed')).not.toMatch(/--text-muted/)
    expect(rulesOf(css, '.impact-badge.neutral')).not.toMatch(/--text-muted/)
    expect(rulesOf(css, '.main-status.flat')).not.toMatch(/--text-muted/)
    expect(rulesOf(css, '.exchange-badge')).not.toMatch(/color:\s*var\(--primary-start/)
  })

  it('자동 갱신 체크박스 라벨은 24px 이상이다(20px 였다)', () => {
    expect(rulesOf(css, '.auto-toggle')).toMatch(MIN24)
  })

  it('원유 시세는 금·은 시세처럼 다크 테마다 — 흰 카드 위 밝은 글자(기준일 1.0·제목 1.14)였다', () => {
    const oilSrc = read('views/OilPricePage.vue')
    const oil = styleOf(oilSrc)
    expect(rulesOf(oil, '.oil-price-widget')).not.toMatch(/#ffffff|#eaf2f8/i)
    for (const sel of ['.range-item', '.chart-section', '.info-section']) {
      expect(rulesOf(oil, sel), sel).not.toMatch(/background:\s*white/i)
    }
    expect(oil).not.toMatch(/color:\s*#2c3e50/i)
    // 차트 글자(범례·눈금)도 어두운 바탕용 — Chart.js 기본 회색(#666)은 어두운 카드에서 안 보인다
    expect(templateOf(oilSrc) + oilSrc.slice(oilSrc.indexOf('<script'), oilSrc.indexOf('<style'))).toMatch(/ticks:\s*\{[^}]*color:/)
  })
})

describe('시장 탭 — 수급(섹터 펼침)', () => {
  it('펼친 섹터의 종목 등락은 등락 토큰을 쓴다(#EF4444 는 행 배경 위 4.09)', () => {
    const css = styleOf(read('views/SectorTradingPage.vue'))
    expect(rulesOf(css, '.stock-change.positive')).toMatch(/var\(--stock-up/)
    expect(rulesOf(css, '.stock-change.negative')).toMatch(/var\(--stock-down/)
  })
})

describe('시장 탭 — 수급 안의 메인 탭(연속 매수·공매도)', () => {
  // 버튼 클래스(.main-tab-btn)가 달라 앞 회차 측정에서 빠져 있던 화면
  const src = read('views/InvestorAnalysisPage.vue')
  const css = styleOf(src)
  const template = templateOf(src)

  it('연속 매수 카드는 키보드로 연다 — 마우스로만 열렸다', () => {
    const card = (template.match(/<div v-for="stock in currentConsecStocks"[^>]*>/) || [''])[0]
    expect(card).toMatch(/role="button"/)
    expect(card).toMatch(/tabindex="0"/)
    expect(card).toMatch(/@keydown\.enter/)
    expect(card).toMatch(/@keydown\.space/)
  })

  it('공매도 행도 키보드로 연다(데이터가 들어오면 보일 행)', () => {
    const row = (template.match(/<tr v-for="\(s, i\) in shortStocks"[^>]*>/) || [''])[0]
    expect(row).toMatch(/tabindex="0"/)
    expect(row).toMatch(/@keydown\.enter/)
    expect(row).toMatch(/@keydown\.space/)
  })

  it('연속 일수 배지는 흰 글자 + 밝은 보라 그라데이션이 아니다(2.72)', () => {
    expect(rulesOf(css, '.consecutive-badge')).not.toMatch(/linear-gradient/)
    expect(rulesOf(css, '.consecutive-badge')).toMatch(/--text-on-accent/)
  })

  it('빈 상태 안내는 흰색 0.25 가 아니다(2.25), 공매도 표 머리는 밝은 배경이 아니다', () => {
    expect(rulesOf(css, '.no-data .hint')).not.toMatch(/rgba\(255,\s*255,\s*255,\s*0\.25\)/)
    expect(rulesOf(css, '.short-table thead th')).not.toMatch(/#f8f9fa/i)
  })
})

describe('펼치기·접기 버튼은 상태를 알린다(aria-expanded)', () => {
  // 같은 화면의 유튜브 '상세'·수급 '더 보기'는 이미 알린다 — 이 셋만 빠져 있었다
  it('오늘 탭 차트 타이밍 관찰', () => {
    const btn = (templateOf(read('components/v2/TodayBriefingTab.vue')).match(/<button[^>]*class="btn-ghost ts-toggle"[^>]*>/) || [''])[0]
    expect(btn).toMatch(/:aria-expanded="timingExpanded/)
  })

  it('허브 관심종목·차트 패턴', () => {
    const template = templateOf(read('views/StockTradingDashboardV2.vue'))
    const wl = (template.match(/<button[^>]*@click="watchlistExpanded = !watchlistExpanded"[^>]*>/) || [''])[0]
    const cs = (template.match(/<button[^>]*@click="chartSignalsExpanded = !chartSignalsExpanded"[^>]*>/) || [''])[0]
    expect(wl).toMatch(/:aria-expanded="watchlistExpanded/)
    expect(cs).toMatch(/:aria-expanded="chartSignalsExpanded/)
  })
})

describe('시장 탭 — 뉴스', () => {
  const css = styleOf(read('views/NewsPage.vue'))

  it('HOT 카드는 거의 흰 배경이 아니다 — 흰 제목 대비 1.07', () => {
    expect(rulesOf(css, '.news-card--hot')).not.toMatch(/rgba\(255,\s*255,\s*255,\s*0\.9/)
  })

  it('출처 칩·AI 요약 배지·HOT 배지 대비(4.36 · 2.72 · 4.2)', () => {
    expect(rulesOf(css, '.news-source')).not.toMatch(/color:\s*#3b82f6/i)
    expect(rulesOf(css, '.news-badge')).toMatch(/--text-on-accent/)
    expect(rulesOf(css, '.hot-badge')).not.toMatch(/#dc2626/i)
  })
})
