import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import AIStrategyCard from './AIStrategyCard.vue'

const baseAi = {
  overallScore: 80,
  recommendation: 'BUY',
  technicalSignal: '상승 추세',
  strategy: '분할 매수 권장',
  chartSignals: [{ code: 'GC', label: '골든크로스', tone: 'positive', detail: '5일선이 20일선 상향돌파' }],
  chartAnalysis: 'AI 차트 해석 본문',
  priceGuide: '7만원 지지 확인 후 진입',
  consensusTargetPrice: 100000,
  targetUpside: 25.5,
  consensusSource: '증권사 5곳',
  buyReasons: ['실적 개선', '수급 양호', '외국인 매수', '4번째(잘림)'],
  sellReasons: ['단기 과열']
}

function mountCard(props = {}) {
  return mount(AIStrategyCard, {
    props: { aiAnalysis: baseAi, diagnosisData: null, currentPrice: 80000, ...props }
  })
}

describe('AIStrategyCard (P-IA ③ 후속 분리)', () => {
  it('recommendation → strategy-box 클래스 + 라벨/시그널/전략 텍스트', () => {
    const w = mountCard()
    expect(w.find('.strategy-box').classes()).toContain('buy')
    expect(w.find('.strategy-rec').text()).toBe('BUY')
    expect(w.find('.strategy-signal').text()).toBe('상승 추세')
    expect(w.find('.strategy-text').text()).toBe('분할 매수 권장')
  })

  it('차트 시그널 칩 + AI 차트 해석 렌더', () => {
    const w = mountCard()
    const chip = w.find('.chip')
    expect(chip.text()).toBe('골든크로스')
    expect(chip.classes()).toContain('chip-positive')
    expect(w.find('.chart-analysis-body').text()).toBe('AI 차트 해석 본문')
  })

  it('목표주가 컨센서스: 목표가/상승여력 + 바 너비(현재가/목표가)', () => {
    const w = mountCard()
    expect(w.find('.consensus-price').text()).toContain('100,000')
    expect(w.find('.consensus-upside').text()).toContain('+25.5%')
    expect(w.find('.consensus-upside').classes()).toContain('positive')
    // 80000/100000 = 80%
    expect(w.find('.consensus-bar-current').attributes('style')).toContain('width: 80%')
  })

  it('매수/매도 근거 최대 3개 슬라이스', () => {
    const w = mountCard()
    expect(w.findAll('.buy-reasons li')).toHaveLength(3)
    expect(w.findAll('.sell-reasons li')).toHaveLength(1)
  })

  it('scoreDiffComment: 단기≫중장기 차이 클 때 노출', () => {
    const w = mountCard({ diagnosisData: { overallScore: 50 } })  // 80 > 50+20
    expect(w.find('.score-diff-comment').exists()).toBe(true)
    expect(w.find('.score-diff-comment').text()).toContain('단기 모멘텀은 강하나')
  })

  it('scoreDiffComment: diagnosisData 없으면 미노출', () => {
    const w = mountCard({ diagnosisData: null })
    expect(w.find('.score-diff-comment').exists()).toBe(false)
  })

  it('recommendation SELL → strategy-box.sell', () => {
    const w = mountCard({ aiAnalysis: { ...baseAi, recommendation: 'SELL' } })
    expect(w.find('.strategy-box').classes()).toContain('sell')
    expect(w.find('.strategy-rec').text()).toBe('SELL')
  })

  it('컨센서스 목표가 없으면 컨센서스 섹션 미노출', () => {
    const w = mountCard({ aiAnalysis: { ...baseAi, consensusTargetPrice: null } })
    expect(w.find('.consensus-section').exists()).toBe(false)
  })
})

// 2026-10-07 화면 점검(에코프로): Gemini 마크다운이 '## …'·'**■ 종합 판단:**' 그대로 한 덩어리로 보였다
describe('AIStrategyCard — 전략 본문은 마크다운 기호 없는 평문', () => {
  it('재현: ##·** 를 보이지 않고 줄을 살린다', () => {
    const w = mountCard({ aiAnalysis: { ...baseAi,
      strategy: '## 에코프로 (086520) 분석\n**■ 종합 판단:** **관망**\n* **진입:** 90,000원 부근' } })
    const text = w.find('.strategy-text').text()
    expect(text).not.toContain('**')
    expect(text).not.toContain('## ')
    expect(text).toContain('■ 종합 판단: 관망')
    expect(text).toContain('• 진입: 90,000원 부근')
    // 평문 렌더링 유지(v-html 금지) — 태그 문자열은 글자로 남는다
    const injected = mountCard({ aiAnalysis: { ...baseAi, strategy: '<b>굵게</b>' } })
    expect(injected.find('.strategy-text').text()).toBe('<b>굵게</b>')
  })
})
