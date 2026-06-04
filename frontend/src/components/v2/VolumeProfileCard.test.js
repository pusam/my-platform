import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import VolumeProfileCard from './VolumeProfileCard.vue'

// mid 가 100단위로 떨어지는 bin 으로 구성 (표시가격 = round(mid/100)*100 모호성 제거)
const sample = {
  poc: 1200,
  val: 1000,
  vah: 1400,
  periodDays: 90,
  bins: [
    { priceLow: 900,  priceHigh: 1100, volumePct: 10 }, // mid 1000
    { priceLow: 1100, priceHigh: 1300, volumePct: 50 }, // mid 1200, POC(1200∈[1100,1300)), max → bar 100%
    { priceLow: 1300, priceHigh: 1500, volumePct: 20 }  // mid 1400
  ]
}

function mountCard(vp = sample) {
  return mount(VolumeProfileCard, {
    props: { volumeProfile: vp },
    global: { stubs: { InfoTooltip: true } }
  })
}

describe('VolumeProfileCard (P2-10 분리)', () => {
  it('bins 수만큼 행 렌더 + 가격 내림차순(reverse)', () => {
    const rows = mountCard().findAll('.vp-row')
    expect(rows).toHaveLength(3)
    // reverse → 첫 행이 가장 높은 가격대(mid 1400)
    expect(rows[0].find('.vp-price').text()).toBe('1400')
    expect(rows[2].find('.vp-price').text()).toBe('1000')
  })

  it('POC bin(1200∈[1100,1300))에 vp-poc 클래스 — 정확히 1개', () => {
    const poc = mountCard().findAll('.vp-row.vp-poc')
    expect(poc).toHaveLength(1)
    expect(poc[0].find('.vp-price').text()).toBe('1200')
  })

  it('막대 너비 = volumePct / max × 100 (max 50 → 40%/100%/20%)', () => {
    const rows = mountCard().findAll('.vp-row')
    // 행 순서(reverse): [1400(20%→40%), 1200(50%→100%), 1000(10%→20%)]
    expect(rows[0].find('.vp-bar').attributes('style')).toContain('width: 40%')
    expect(rows[1].find('.vp-bar').attributes('style')).toContain('width: 100%')
    expect(rows[2].find('.vp-bar').attributes('style')).toContain('width: 20%')
  })

  it('POC 통계 텍스트 노출', () => {
    expect(mountCard().find('.vp-header').text()).toContain('1,200')
  })

  it('volumePct 1자리 표기', () => {
    expect(mountCard().findAll('.vp-pct')[1].text()).toBe('50.0%')
  })
})
