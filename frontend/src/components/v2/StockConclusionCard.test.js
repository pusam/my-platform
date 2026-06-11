import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import StockConclusionCard from './StockConclusionCard.vue'
import apiClient from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() }
}))

const conclusionData = {
  stockCode: '005930',
  stockName: '삼성전자',
  level: 'STRONG_BUY',
  headline: '단기 모멘텀 + 다수 시그널 합의 — 매수 적기로 평가.',
  guidance: '밸류(저평가)도 양호 — 전량 진입 고려.',
  conflictNote: null,
  factors: [
    { key: 'total', label: '종합 점수', dimension: 'MID', score: 80, verdict: 'POSITIVE', note: '5카테고리 합산' },
    { key: 'supplyDemand', label: '수급', dimension: 'SHORT', score: 16, verdict: 'POSITIVE', note: '외국인/기관 순매수 추세 · 근거: 외국인3일연속(초기)' }
  ],
  tradePlan: {
    basePrice: 70000,
    stopLossPct: -3,
    targetPct: 5,
    stopLossPrice: 67900,
    targetPrice: 73500,
    avgMfePct: 4.12,
    avgMaePct: -2.57,
    mfeMaeSampleCount: 12
  },
  dataAt: new Date().toISOString(),
  dataAvailable: true
}

const accuracyResponse = {
  data: { success: true, data: { stats: [
    { signalType: 'STRONG_BUY', totalSignals: 20, hitCount: 13, hitRate: 65, avgPctChange: 1.8 }
  ] } }
}

const bandResponse = {
  data: { success: true, data: { bands: [
    { band: '55~64', scoreFrom: 55, scoreTo: 64, totalSignals: 0, hitCount: 0, hitRate: 0 },
    { band: '75~84', scoreFrom: 75, scoreTo: 84, totalSignals: 9, hitCount: 6, hitRate: 66.67 }
  ] } }
}

function stubApi(conclusion = conclusionData, catalyst = null) {
  apiClient.get.mockImplementation((url) => {
    if (url.includes('/conclusion')) return Promise.resolve({ data: { success: true, data: conclusion } })
    if (url.includes('/catalyst')) return Promise.resolve({ data: { success: true, data: catalyst } })
    if (url.includes('accuracy-by-band')) return Promise.resolve(bandResponse)
    if (url.includes('accuracy')) return Promise.resolve(accuracyResponse)
    return Promise.resolve({ data: { success: false } })
  })
}

async function mountCard() {
  const wrapper = mount(StockConclusionCard, {
    props: { stockCode: '005930' },
    global: { stubs: { BuyChecklistModal: true } }
  })
  await flushPromises()
  return wrapper
}

describe('StockConclusionCard — 매매 계획 / 조건부 적중률', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('tradePlan 있으면 진입/손절/목표가를 숫자로 표시', async () => {
    stubApi()
    const w = await mountCard()
    const plan = w.find('.trade-plan')
    expect(plan.exists()).toBe(true)
    expect(plan.text()).toContain('진입 70,000원')
    expect(plan.text()).toContain('67,900원 (-3%)')
    expect(plan.text()).toContain('73,500원 (+5%)')
  })

  it('MFE/MAE 실측 라인 — 표본 수 + 평균 최고/최저 변동폭', async () => {
    stubApi()
    const w = await mountCard()
    const mfe = w.find('.tp-mfe')
    expect(mfe.exists()).toBe(true)
    expect(mfe.text()).toContain('12건')
    expect(mfe.text()).toContain('+4.12%')
    expect(mfe.text()).toContain('-2.57%')
  })

  it('basePrice null 이면 % 만 표시 (가격 미조회 폴백)', async () => {
    stubApi({
      ...conclusionData,
      tradePlan: { ...conclusionData.tradePlan, basePrice: null, stopLossPrice: null, targetPrice: null }
    })
    const w = await mountCard()
    const plan = w.find('.trade-plan')
    expect(plan.text()).toContain('손절 -3%')
    expect(plan.text()).toContain('목표 +5%')
    expect(plan.text()).not.toContain('진입')
  })

  it('tradePlan null (WAIT 등) 이면 매매 계획 블록 미표시', async () => {
    stubApi({ ...conclusionData, level: 'WAIT', tradePlan: null })
    const w = await mountCard()
    expect(w.find('.trade-plan').exists()).toBe(false)
  })

  it('점수대(75~84) 구간 적중률을 적중률 라인에 병기', async () => {
    stubApi()
    const w = await mountCard()
    const band = w.find('.acc-detail.band')
    expect(band.exists()).toBe(true)
    expect(band.text()).toContain('75~84')
    expect(band.text()).toContain('66.67%')
    expect(band.text()).toContain('9건')
  })

  it('타이트 계획(targetPct=3) 이면 타이트 라벨 표시', async () => {
    stubApi({
      ...conclusionData,
      tradePlan: { ...conclusionData.tradePlan, stopLossPct: -2, targetPct: 3, stopLossPrice: 68600, targetPrice: 72100 }
    })
    const w = await mountCard()
    expect(w.find('.tp-basis').text()).toContain('타이트')
  })

  it('factor 근거 텍스트(· 근거: ...) 렌더', async () => {
    stubApi()
    const w = await mountCard()
    expect(w.text()).toContain('근거: 외국인3일연속(초기)')
  })

  it('재료 배지(V31) — 호재면 헤드라인 아래 표시', async () => {
    stubApi(conclusionData, {
      catalystType: 'ORDER_WIN', typeLabel: '수주', direction: 'POSITIVE', summary: '2조원 공급계약'
    })
    const w = await mountCard()
    const line = w.find('.catalyst-line')
    expect(line.exists()).toBe(true)
    expect(line.text()).toContain('재료: 수주(호재)')
    expect(line.text()).toContain('2조원 공급계약')
    expect(line.classes()).toContain('cat-positive')
  })

  it('재료 NONE/null 이면 배지 미표시', async () => {
    stubApi(conclusionData, { catalystType: 'NONE', direction: 'NONE' })
    const w = await mountCard()
    expect(w.find('.catalyst-line').exists()).toBe(false)
  })
})
