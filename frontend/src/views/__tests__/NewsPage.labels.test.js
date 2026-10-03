import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 뉴스 화면 이름표 — 2026-10-03.
 * 재현: 수집기는 Gemini 요약을 뺀 뒤 RSS 원문 앞부분을 그대로 저장하는데(NewsService.fetchAndSummarizeNews), 화면은
 * "AI가 요약한 오늘의 주요 경제 뉴스"·'AI 요약' 배지를 달았고, 수집 시각도 "매일 아침 8시"라고 했다(실제는 평일
 * 07:30 · 장중 15분마다 · 18:00).
 */
const src = readFileSync(join(process.cwd(), 'src/views/NewsPage.vue'), 'utf8')

describe('NewsPage 이름표', () => {
  it('재현: AI 요약이라고 하지 않는다 — RSS 원문이다', () => {
    expect(src).not.toMatch(/AI가 요약한/)
    expect(src).not.toMatch(/>AI 요약</)
    expect(src).toMatch(/RSS 원문/)
  })

  it('재현: 수집 시각은 실제 크론대로', () => {
    expect(src).not.toMatch(/매일 아침 8시/)
    expect(src).toMatch(/평일 07:30/)
  })
})
