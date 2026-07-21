import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StockBriefingHeadline from './StockBriefingHeadline.vue'

// 백엔드 StockDiagnosisDto.SupplyDemandDto 직렬화 키 = foreignNet5Days / institutionNet5Days.
function mountHeadline(supplyDemand, over = {}) {
  return mount(StockBriefingHeadline, {
    props: {
      diagnosisData: { overallScore: 55, technicalAnalysis: { rsi14: 50 }, supplyDemand, ...over },
      aiAnalysis: { overallScore: 60, recommendation: 'HOLD' }
    }
  })
}

describe('StockBriefingHeadline — 수급 키 정합', () => {
  it('회귀: 기관 순매도를 institutionNet5Days 키로 읽어 "외인·기관 동반 매도" 경고가 뜬다', () => {
    // 외인·기관 둘 다 순매도 → isSupplyNegative=true. 기관을 구 오타 키(instNet5Days)로 읽으면
    // instNet=null 이라 동반매도 판정이 절대 성립 안 함(경고 누락 버그).
    const w = mountHeadline({ foreignNet5Days: -50, institutionNet5Days: -30 })
    expect(w.find('.rec-cautions').text()).toContain('외인·기관 동반 매도')
  })

  it('기관만 순매수여도 supplyPos 성립(institutionNet5Days) — 동반매도 경고 없음', () => {
    const w = mountHeadline({ foreignNet5Days: -10, institutionNet5Days: 40 })
    expect(w.text()).not.toContain('외인·기관 동반 매도')
  })
})

// recommendation 사다리 5분기 — 이 컴포넌트의 주 출력. 우선순위(회피 > 적극 > 선별 > 수급회피 > 관망)와
// 각 분기의 성립 조건을 고정한다.
function mountFull({ fund = 55, rsi = 50, aiRec = 'HOLD', foreign = null, inst = null, warnings } = {}) {
  return mount(StockBriefingHeadline, {
    props: {
      diagnosisData: {
        overallScore: fund,
        technicalAnalysis: { rsi14: rsi },
        supplyDemand: { foreignNet5Days: foreign, institutionNet5Days: inst },
        ...(warnings ? { warnings } : {})
      },
      aiAnalysis: { overallScore: 60, recommendation: aiRec }
    }
  })
}

describe('StockBriefingHeadline — recommendation 사다리', () => {
  it('데이터 전무 → 분석 중', () => {
    const w = mount(StockBriefingHeadline, { props: { diagnosisData: null, aiAnalysis: null } })
    expect(w.find('.rec-label').text()).toBe('분석 중')
  })

  it('회피가 최우선 — 펀더멘털·AI·수급 전부 긍정이어도 AI SELL 이면 회피', () => {
    const w = mountFull({ fund: 80, aiRec: 'SELL', foreign: 100, inst: 50 })
    expect(w.find('.rec-label').text()).toBe('회피')
    expect(w.find('.rec-reason').text()).toContain('AI 매도')
  })

  it('펀더멘털 부진(<40) → 회피 (AI BUY 여도)', () => {
    const w = mountFull({ fund: 35, aiRec: 'BUY', foreign: 100 })
    expect(w.find('.rec-label').text()).toBe('회피')
    expect(w.find('.rec-reason').text()).toContain('펀더멘털 부진')
  })

  it('적극 매수 — 펀더멘털≥65 + AI BUY + 수급 양수 + 비과열', () => {
    const w = mountFull({ fund: 70, aiRec: 'BUY', foreign: 100, inst: 20, rsi: 60 })
    expect(w.find('.rec-label').text()).toBe('적극 매수')
  })

  it('RSI≥75 과열이면 적극 매수 대신 선별 매수 + 분할 매수 문구', () => {
    const w = mountFull({ fund: 70, aiRec: 'BUY', foreign: 100, rsi: 78 })
    expect(w.find('.rec-label').text()).toBe('선별 매수')
    expect(w.find('.rec-reason').text()).toContain('분할 매수')
  })

  it('수급 이탈(외인·기관 동반 매도) + 펀더멘털<60 → 회피', () => {
    const w = mountFull({ fund: 50, aiRec: 'HOLD', foreign: -30, inst: -10 })
    expect(w.find('.rec-label').text()).toBe('회피')
    expect(w.find('.rec-reason').text()).toContain('수급 이탈')
  })

  it('신호 없음 → 관망', () => {
    const w = mountFull({ fund: 55, aiRec: 'HOLD' })
    expect(w.find('.rec-label').text()).toBe('관망')
  })
})
