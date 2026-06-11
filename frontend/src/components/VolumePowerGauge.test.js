import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import VolumePowerGauge from './VolumePowerGauge.vue'

// 체결강도 항상-100% 버그 회귀 방지:
// 데이터 없음(0/null)은 100%(균형)으로 위장하지 않고 "데이터 없음" 상태로 표시해야 한다.
describe('VolumePowerGauge — 데이터 없음 정직 표시', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const at = (hour) => vi.setSystemTime(new Date(2026, 5, 11, hour, 0, 0))

  it('장중 + 유효값 135.4 → 값/신호 정상 표시', () => {
    at(10)
    const w = mount(VolumePowerGauge, { props: { volumePower: 135.4, signal: 'STRONG_BUY' } })
    expect(w.find('.power-value').text()).toBe('135.4%')
    expect(w.find('.signal-badge').text()).toBe('강한 매수세')
  })

  it('장중 + 데이터 없음(0) → "-" + 수집 중 안내 (100% 위장 금지)', () => {
    at(10)
    const w = mount(VolumePowerGauge, { props: { volumePower: 0 } })
    expect(w.find('.power-value').text()).toBe('-%')
    expect(w.text()).not.toContain('100.0')
    expect(w.find('.pre-market-text').text()).toContain('수집')
  })

  it('장 마감 후(21시) + 데이터 없음 → "데이터 없음" (수집 중 아님)', () => {
    at(21)
    const w = mount(VolumePowerGauge, { props: { volumePower: 0 } })
    expect(w.find('.signal-badge').text()).toBe('데이터 없음')
    expect(w.find('.pre-market-text').text()).toContain('당일 체결강도 데이터가 없습니다')
  })

  it('장 마감 후(21시) + 유효값 → 종가 기준 표시', () => {
    at(21)
    const w = mount(VolumePowerGauge, { props: { volumePower: 92.1, signal: 'NEUTRAL' } })
    expect(w.find('.power-value').text()).toBe('92.1%')
    expect(w.find('.signal-badge').text()).toContain('종가')
  })

  it('08시 이전 → 거래 시작 대기', () => {
    at(7)
    const w = mount(VolumePowerGauge, { props: { volumePower: 0 } })
    expect(w.find('.signal-badge').text()).toContain('거래 시작 대기')
  })
})
