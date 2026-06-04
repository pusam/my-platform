import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'

// 셋업 스모크 — vitest + jsdom + @vue/test-utils 마운트 경로 검증.
// P2-10(StockDetailDashboard 분리)에서 컴포넌트 단위 행동 테스트의 전제 조건.
const Greeter = defineComponent({
  props: { name: { type: String, default: 'world' } },
  template: '<p class="greet">hello {{ name }}</p>'
})

describe('vitest 셋업 스모크', () => {
  it('jsdom 환경이 살아있다 (document 존재)', () => {
    expect(typeof document).toBe('object')
    expect(document.createElement('div').tagName).toBe('DIV')
  })

  it('@vue/test-utils 로 컴포넌트 마운트 + props 렌더', () => {
    const wrapper = mount(Greeter, { props: { name: '주식' } })
    expect(wrapper.find('.greet').text()).toBe('hello 주식')
  })

  it('reactive 업데이트 반영', async () => {
    const wrapper = mount(Greeter, { props: { name: 'A' } })
    await wrapper.setProps({ name: 'B' })
    expect(wrapper.text()).toContain('hello B')
  })
})
