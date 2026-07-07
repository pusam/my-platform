import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import QuickSummaryBar from './QuickSummaryBar.vue'

const diagnosis = {
  overallScore: 75, // SAFE
  technicalAnalysis: { rsi14: 72.4, disparity20: 3.2 }, // RSI 과매수, MA 위
  supplyDemand: { foreignNet5Days: 120.6, instNet5Days: -45.2 }
}
const ai = { overallScore: 80, recommendation: 'TRADING_BUY' }

function mountBar(props = {}) {
  return mount(QuickSummaryBar, {
    props: { hasData: true, loading: false, diagnosisData: diagnosis, aiAnalysis: ai, ...props }
  })
}

describe('QuickSummaryBar (P2-10 분리)', () => {
  it('hasData && !loading → 6개 qs-item 렌더', () => {
    expect(mountBar().findAll('.qs-item')).toHaveLength(6)
  })

  it('RSI 72 → 값 반올림(72) + 과매수 + qs-danger', () => {
    const items = mountBar().findAll('.qs-item')
    expect(items[0].find('.qs-value').text()).toBe('72')
    expect(items[0].find('.qs-badge').text()).toBe('과매수')
    expect(items[0].find('.qs-value').classes()).toContain('qs-danger')
  })

  it('20일선 disparity +3.2 → 위 / +3.2%', () => {
    const ma = mountBar().findAll('.qs-item')[1]
    expect(ma.find('.qs-value').text()).toBe('위')
    expect(ma.find('.qs-value').classes()).toContain('qs-positive')
    expect(ma.find('.qs-sub').text()).toBe('+3.2%')
  })

  it('외국인 순매수 +121억 / 기관 순매도 -45억', () => {
    const items = mountBar().findAll('.qs-item')
    expect(items[2].find('.qs-value').text()).toBe('순매수')
    expect(items[2].find('.qs-sub').text()).toBe('+121억')
    expect(items[3].find('.qs-value').text()).toBe('순매도')
    expect(items[3].find('.qs-sub').text()).toBe('-45억')
  })

  it('연속 순매수일 배지 — 2일↑만 "N일 연속" 병기(외국인/기관), 1일/null 은 미표시(참고 톤)', () => {
    const items = mountBar({ diagnosisData: {
      ...diagnosis,
      supplyDemand: { foreignNet5Days: 120.6, instNet5Days: -45.2, foreignBuyStreak: 3, institutionBuyStreak: 1 }
    } }).findAll('.qs-item')
    // 외국인 3일 연속 → 배지, 기관 1일 → 미표시
    expect(items[2].find('.qs-streak').exists()).toBe(true)
    expect(items[2].find('.qs-streak').text()).toBe('3일 연속')
    expect(items[3].find('.qs-streak').exists()).toBe(false)
  })

  it('연속 순매수일 null(데이터 부족) → 배지 미표시', () => {
    const items = mountBar().findAll('.qs-item')  // 기본 diagnosis 엔 streak 없음
    expect(items[2].find('.qs-streak').exists()).toBe(false)
    expect(items[3].find('.qs-streak').exists()).toBe(false)
  })

  it('리스크 score 75 → SAFE / AI 80 + Trading Buy', () => {
    const items = mountBar().findAll('.qs-item')
    expect(items[4].find('.qs-badge').text()).toBe('SAFE')
    expect(items[5].find('.qs-value').text()).toBe('80')
    expect(items[5].find('.qs-badge').text()).toBe('Trading Buy')
    expect(items[5].find('.qs-badge').classes()).toContain('qs-rec-trading_buy')
  })

  it('데이터 null 안전 처리 (대시/빈값)', () => {
    const w = mountBar({ diagnosisData: null, aiAnalysis: null })
    const items = w.findAll('.qs-item')
    expect(items[0].find('.qs-value').text()).toBe('-')
    expect(items[4].find('.qs-badge').text()).toBe('-')
    expect(items[5].find('.qs-value').text()).toBe('-')
  })

  it('loading → 스켈레톤(6개), 본문 미렌더', () => {
    const w = mountBar({ loading: true })
    expect(w.find('.quick-summary-bar.skeleton').exists()).toBe(true)
    expect(w.findAll('.qs-skeleton')).toHaveLength(6)
    expect(w.findAll('.qs-value')).toHaveLength(0)
  })

  it('!hasData && !loading → 아무것도 렌더 안 함', () => {
    const w = mountBar({ hasData: false, loading: false })
    expect(w.find('.quick-summary-bar').exists()).toBe(false)
  })
})
