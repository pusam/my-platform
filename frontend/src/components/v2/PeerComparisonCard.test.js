import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PeerComparisonCard from './PeerComparisonCard.vue'

const peers = [
  { stockName: '삼성전자', pbr: 1.3, dividendYield: 2.1, isCurrent: true },
  { stockName: 'SK하이닉스', pbr: 0.4, dividendYield: 1.2, isCurrent: false },
  { stockName: 'A사', pbr: 1.8, dividendYield: 0.5, isCurrent: false }
]

function mountCard(props = {}) {
  return mount(PeerComparisonCard, {
    props: { peerComparisons: peers, sectorName: '반도체', sectorAvgPbr: 1.2, ...props }
  })
}

describe('PeerComparisonCard (P2-10 분리)', () => {
  it('peer 수만큼 행 + 섹터명 뱃지', () => {
    const w = mountCard()
    expect(w.findAll('.peer-bar-row')).toHaveLength(3)
    expect(w.find('.sector-name-badge').text()).toBe('반도체')
  })

  it('current peer 강조 클래스', () => {
    const rows = mountCard().findAll('.peer-bar-row')
    expect(rows[0].classes()).toContain('current')
    expect(rows[1].classes()).not.toContain('current')
  })

  it('bar 너비 = min(100, pbr/2×100)', () => {
    const rows = mountCard().findAll('.peer-bar-row')
    expect(rows[0].find('.peer-bar-fill').attributes('style')).toContain('width: 65%')  // 1.3/2*100
    expect(rows[1].find('.peer-bar-fill').attributes('style')).toContain('width: 20%')  // 0.4/2*100
  })

  it('PBR 구간별 색상 클래스 (very-low/mid/high)', () => {
    const rows = mountCard().findAll('.peer-bar-row')
    expect(rows[0].find('.peer-bar-fill').classes()).toContain('peer-mid')      // 1.3 → mid
    expect(rows[1].find('.peer-bar-fill').classes()).toContain('peer-very-low') // 0.4 → very-low
    expect(rows[2].find('.peer-bar-fill').classes()).toContain('peer-high')     // 1.8 → high
  })

  it('PBR/배당 텍스트 + 섹터 평균 라벨', () => {
    const w = mountCard()
    const r0 = w.findAll('.peer-bar-row')[0]
    expect(r0.find('.peer-pbr').text()).toBe('PBR 1.30배')
    expect(r0.find('.peer-div').text()).toBe('배당 2.1%')
    expect(w.find('.sector-avg-label').text()).toContain('1.20배')
  })

  it('sectorAvgPbr 없으면 평균선/라벨 미표시', () => {
    const w = mountCard({ sectorAvgPbr: null })
    expect(w.find('.sector-avg-line').exists()).toBe(false)
    expect(w.find('.sector-avg-label').exists()).toBe(false)
  })
})
