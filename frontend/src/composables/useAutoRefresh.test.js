import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { useAutoRefresh } from './useAutoRefresh.js'

/**
 * 자동 갱신 장치 — 화면 함수가 실패를 삼키는 곳이 많아(오류를 잡고 화면에 '조회 실패'를 띄운 뒤 정상 반환),
 * 장치는 그 실패를 성공으로 보고 갱신 시각을 찍었다(2026-11-09, 시장 탭 수급 화면). 화면 함수가 false 를 돌려주면
 * 실패로 보고 시각을 남기지 않는다 — 아무것도 돌려주지 않는 기존 화면은 종전대로 성공.
 */
const harness = (fetcher) => {
  let api
  const C = defineComponent({
    setup() { api = useAutoRefresh(fetcher, { interval: 60_000, immediate: true }); return () => h('div') }
  })
  const w = mount(C)
  return { w, api: () => api }
}

describe('useAutoRefresh — 성공일 때만 갱신 시각', () => {
  it('fetcher 가 false 를 돌려주면 시각을 남기지 않는다', async () => {
    const { w, api } = harness(vi.fn(async () => false))
    await flushPromises()
    expect(api().lastUpdated.value).toBeNull()
    w.unmount()
  })

  it('fetcher 가 아무것도 돌려주지 않으면 종전대로 성공(다른 화면 호환)', async () => {
    const { w, api } = harness(vi.fn(async () => {}))
    await flushPromises()
    expect(api().lastUpdated.value).toBeInstanceOf(Date)
    w.unmount()
  })

  it('fetcher 가 던지면 시각을 남기지 않는다(종전 그대로)', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { w, api } = harness(vi.fn(async () => { throw new Error('x') }))
    await flushPromises()
    expect(api().lastUpdated.value).toBeNull()
    err.mockRestore()
    w.unmount()
  })
})
