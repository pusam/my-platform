import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SectionJudgmentBoard from './SectionJudgmentBoard.vue'
import apiClient from '../../utils/api'

vi.mock('../../utils/api', () => ({ default: { get: vi.fn() } }))

function row(over = {}) {
  return {
    stockCode: '005930', stockName: '삼성전자', scored: true,
    totalScore: 80, technical: 16, earnings: 18, sectorMomentum: 12,
    supplyDemand: 8, supplyInverseSuspect: false,
    timingScore: null, sector: '반도체', sectorStrengthRel: 1.5,
    currentPrice: 70000, changeRate: 1.24, tradingValue: 523400000000,
    catalystType: null, catalystLabel: null, catalystDirection: null,
    sources: ['momentum'], ...over
  }
}

function boardResp(rows) {
  return { data: { success: true, data: {
    market: { regime: 'BULL' }, rows, timingAvailable: true, sectorStrengthAvailable: true,
    scope: 'momentum', note: '테스트'
  } } }
}

async function mountBoard(rows) {
  apiClient.get.mockResolvedValue(boardResp(rows))
  const w = mount(SectionJudgmentBoard)
  await flushPromises()
  return w
}

describe('SectionJudgmentBoard — 매매 맥락(재료·현재가·거래대금)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('현재가 + 등락률 셀 — 천단위 콤마 + 부호/색상', async () => {
    const w = await mountBoard([row({ currentPrice: 70000, changeRate: 1.24 })])
    const price = w.find('.td-price')
    expect(price.exists()).toBe(true)
    expect(price.text()).toContain('70,000')
    expect(price.text()).toContain('+1.24%')
    expect(price.find('.pr').classes()).toContain('positive')
  })

  it('등락률 음수 → negative 색상, null → 등락 생략', async () => {
    const w = await mountBoard([
      row({ stockCode: 'A', changeRate: -2.5 }),
      row({ stockCode: 'B', changeRate: null })
    ])
    const cells = w.findAll('.td-price')
    expect(cells[0].find('.pr').classes()).toContain('negative')
    expect(cells[0].text()).toContain('-2.50%')
    expect(cells[1].find('.pr').exists()).toBe(false)
  })

  it('거래대금 축약 — 억/조 단위, null=미실측 —', async () => {
    const w = await mountBoard([
      row({ stockCode: 'A', tradingValue: 523400000000 }),  // 5,234억
      row({ stockCode: 'B', tradingValue: 1500000000000 }), // 1.5조
      row({ stockCode: 'C', tradingValue: null })           // 미실측
    ])
    const tv = w.findAll('.td-tv')
    expect(tv[0].text()).toContain('5,234억')
    expect(tv[1].text()).toContain('1.5조')
    expect(tv[2].text()).toBe('—')
  })

  it('재료 배지 — 호재 🔥/cat-pos · 악재 ⚠️/cat-neg · 중립 아이콘無/cat-neu · 없으면 무배지', async () => {
    const w = await mountBoard([
      row({ stockCode: 'A', catalystType: 'ORDER_WIN', catalystLabel: '수주', catalystDirection: 'POSITIVE' }),
      row({ stockCode: 'B', catalystType: 'LITIGATION', catalystLabel: '소송', catalystDirection: 'NEGATIVE' }),
      row({ stockCode: 'C', catalystType: 'NEW_BUSINESS', catalystLabel: '신사업', catalystDirection: 'NEUTRAL' }),
      row({ stockCode: 'D' })  // 재료 없음
    ])
    const rows = w.findAll('.jb-row')
    const a = rows[0].find('.cat-badge')
    expect(a.text()).toContain('🔥')
    expect(a.text()).toContain('수주')
    expect(a.classes()).toContain('cat-pos')

    const b = rows[1].find('.cat-badge')
    expect(b.text()).toContain('⚠️')
    expect(b.text()).toContain('소송')
    expect(b.classes()).toContain('cat-neg')

    // 중립 — 방향 아이콘 없이 라벨만, cat-neu(회색). 🔥/⚠️ 오인 표시 안 함.
    const c = rows[2].find('.cat-badge')
    expect(c.exists()).toBe(true)
    expect(c.text()).toContain('신사업')
    expect(c.text()).not.toContain('🔥')
    expect(c.text()).not.toContain('⚠️')
    expect(c.classes()).toContain('cat-neu')

    expect(rows[3].find('.cat-badge').exists()).toBe(false)
  })

  it('지연 경고 — 현재가/거래대금이 캐시 스냅샷(최대 30분·실시간 아님)임을 명시', async () => {
    const w = await mountBoard([row()])
    const note = w.find('.jb-delay-note')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('최대 30분 지연')
    expect(note.text()).toContain('실시간 아님')
  })

  it('카테고리 NA(-1) → "—" 렌더 (순매수 신호 미포착을 음수 점수로 오해 방지)', async () => {
    const w = await mountBoard([row({ supplyDemand: -1 })])
    const supply = w.find('.td-supply')
    expect(supply.exists()).toBe(true)
    expect(supply.text()).toBe('—')
    // 보드에 "-1" 이 노출되지 않아야
    expect(w.text()).not.toContain('-1')
  })

  it('카테고리 양수 점수는 그대로 표시(역상관 의심 ⚠ 포함)', async () => {
    const w = await mountBoard([row({ supplyDemand: 12, supplyInverseSuspect: true })])
    expect(w.find('.td-supply').text()).toContain('12')
  })

  it('발굴 트랙 포함 토글 상태 유지 — 저장값(union)으로 초기 로드', async () => {
    localStorage.setItem('judgmentBoard.scope', 'union')
    await mountBoard([row()])
    expect(apiClient.get).toHaveBeenCalledWith('/recommendation/judgment-board', { params: { scope: 'union' } })
    localStorage.removeItem('judgmentBoard.scope')
  })

  it('저장값 없으면 momentum(빠름)으로 초기 로드', async () => {
    localStorage.removeItem('judgmentBoard.scope')
    await mountBoard([row()])
    expect(apiClient.get).toHaveBeenCalledWith('/recommendation/judgment-board', { params: { scope: 'momentum' } })
  })
})
