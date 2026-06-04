import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SupportResistanceCard from './SupportResistanceCard.vue'

const sample = {
  currentPrice: 1150,
  resistance: [
    { price: 1200, touches: 3, strength: 'HIGH', distancePct: 4.3 },
    { price: 1300, touches: 1, strength: 'LOW', distancePct: 13.0 }
  ],
  support: [
    { price: 1100, touches: 2, strength: 'MEDIUM', distancePct: -3.1 }
  ]
}

function mountCard(sr = sample) {
  return mount(SupportResistanceCard, {
    props: { supportResistance: sr },
    global: { stubs: { InfoTooltip: true } }
  })
}

describe('SupportResistanceCard (P2-10 분리)', () => {
  it('저항/지지 행 + 현재가 렌더', () => {
    const w = mountCard()
    expect(w.findAll('.sr-resistance')).toHaveLength(2)
    expect(w.findAll('.sr-support')).toHaveLength(1)
    expect(w.find('.sr-current-price').text()).toBe('1,150원')
  })

  it('저항선은 reverse(먼 저항이 위) — 첫 행이 1,300원', () => {
    const res = mountCard().findAll('.sr-resistance')
    expect(res[0].find('.sr-price').text()).toBe('1,300원')
    expect(res[1].find('.sr-price').text()).toBe('1,200원')
  })

  it('강도 라벨 HIGH→강 / MEDIUM→중 / LOW→약', () => {
    const w = mountCard()
    const resStrengths = w.findAll('.sr-resistance .sr-strength').map(s => s.text())
    expect(resStrengths).toEqual(['약', '강']) // reverse 후: LOW, HIGH
    expect(w.find('.sr-support .sr-strength').text()).toBe('중')
  })

  it('저항 거리 +부호, 지지 그대로', () => {
    const w = mountCard()
    expect(w.findAll('.sr-resistance .sr-distance')[1].text()).toBe('+4.3%')
    expect(w.find('.sr-support .sr-distance').text()).toBe('-3.1%')
  })

  it('strength 클래스 매핑 (st-high/medium/low)', () => {
    const w = mountCard()
    expect(w.find('.sr-row.st-high').exists()).toBe(true)
    expect(w.find('.sr-row.st-medium').exists()).toBe(true)
  })
})
