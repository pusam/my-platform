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
