import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const getStatus = vi.fn()
vi.mock('../../utils/api', () => ({
  tradingSafetyAPI: { getStatus: (...a) => getStatus(...a), enableKillSwitch: vi.fn(), disableKillSwitch: vi.fn() }
}))
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), success: vi.fn(), warning: vi.fn() } }))

import TradingSafetyWidget from './TradingSafetyWidget.vue'

/**
 * 매매 안전 위젯 — 상태를 못 받았으면 '매매 정상'이 아니다(2026-10-03).
 * 재현: 초기값 killSwitchEnabled=false 가 그대로 '매매 정상'(초록)으로 보여, 비상 정지가 켜져 있어도 조회가 실패하면 정상처럼 보였다.
 */
describe('TradingSafetyWidget', () => {
  beforeEach(() => getStatus.mockReset())

  it('재현: 조회 실패면 상태 확인 실패 — 매매 정상이 아니고 재개 버튼도 없다', async () => {
    getStatus.mockRejectedValueOnce(new Error('network'))
    const w = mount(TradingSafetyWidget)
    await flushPromises()
    expect(w.find('.status-badge').text()).toContain('상태 확인 실패')
    expect(w.text()).not.toContain('매매 정상')
    expect(w.find('.btn-resume').exists()).toBe(false)
    w.unmount()
  })

  it('받은 상태는 그대로 — 비상 정지 ON', async () => {
    getStatus.mockResolvedValue({ data: { success: true, data: { killSwitchEnabled: true, killSwitchReason: '수동' } } })
    const w = mount(TradingSafetyWidget)
    await flushPromises()
    expect(w.find('.status-badge').text()).toContain('비상 정지 ON')
    expect(w.find('.btn-resume').exists()).toBe(true)
    w.unmount()
  })

  it('성공 뒤 갱신이 실패하면 마지막 상태를 현재처럼 보이지 않는다', async () => {
    getStatus.mockResolvedValueOnce({ data: { success: true, data: { killSwitchEnabled: false } } })
    const w = mount(TradingSafetyWidget)
    await flushPromises()
    expect(w.find('.status-badge').text()).toContain('매매 정상')
    getStatus.mockRejectedValueOnce(new Error('network'))
    await w.vm.loadStatus()
    await flushPromises()
    expect(w.find('.status-badge').text()).toContain('상태 확인 실패')
    w.unmount()
  })
})
