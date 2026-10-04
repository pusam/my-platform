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

// 2026-10-04: 시간대만 보고 판단해 주말에도 '오늘'처럼 말했다 — 토요일 10시엔 금요일 값을 "매수세가 우위입니다"(실시간처럼),
// 토요일 07시엔 "NXT 프리마켓(08:00) 이후 표시", 밤엔 "오늘의 최종 체결강도". 장 마감 뒤 값은 '마지막 거래일'의 것이다.
describe('VolumePowerGauge — 휴장일·장 마감 이름표', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  const saturday = (hour) => vi.setSystemTime(new Date(2026, 9, 3, hour, 0, 0))   // 2026-10-03 토

  it('재현: 토요일 10시 유효값은 실시간처럼 말하지 않는다 — 마지막 거래일 값', () => {
    saturday(10)
    const w = mount(VolumePowerGauge, { props: { volumePower: 105, signal: 'BUY' } })
    expect(w.text()).not.toContain('매수세가 우위입니다')
    expect(w.find('.post-market-text').text()).toContain('마지막 거래일')
    expect(w.find('.signal-badge').text()).toContain('마지막 거래일')
  })

  it('재현: 토요일 07시엔 프리마켓 안내가 아니라 휴장', () => {
    saturday(7)
    const w = mount(VolumePowerGauge, { props: { volumePower: 0 } })
    expect(w.text()).not.toContain('NXT 프리마켓')
    expect(w.text()).toContain('휴장')
  })

  it('재현: 장 마감 뒤 문구는 "오늘의 최종"이 아니라 마지막 거래일 — 평일 공휴일 밤에도 맞는 말', () => {
    vi.setSystemTime(new Date(2026, 5, 11, 21, 0, 0))
    const w = mount(VolumePowerGauge, { props: { volumePower: 92.1, signal: 'NEUTRAL' } })
    expect(w.text()).not.toContain('오늘의 최종')
    expect(w.find('.post-market-text').text()).toContain('마지막 거래일')
  })
})
