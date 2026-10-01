import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 긴급 뉴스 토스트(전 탭 공통, 2026-10-01 점검) — 닫기 버튼이 20×18px(24px 미만)·이름 없는 '×' 였고,
 * 토스트 본문은 마우스로만 열렸다. 제목을 진짜 링크로 둬 키보드로 연다(주소 가드는 그대로 http/https 만).
 */
vi.mock('../utils/api', () => ({ newsAPI: { pollNews: vi.fn() } }))
vi.mock('../utils/auth', () => ({ TokenManager: { hasToken: () => true } }))

import { newsAPI } from '../utils/api'
import NewsToast from './NewsToast.vue'

const URGENT = [
  { id: 11, urgent: true, title: '원전 수주', sourceName: '한국경제', sourceUrl: 'https://example.com/n1' },
  { id: 12, urgent: true, title: '환율 급등', sourceName: '매일경제', sourceUrl: 'javascript:alert(1)' }
]

describe('NewsToast — 키보드·터치 영역', () => {
  let openSpy
  beforeEach(() => {
    vi.useFakeTimers()
    newsAPI.pollNews.mockResolvedValue({ data: { data: URGENT } })
    openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
  })
  afterEach(() => {
    openSpy.mockRestore()
    vi.useRealTimers()
  })

  const mountToast = async () => {
    const w = mount(NewsToast, { global: { stubs: { teleport: true } } })
    await vi.advanceTimersByTimeAsync(5000)   // 첫 폴링(5초 뒤)
    await flushPromises()
    return w
  }

  it('닫기 버튼은 이름(aria-label)과 type 이 있다 — "×" 만으로는 읽히지 않는다', async () => {
    const w = await mountToast()
    const close = w.findAll('.toast-close')
    expect(close).toHaveLength(2)
    expect(close[0].attributes('aria-label')).toBe('닫기')
    expect(close[0].attributes('type')).toBe('button')
  })

  it('안전한 주소면 제목이 새 창 링크다 — javascript: 는 링크를 만들지 않는다', async () => {
    const w = await mountToast()
    const links = w.findAll('a.toast-link')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toBe('https://example.com/n1')
    expect(links[0].attributes('target')).toBe('_blank')
    expect(links[0].attributes('rel')).toBe('noopener noreferrer')
    expect(w.findAll('.toast-title')[1].text()).toBe('환율 급등')
  })

  it('링크를 누르면 토스트 click(window.open)은 돌지 않고 토스트만 닫힌다', async () => {
    const w = await mountToast()
    const link = w.find('a.toast-link')
    link.element.addEventListener('click', (e) => e.preventDefault())   // jsdom 이동만 막는다
    await link.trigger('click')
    expect(openSpy).not.toHaveBeenCalled()
    expect(w.findAll('.news-toast')).toHaveLength(1)
  })

  it('토스트 나머지 영역 click 은 종전처럼 연다(안전한 주소만)', async () => {
    const w = await mountToast()
    await w.findAll('.news-toast')[0].trigger('click')
    expect(openSpy).toHaveBeenCalledWith('https://example.com/n1', '_blank', 'noopener,noreferrer')
  })

  it('닫기 버튼은 24px 이상이고, 출처 글자는 흰색 0.5 가 아니다(그라데이션 끝 위 4.07)', () => {
    const src = readFileSync(join(process.cwd(), 'src/components/NewsToast.vue'), 'utf8')
    const css = src.slice(src.indexOf('<style')).replace(/\/\*[\s\S]*?\*\//g, '')
    const rule = (sel) => { const i = css.indexOf(sel + ' {'); return i < 0 ? '' : css.slice(i, css.indexOf('}', i)) }
    expect(rule('.toast-close')).toMatch(/min-height:\s*(2[4-9]|[3-9]\d)px/)
    expect(rule('.toast-close')).toMatch(/min-width:\s*(2[4-9]|[3-9]\d)px/)
    expect(rule('.toast-source')).not.toMatch(/rgba\(255,\s*255,\s*255,\s*0\.5\)/)
  })
})
