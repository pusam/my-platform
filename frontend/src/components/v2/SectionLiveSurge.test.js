import { describe, it, expect } from 'vitest'
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
