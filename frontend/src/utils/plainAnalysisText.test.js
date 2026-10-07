import { describe, it, expect } from 'vitest'
import { plainAnalysisText } from './plainAnalysisText'

/**
 * AI 매매 전략 본문 — 마크다운 기호를 걷어 낸 평문(2026-10-07 화면 점검).
 * 재현: 에코프로 상세 'AI 매매 전략'이 '## 에코프로 (086520) 분석 … **■ 종합 판단:** **관망** - …'을 한 덩어리로 보였다 —
 * Gemini 가 준 마크다운을 그대로 {{ }} 로 그렸다(줄바꿈도 사라짐). 렌더링은 계속 평문(v-html 금지).
 */
describe('plainAnalysisText', () => {
  it('재현: 굵게·제목 기호를 지우고 줄은 살린다', () => {
    const md = '## 에코프로 (086520) 분석 및 매매 전략\n**■ 종합 판단:** **관망** - 기술적 지표상 단기 상승\n* **진입:** 90,000원 부근\n- 청산: 95,000원'
    expect(plainAnalysisText(md)).toBe(
      '에코프로 (086520) 분석 및 매매 전략\n■ 종합 판단: 관망 - 기술적 지표상 단기 상승\n• 진입: 90,000원 부근\n• 청산: 95,000원')
  })

  it('곱셈·별표가 섞인 숫자는 건드리지 않는다', () => {
    expect(plainAnalysisText('PER 3 * 2 = 6')).toBe('PER 3 * 2 = 6')
  })

  it('빈 값은 그대로', () => {
    expect(plainAnalysisText(null)).toBe('')
    expect(plainAnalysisText('')).toBe('')
  })
})
