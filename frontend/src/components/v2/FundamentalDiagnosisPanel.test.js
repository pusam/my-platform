import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import FundamentalDiagnosisPanel from './FundamentalDiagnosisPanel.vue'

const baseDiag = {
  overallScore: 75,
  verdict: '매수 추천',
  verdictLevel: 'BUY',
  rsiStatus: '정상',
  warnings: ['부채비율 높음'],
  positives: ['영업이익 증가'],
  financialHealth: {
    score: 80, operatingProfit: 1000, netIncome: 800,
    operatingMargin: 12.5, roe: 15.2, assessment: '재무 양호'
  },
  supplyDemand: {
    score: 70, foreignNet5Days: 120, foreignBuyDays: 3,
    institutionNet5Days: -50, institutionBuyDays: 0,
    isBothBuying: false, isBothSelling: false, assessment: '외국인 매수 우위'
  },
  technicalAnalysis: {
    score: 65, rsiStatus: '정상', rsi14: 55,
    isArrangedUp: true, isAboveMa20: true,
    signalDescription: '상승 추세', assessment: '기술적 양호', mfiScore: null
  }
}

function mountPanel(diag = baseDiag, supplyDemand = null) {
  return mount(FundamentalDiagnosisPanel, {
    props: { diagnosisData: diag, supplyDemand }
  })
}

describe('FundamentalDiagnosisPanel (P-IA ③-2차 분리)', () => {
  it('종합 의견: verdict 라벨/점수 + 등급 클래스 + 경고/긍정 렌더', () => {
    const w = mountPanel()
    expect(w.find('.verdict-label').text()).toBe('매수 추천')
    expect(w.find('.verdict-section').classes()).toContain('verdict-buy')
    expect(w.find('.verdict-score').text()).toContain('75점')
    expect(w.find('.alert-box.warning').text()).toContain('부채비율 높음')
    expect(w.find('.alert-box.positive').text()).toContain('영업이익 증가')
  })

  it('기본 탭=재무 건전성: 점수 + formatBillion 영업이익 렌더', () => {
    const w = mountPanel()
    expect(w.find('.card-score').text()).toContain('80점')
    expect(w.text()).toContain('1,000억')   // formatBillion(1000)
    expect(w.find('.card-assessment').text()).toBe('재무 양호')
  })

  it('수급 탭 전환: 외국인 순매수 라벨 + 금액(formatBillionAbs)', async () => {
    const w = mountPanel()
    await w.findAll('.fund-tab-btn')[1].trigger('click')  // 최근 5일 누적 수급
    const rows = w.findAll('.supply-row')
    expect(rows[0].text()).toContain('순매수')
    expect(rows[0].text()).toContain('120억')
    expect(rows[1].text()).toContain('순매도')  // 기관 -50
  })

  it('기술적 탭 전환: RSI/정배열 지표 렌더', async () => {
    const w = mountPanel()
    await w.findAll('.fund-tab-btn')[2].trigger('click')  // 기술적 분석
    expect(w.text()).toContain('55')         // formatNumber(rsi14,1)
    expect(w.text()).toContain('정배열')
  })

  it('RSI 과열 + STRONG_BUY → 관망 강등(getAdjustedVerdict) + caution 클래스/태그', () => {
    const w = mountPanel({ ...baseDiag, verdict: '적극 매수', verdictLevel: 'STRONG_BUY', rsiStatus: '과열' })
    expect(w.find('.verdict-label').text()).toBe('관망')
    expect(w.find('.verdict-section').classes()).toContain('verdict-caution')
    expect(w.find('.verdict-caution-tag').exists()).toBe(true)
  })

  it('supplyDemand prop 있으면 금일 수급 미니바 노출', async () => {
    const w = mountPanel(baseDiag, { dataSource: '실시간', foreignNetBuy: 30, instNetBuy: -10 })
    await w.findAll('.fund-tab-btn')[1].trigger('click')
    expect(w.find('.today-supply-mini').exists()).toBe(true)
    expect(w.find('.today-supply-mini').text()).toContain('외국인 +30')
  })

  it('supplyDemand 없으면 금일 수급 미니바 미노출', async () => {
    const w = mountPanel()
    await w.findAll('.fund-tab-btn')[1].trigger('click')
    expect(w.find('.today-supply-mini').exists()).toBe(false)
  })
})
