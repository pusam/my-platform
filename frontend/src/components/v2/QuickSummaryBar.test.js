import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import QuickSummaryBar from './QuickSummaryBar.vue'

const diagnosis = {
  overallScore: 75, // SAFE
  technicalAnalysis: { rsi14: 72.4, disparity20: 3.2 }, // RSI 과매수, MA 위
  // ⚠ 백엔드 StockDiagnosisDto.SupplyDemandDto 직렬화 키는 institutionNet5Days (instNet5Days 아님).
  // 이 mock 이 실제 API 키를 그대로 써야 키 불일치(기관 순매수 미표시) 회귀를 잡는다.
  supplyDemand: { foreignNet5Days: 120.6, institutionNet5Days: -45.2 }
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

  it('회귀: 기관 5일 순매수는 institutionNet5Days 키로 읽는다(구 instNet5Days 오타면 "-"로 깨짐)', () => {
    // 백엔드 실제 키(institutionNet5Days)만 채우고 구 오타 키(instNet5Days)는 안 채움 →
    // 코드가 올바른 키를 읽어야만 값/라벨이 렌더된다.
    const items = mountBar({ diagnosisData: {
      overallScore: 75, technicalAnalysis: { rsi14: 50, disparity20: 0 },
      supplyDemand: { foreignNet5Days: 30, institutionNet5Days: -88.7 }
    } }).findAll('.qs-item')
    expect(items[3].find('.qs-value').text()).toBe('순매도')      // 값 있음(‘-’ 아님)
    expect(items[3].find('.qs-value').classes()).toContain('qs-negative')
    expect(items[3].find('.qs-sub').text()).toBe('-89억')
  })

  it('연속 순매수일 배지 — 2일↑만 "N일 연속" 병기(외국인/기관), 1일/null 은 미표시(참고 톤)', () => {
    const items = mountBar({ diagnosisData: {
      ...diagnosis,
      supplyDemand: { foreignNet5Days: 120.6, institutionNet5Days: -45.2, foreignBuyStreak: 3, institutionBuyStreak: 1 }
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

  it('RVOL(V41 ② 참고) 병기 — 값 있으면 "2.3x" 라인, null=미산출(§4c)이면 미표시', () => {
    const withRvol = mountBar({ diagnosisData: { ...diagnosis, rvol: 2.34 } })
    const line = withRvol.find('.qs-rvol-line')
    expect(line.exists()).toBe(true)
    expect(line.text()).toContain('RVOL')
    expect(line.text()).toContain('2.3x')
    // 기본 diagnosis 엔 rvol 없음 → 미표시(§4c)
    expect(mountBar().find('.qs-rvol-line').exists()).toBe(false)
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

/**
 * RSI·20일선 칸의 기준 종가(2026-10-07, 결정 대기 ⓑ → '추천 방향').
 * 재현(10/7 운영): 같은 종목 20일선 이격이 이 칸 +4.5% · 차트 해설 +5.3% · AI 근거 '5% 이상'으로 갈렸다 — 이 칸은 확정 종가(직전 거래일)
 * 기준, 나머지는 오늘 시세 기준인데 어느 칸도 기준을 말하지 않았다.
 */
describe('QuickSummaryBar 기준 종가', () => {
  it('재현: 지표가 쓴 마지막 종가 날짜를 20일선 칸에 적고, 두 칸 툴팁에 기준을 밝힌다', () => {
    const w = mountBar({ diagnosisData: { ...diagnosis, technicalAnalysis: { rsi14: 55, disparity20: 4.5, basisDate: '2026-10-06' } } })
    const items = w.findAll('.qs-item')
    expect(items[1].find('.qs-basis').text()).toBe('10/06 종가')
    expect(items[0].attributes('title')).toContain('10/06 종가 기준')
    expect(items[1].attributes('title')).toContain('차트 해설은 오늘 시세 기준')
  })
  it('기준 날짜를 모르면 줄을 숨긴다', () => {
    const items = mountBar().findAll('.qs-item')
    expect(items[1].find('.qs-basis').exists()).toBe(false)
    expect(items[1].attributes('title')).toBeUndefined()
  })
})
