import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DetailSection from './DetailSection.vue'

function mountSection(props = {}) {
  return mount(DetailSection, {
    props,
    slots: { default: '<div class="child">심화내용</div>' }
  })
}

describe('DetailSection (P-IA 3단계 접기)', () => {
  it('기본 접힘(defaultOpen=false): body 숨김(v-show), aria-expanded=false', () => {
    const w = mountSection()
    expect(w.find('.detail-section-toggle').attributes('aria-expanded')).toBe('false')
    // v-show 라 DOM 에는 존재하지만 display:none
    const body = w.find('.detail-section-body')
    expect(body.exists()).toBe(true)
    expect(body.attributes('style')).toContain('display: none')
  })

  it('자식은 접힘 상태에서도 마운트 유지(언마운트 X) — API 타이밍 보존', () => {
    const w = mountSection()
    expect(w.find('.child').exists()).toBe(true) // 접혀도 DOM 에 존재
  })

  it('토글 클릭 → 펼침: aria-expanded=true, display:none 해제', async () => {
    const w = mountSection()
    await w.find('.detail-section-toggle').trigger('click')
    expect(w.find('.detail-section-toggle').attributes('aria-expanded')).toBe('true')
    const style = w.find('.detail-section-body').attributes('style') || ''
    expect(style).not.toContain('display: none')
  })

  it('defaultOpen=true → 처음부터 펼침', () => {
    const w = mountSection({ defaultOpen: true })
    expect(w.find('.detail-section-toggle').attributes('aria-expanded')).toBe('true')
  })

  it('title 렌더', () => {
    expect(mountSection({ title: '심화 분석' }).find('.ds-title').text()).toBe('심화 분석')
  })
})
