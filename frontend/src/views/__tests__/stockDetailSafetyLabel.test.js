import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 종목 상세 '안전 점수' — 이름과 설명을 계산대로(2026-10-03).
 * 재현: 값은 진단 종합점수 − 리스크×0.3 인데 '안전 점수'라 불렀고(안전성을 잰 값이 아니다), 설명문은 특별한 경우가
 * 아니면 점수와 무관하게 "리스크가 높아 신중한 판단이 필요합니다"였다(80점 초록 게이지에도).
 */
const src = readFileSync(join(process.cwd(), 'src/views/StockDetailDashboard.vue'), 'utf8')

describe('StockDetailDashboard 안전 점수', () => {
  it('재현: 이름이 계산을 말한다 — 진단 종합점수(리스크 감점)', () => {
    expect(src).not.toMatch(/<h2>안전 점수<\/h2>/)
    expect(src).toMatch(/진단 점수 \(리스크 감점\)/)
  })

  it('재현: 기본 설명이 점수와 무관하게 "리스크가 높아"가 아니다 — 점수 구간대로', () => {
    expect(src).not.toMatch(/return '리스크가 높아 신중한 판단이 필요합니다\.'/)
    expect(src).toMatch(/if \(safety >= 65\) return '큰 감점 요인은 없습니다/)
  })
})
