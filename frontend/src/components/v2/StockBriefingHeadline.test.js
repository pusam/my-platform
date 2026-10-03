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

// 자체 매수·매도 판정은 내지 않는다(2026-10-03) — 결론 카드와 다른 말을 했다. 주의 사항(관측값)만.
function mountFull({ fund = 55, rsi = 50, aiRec = 'HOLD', foreign = null, inst = null, warnings } = {}) {
  return mount(StockBriefingHeadline, {
    props: {
      diagnosisData: {
        overallScore: fund,
        technicalAnalysis: { rsi14: rsi },
        supplyDemand: { foreignNet5Days: foreign, institutionNet5Days: inst },
        ...(warnings ? { warnings } : {})
      },
      aiAnalysis: { overallScore: null, recommendation: aiRec }
    }
  })
}

describe('StockBriefingHeadline — 판정 없음, 주의 사항만', () => {
  it('재현: 펀더멘털·AI·수급이 모두 좋아 보여도 "적극 매수"를 말하지 않는다', () => {
    const w = mountFull({ fund: 80, aiRec: 'BUY', foreign: 100, inst: 50 })
    expect(w.text()).not.toMatch(/적극 매수|선별 매수|회피|관망/)
    expect(w.find('.rec-label').text()).toBe('참고')
    expect(w.text()).toContain('결론 카드')
  })

  it('관측된 주의 사항은 그대로 — RSI 과열', () => {
    const w = mountFull({ rsi: 78 })
    expect(w.find('.rec-cautions').text()).toContain('RSI 과열')
  })

  it('입력이 아무것도 없으면 분석 중', () => {
    const w = mount(StockBriefingHeadline, { props: { diagnosisData: null, aiAnalysis: null } })
    expect(w.find('.rec-label').text()).toBe('분석 중')
  })
})
