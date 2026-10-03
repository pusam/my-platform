import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { mount } from '@vue/test-utils'
import SectionLiveSurge from './SectionLiveSurge.vue'

/**
 * 수급 급증 카드는 그리드로 나란히 놓인다 — 그래서 **카드마다 행 수가 달라지면 안 된다**(2026-09-21 디자인 점검).
 *
 * 예전엔 `변화량`·`현재가`·`등락률` 이 각각 `v-if` 라, 값이 없는 종목의 카드만 행이 빠져
 * 옆 카드와 라벨 높이가 어긋났다(같은 줄을 가로로 훑어 비교할 수 없다). 결측은 §4c 대로
 * 위장하지 않되 **자리는 남기고 '-'** 로 표시한다(체결강도 게이지가 쓰는 기존 규약과 동일).
 *
 * 단 `공통(COMMON)` 탭은 지표 구성 자체가 달라(외국인·기관 분해) 변화량이 적용되지 않는다 —
 * 탭 안에서 일관되면 된다.
 */
describe('SectionLiveSurge — 카드 행 정렬', () => {
  const stock = (over = {}) => ({
    stockCode: '005930',
    stockName: '삼성전자',
    currentRank: 1,
    netBuyAmount: 15540,
    formattedNetBuyAmount: '+15540.7억',
    formattedChangeAmount: '+120.0억',
    amountChange: 120,
    currentPrice: 272000,
    changeRate: 4.21,
    surgeLevel: 'HOT',
    ...over
  })

  const mountWith = (stocks, investor = 'FOREIGN') => {
    const w = mount(SectionLiveSurge, { props: { active: false } })
    w.vm.allStocks = { [investor]: stocks }
    w.vm.investor = investor
    return w
  }

  const rowCounts = (w) =>
    w.findAll('.stock-card').map((c) => c.findAll('.detail-row').length)

  const labelsOf = (w, i) =>
    w.findAll('.stock-card')[i].findAll('.detail-row .label').map((l) => l.text())

  it('변화량이 없는 종목도 같은 행 수를 유지한다 — 자리를 비우고 "-" 로 표시', async () => {
    const w = mountWith([
      stock(),
      stock({ stockCode: '000660', formattedChangeAmount: null, amountChange: null })
    ])
    await w.vm.$nextTick()

    const counts = rowCounts(w)
    expect(counts).toHaveLength(2)
    expect(new Set(counts).size).toBe(1)                 // 모든 카드가 같은 행 수
    expect(labelsOf(w, 0)).toEqual(labelsOf(w, 1))       // 라벨 순서까지 동일
    expect(w.findAll('.stock-card')[1].text()).toContain('-')
  })

  it('현재가·등락률이 결측이어도 행이 사라지지 않는다', async () => {
    const w = mountWith([
      stock(),
      stock({ stockCode: '035720', currentPrice: null, changeRate: null })
    ])
    await w.vm.$nextTick()

    expect(new Set(rowCounts(w)).size).toBe(1)
    expect(labelsOf(w, 1)).toContain('현재가')
    expect(labelsOf(w, 1)).toContain('등락률')
  })

  it('공통 탭은 변화량을 쓰지 않는다 — 탭 안에서만 일관되면 된다', async () => {
    const w = mountWith(
      [stock(), stock({ stockCode: '000660', formattedChangeAmount: null })],
      'COMMON'
    )
    await w.vm.$nextTick()

    expect(new Set(rowCounts(w)).size).toBe(1)
    expect(labelsOf(w, 0)).not.toContain('변화량')
  })
})

/**
 * '낡음'은 글자로 말하고, 글자를 흐리게 만들지 않는다(2026-10-01 '오늘' 탭 점검).
 *
 * 예전엔 서버가 낡음으로 표시한 카드를 투명도 0.55 로 흐렸는데 — 서버 판정이 매 주기 최신 값까지 낡음으로
 * 잘못 찍어(InvestorSurgeDtoOutdatedTest) 장중 :x0~:x2 마다 카드 12장이 통째로 흐려졌고, 그때 글자 대비가
 * 2.46~2.79 로 기준(4.5) 아래였다. 진짜로 지연됐을 때도 읽을 수는 있어야 한다.
 */
describe('SectionLiveSurge — 갱신 지연 표시', () => {
  const card = (over = {}) => ({
    stockCode: '005930', stockName: '삼성전자', currentRank: 1, netBuyAmount: 100,
    formattedNetBuyAmount: '+100억', currentPrice: 272000, changeRate: 1.2, surgeLevel: 'HOT',
    snapshotTime: '12:20:00', outdated: false, ...over
  })
  const mountWith = (stocks) => {
    const w = mount(SectionLiveSurge, { props: { active: false } })
    w.vm.allStocks = { FOREIGN: stocks }
    w.vm.investor = 'FOREIGN'
    return w
  }

  it('일부만 지연이면 그 카드에 "갱신 지연"과 기준 시각을 글자로 붙인다', async () => {
    const w = mountWith([card({ outdated: true }), card({ stockCode: '000660', stockName: 'SK하이닉스', snapshotTime: '12:30:00' })])
    await w.vm.$nextTick()
    const [stale, fresh] = w.findAll('.stock-card')
    expect(stale.text()).toContain('갱신 지연')
    expect(stale.text()).toContain('12:20')
    expect(fresh.text()).not.toContain('갱신 지연')
    expect(w.find('.surge-stale-note').exists()).toBe(false)
  })

  it('전부 지연이면 섹션 위에 한 번만 알린다 — 카드마다 반복하지 않는다', async () => {
    const w = mountWith([card({ outdated: true }), card({ stockCode: '000660', outdated: true, snapshotTime: '12:10:00' })])
    await w.vm.$nextTick()
    const note = w.find('.surge-stale-note')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('지연')
    expect(note.text()).toContain('12:20')                       // 가장 최근 값의 시각
    expect(w.findAll('.stock-card .stale-tag')).toHaveLength(0)
  })

  it('지연 카드에 투명도를 걸지 않는다 — 대비 기준(4.5) 아래로 떨어뜨리지 않는다', () => {
    const src = readFileSync(join(process.cwd(), 'src/components/v2/SectionLiveSurge.vue'), 'utf8')
    expect(src).not.toMatch(/\.outdated\s*\{[^}]*opacity/)
  })

  it('미리보기 개수를 주면 그만큼만 보이고 "더 보기"로 나머지를 연다 — 휴대폰에서 카드 12장이 페이지의 73%였다', async () => {
    const many = Array.from({ length: 8 }, (_, i) => card({ stockCode: String(100000 + i), stockName: '종목' + i }))
    const w = mount(SectionLiveSurge, { props: { active: false, previewCount: 6 } })
    w.vm.allStocks = { FOREIGN: many }
    await w.vm.$nextTick()
    expect(w.findAll('.stock-card')).toHaveLength(6)
    const more = w.find('.surge-more')
    expect(more.text()).toContain('2')
    expect(more.attributes('aria-expanded')).toBe('false')
    await more.trigger('click')
    expect(w.findAll('.stock-card')).toHaveLength(8)
    expect(w.find('.surge-more').attributes('aria-expanded')).toBe('true')
  })

  it('카드는 키보드로 포커스하고 Enter·Space 로 연다 — 예전엔 마우스로만 열렸다', async () => {
    const push = vi.fn()
    const w = mount(SectionLiveSurge, { props: { active: false }, global: { mocks: { $router: { push } } } })
    w.vm.allStocks = { FOREIGN: [card()] }
    await w.vm.$nextTick()
    const c = w.find('.stock-card')
    expect(c.attributes('tabindex')).toBe('0')
    expect(c.attributes('role')).toBe('button')
    expect(c.attributes('aria-label')).toContain('삼성전자')
    await c.trigger('keydown', { key: 'Enter' })
    await c.trigger('keydown', { key: ' ' })
    expect(push).toHaveBeenCalledTimes(2)
    expect(push).toHaveBeenCalledWith('/stock/005930')
  })

  it('미리보기 개수가 없으면(기본) 전부 보인다 — 다른 곳에 붙여도 동작이 바뀌지 않는다', async () => {
    const many = Array.from({ length: 8 }, (_, i) => card({ stockCode: String(200000 + i) }))
    const w = mountWith(many)
    await w.vm.$nextTick()
    expect(w.findAll('.stock-card')).toHaveLength(8)
    expect(w.find('.surge-more').exists()).toBe(false)
  })
})

/**
 * 직전 거래일 스냅샷 — 오늘 장중 수집 전(09:00~09:02)이나 수집이 멈춘 날엔 서버가 직전 거래일 값을 돌려준다(2026-10-03).
 * 재현: 낡음 판정이 시각만 비교해(어제 15:30 vs 오늘 09:05 → 음수 → 0분) 어제 카드가 갱신 지연 표시 없이 '실시간' 아래에 섰다.
 */
describe('SectionLiveSurge — 직전 거래일 값은 그렇게 말한다', () => {
  const prev = (over = {}) => ({
    stockCode: '005930', stockName: '삼성전자', currentRank: 1,
    formattedNetBuyAmount: '+1,200억', netBuyAmount: 1200,
    snapshotDate: '2026-10-02', snapshotTime: '15:30:00',
    outdated: true, previousSession: true, ...over
  })

  it('재현: 전부 직전 거래일이면 섹션이 날짜와 함께 그렇다고 말한다', async () => {
    const w = mount(SectionLiveSurge, { props: { active: false } })
    w.vm.allStocks = { FOREIGN: [prev(), prev({ stockCode: '000660', stockName: 'SK하이닉스' })] }
    await w.vm.$nextTick()
    const note = w.find('.surge-stale-note')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('직전 거래일')
    expect(note.text()).toContain('10/02 15:30')
  })

  it('머리말은 실시간이라고 하지 않는다 — 10분 스냅샷이다', () => {
    const src = readFileSync(join(process.cwd(), 'src/components/v2/SectionLiveSurge.vue'), 'utf8')
    expect(src).not.toMatch(/실시간 \(\{\{ nextRefresh \}\}s\)/)
    expect(src).not.toMatch(/⚡<\/span> 실시간 수급 급증/)
  })
})
