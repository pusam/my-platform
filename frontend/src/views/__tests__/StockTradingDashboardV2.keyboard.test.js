import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * '오늘' 탭 슬롯(허브가 넣는 시간대 신호·관심종목)도 키보드로 연다(2026-10-01 점검).
 *
 * 허브는 너무 커서 다른 테스트도 마운트하지 않는다(StockTradingDashboardV2.ia.test.js) — 그래서 이 슬롯의
 * 템플릿 원문을 읽어 규약만 확인한다.
 * - role="button" 은 Enter 와 Space 를 모두 받는다(예전엔 Enter 만 — Space 를 누르면 화면이 스크롤됐다).
 * - 관심종목 행은 안에 목표가 입력·버튼이 있어 행 자체를 버튼으로 두면 '버튼 안의 버튼'이 되고, 입력칸에서
 *   Enter 로 저장하면 행의 Enter 처리까지 번져 종목 화면으로 넘어갔다 → 종목명을 진짜 버튼으로 둔다.
 */
const src = readFileSync(join(process.cwd(), 'src/views/StockTradingDashboardV2.vue'), 'utf8')
const todaySlots = src.slice(src.indexOf('<template #phase-signals>'), src.indexOf('</TodayBriefingTab>'))

describe("'오늘' 탭 슬롯 — 키보드로 연다", () => {
  it('슬롯을 찾았다(템플릿 구조가 바뀌면 이 테스트부터 고칠 것)', () => {
    expect(todaySlots.length).toBeGreaterThan(200)
    expect(todaySlots).toContain('<template #watchlist>')
  })

  it('role="button" 요소는 Enter 와 Space 를 모두 받는다', () => {
    const tags = todaySlots.match(/<[a-z][^>]*role="[^"]*button[^"]*"[^>]*>/g) || []
    expect(tags.length).toBeGreaterThan(0)
    for (const tag of tags) {
      expect(tag, tag).toMatch(/@keydown\.enter/)
      expect(tag, tag).toMatch(/@keydown\.space/)
    }
  })

  it('관심종목 행은 버튼이 아니고(안에 입력·버튼이 있다) 종목명이 진짜 버튼이다', () => {
    const row = (todaySlots.match(/<div[^>]*class="wl-row"[^>]*>/) || [''])[0]
    expect(row).not.toBe('')
    expect(row).not.toMatch(/role="button"/)
    expect(row).not.toMatch(/@keydown/)
    expect(todaySlots).toMatch(/<button[^>]*class="wl-name wl-open"/)
  })
})
