import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * AI 전략 화면 — 구조적으로 늘 높은 평균을 '투자 매력도 · 적극 매수'로 말하지 않는다(2026-10-03).
 * 전략 점수는 각 스크리너 상위 5종목 점수의 평균이고 상위는 대개 만점이라 평균의 평균이 늘 80 안팎이었다.
 */
const src = readFileSync(join(process.cwd(), 'src', 'views/AiStrategyDashboardPage.vue'), 'utf8')

describe('AiStrategyDashboardPage', () => {
  it('재현: "AI 종합 투자 매력도"·"적극 매수" 의견이 없다', () => {
    expect(src).not.toMatch(/AI 종합 투자 매력도/)
    expect(src).not.toMatch(/'적극 매수'/)
  })

  it('조회 실패는 0점이 아니라 "-"', () => {
    expect(src).toMatch(/strategyScores\.value = \{ scalping: null, swing: null, turnaround: null, value: null \}/)
    expect(src).toMatch(/strategyScores\.scalping \?\? '-'/)
  })

  it('목표·손절·보유 기간은 전략 고정 규칙이라고 밝힌다', () => {
    expect(src).toMatch(/전략 고정 규칙 — 종목별 예측 아님/)
  })
})
