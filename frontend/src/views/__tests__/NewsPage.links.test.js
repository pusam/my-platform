import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 시장 탭 '뉴스' 카드는 마우스로만 열렸다(2026-10-01 점검, 88개) — 카드 전체가 click 만 받는 article 이었다.
 * 제목을 진짜 링크로 둬 Tab·Enter 로 연다. 주소는 기존 가드 그대로 http/https 만(javascript: 차단),
 * 링크를 누르면 카드의 window.open 이 한 번 더 돌지 않게 전파를 막는다.
 */
vi.mock('../../utils/api', () => ({
  newsAPI: {
    getTodayNews: vi.fn(),
    getRecentNews: vi.fn(() => Promise.resolve({ data: { data: [] } })),
    fetchNews: vi.fn()
  }
}))
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { newsAPI } from '../../utils/api'
import NewsPage from '../NewsPage.vue'

const NEWS = [
  { id: 1, title: '반도체 수출 증가', summary: '요약', sourceName: '전자신문', sourceUrl: 'https://example.com/a', summarizedAt: '2026-10-01T09:00:00' },
  { id: 2, title: '환율 동향', summary: '요약', sourceName: '매일경제', sourceUrl: 'javascript:alert(1)', summarizedAt: '2026-10-01T09:00:00' }
]

describe('NewsPage — 뉴스 카드를 키보드로 연다', () => {
  let openSpy
  beforeEach(() => {
    newsAPI.getTodayNews.mockResolvedValue({ data: { data: NEWS } })
    openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
  })
  afterEach(() => openSpy.mockRestore())

  const mountPage = async () => {
    const w = mount(NewsPage, { props: { embedded: true }, global: { stubs: { LoadingSpinner: true, BackButton: true } } })
    await flushPromises()
    return w
  }

  it('안전한 주소면 제목이 새 창 링크다(noopener·noreferrer)', async () => {
    const w = await mountPage()
    const links = w.findAll('a.news-link')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toBe('https://example.com/a')
    expect(links[0].attributes('target')).toBe('_blank')
    expect(links[0].attributes('rel')).toBe('noopener noreferrer')
    expect(links[0].text()).toBe('반도체 수출 증가')
  })

  it('javascript: 같은 주소는 링크를 만들지 않고 제목만 보인다', async () => {
    const w = await mountPage()
    const cards = w.findAll('.news-card')
    expect(cards[1].find('a').exists()).toBe(false)
    expect(cards[1].find('h3').text()).toBe('환율 동향')
  })

  it('링크를 누르면 카드 click(window.open)은 돌지 않는다 — 탭이 두 개 열리지 않게', async () => {
    const w = await mountPage()
    const link = w.find('a.news-link')
    link.element.addEventListener('click', (e) => e.preventDefault())   // jsdom 의 실제 이동만 막는다(전파는 그대로)
    await link.trigger('click')
    expect(openSpy).not.toHaveBeenCalled()
  })

  it('카드의 나머지 영역 click 은 종전처럼 연다(안전한 주소만)', async () => {
    const w = await mountPage()
    const cards = w.findAll('.news-card')
    await cards[0].trigger('click')
    expect(openSpy).toHaveBeenCalledWith('https://example.com/a', '_blank', 'noopener,noreferrer')
    openSpy.mockClear()
    await cards[1].trigger('click')
    expect(openSpy).not.toHaveBeenCalled()
  })
})
