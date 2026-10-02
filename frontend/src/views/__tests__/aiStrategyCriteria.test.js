import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * AI 전략 화면의 '선정 기준' 문구는 백엔드가 실제로 고르는 규칙이어야 한다(2026-10-02 화면 점검).
 * 예전엔 스캘핑 '체결강도 120%+', 스윙 '눌림목', 추세 'VWAP 상단', 가치 '고배당'을 말했지만 그런 조건은 어디에도 없었다.
 */
const src = readFileSync(join(process.cwd(), 'src', 'views/AiStrategyDashboardPage.vue'), 'utf8')
const block = src.slice(src.indexOf('const strategyDescriptions = {'), src.indexOf('const currentStrategy'))

describe('AI 전략 선정 기준 문구', () => {
  it('재현: 없는 조건(체결강도·눌림목·VWAP·고배당)을 말하지 않는다', () => {
    for (const phantom of ['체결강도', '눌림목', 'VWAP', '고배당']) {
      expect(block).not.toContain(phantom)
    }
  })

  it('백엔드 규칙을 말한다 — 거래량 증가율·마법의 공식·턴어라운드·PEG', () => {
    expect(block).toMatch(/거래량 증가율 30%\+/)
    expect(block).toMatch(/마법의 공식\(ROE·영업이익률·PER\)/)
    expect(block).toMatch(/턴어라운드\(흑자전환 · 순이익 급증\)/)
    expect(block).toMatch(/PEG 1\.0 이하 & 순이익 성장률 10%\+/)
  })
})
