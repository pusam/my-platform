import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 내 사이트 바로가기(/sites) — 주소 모음 화면.
 *  - 목록은 서버가 준 것만 그린다(화면 파일에 주소를 적지 않는다).
 *  - 사용자가 넣은 주소는 http/https 일 때만 링크가 된다(javascript: 등은 글자로만).
 *  - 불러오기 실패를 '없음'으로 보이지 않는다.
 */
vi.mock('../../utils/api', () => ({
  siteLinksAPI: { list: vi.fn(), add: vi.fn(), update: vi.fn(), remove: vi.fn() }
}))
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

import { siteLinksAPI } from '../../utils/api'
import { toast } from '../../utils/toast'
import SiteLinksPage from '../SiteLinksPage.vue'

const ok = (data) => ({ data: { success: true, data } })

const LINKS = [
  { id: 2, name: '둘째 사이트', description: '관리자 화면', url: 'https://b.example/admin', loginRequired: true, sortOrder: 2 },
  { id: 1, name: '첫째 사이트', description: null, url: 'https://a.example/', loginRequired: false, sortOrder: 1 },
  { id: 3, name: '나쁜 줄', description: '<b>굵게</b>', url: 'javascript:alert(1)', loginRequired: false, sortOrder: 3 }
]

const mountPage = async () => {
  const w = mount(SiteLinksPage, { global: { stubs: { GlobalNav: true, BackButton: true } } })
  await flushPromises()
  return w
}

describe('SiteLinksPage — 내 사이트 바로가기', () => {
  let confirmSpy

  beforeEach(() => {
    vi.clearAllMocks()
    siteLinksAPI.list.mockResolvedValue(ok(LINKS))
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => {
    confirmSpy.mockRestore()
  })

  it('서버가 준 목록을 순서대로 그리고, 새 탭 링크에는 noopener 를 단다', async () => {
    const w = await mountPage()

    const cards = w.findAll('[data-test="card"]')
    expect(cards.map((c) => c.find('.link-name').text())).toEqual(['첫째 사이트', '둘째 사이트', '나쁜 줄'])

    const first = cards[0].find('a[data-test="open"]')
    expect(first.attributes('href')).toBe('https://a.example/')
    expect(first.attributes('target')).toBe('_blank')
    expect(first.attributes('rel')).toBe('noopener noreferrer')
    expect(cards[0].find('.link-addr').text()).toBe('a.example')
    expect(cards[0].find('.login-badge').text()).toBe('로그인 없음')
    expect(cards[1].find('.login-badge').text()).toBe('로그인 필요')
    expect(cards[1].find('.link-desc').text()).toBe('관리자 화면')
  })

  it('http·https 가 아닌 주소는 링크가 되지 않는다 — 글자로만 보인다', async () => {
    const w = await mountPage()

    const bad = w.findAll('[data-test="card"]')[2]
    expect(bad.find('a').exists()).toBe(false)
    expect(bad.find('[data-test="blocked"]').exists()).toBe(true)
    expect(bad.text()).toContain('열 수 없는 주소')
    expect(w.html()).not.toContain('href="javascript:')
  })

  it('설명·이름은 평문으로 그린다 — 태그가 살아나지 않는다', async () => {
    const w = await mountPage()

    const bad = w.findAll('[data-test="card"]')[2]
    expect(bad.find('.link-desc').text()).toBe('<b>굵게</b>')
    expect(bad.find('.link-desc b').exists()).toBe(false)
  })

  it('목록이 비면 넣는 방법을 말한다', async () => {
    siteLinksAPI.list.mockResolvedValue(ok([]))
    const w = await mountPage()

    expect(w.find('[data-test="empty"]').exists()).toBe(true)
    expect(w.find('[data-test="list"]').exists()).toBe(false)
  })

  it("불러오기 실패는 '없음'이 아니라 실패로 말하고 다시 시도할 수 있다", async () => {
    siteLinksAPI.list.mockRejectedValueOnce(new Error('network'))
    const w = await mountPage()

    expect(w.find('[data-test="load-failed"]').exists()).toBe(true)
    expect(w.find('[data-test="empty"]').exists()).toBe(false)

    await w.find('.retry-btn').trigger('click')
    await flushPromises()
    expect(w.findAll('[data-test="card"]')).toHaveLength(3)
  })

  it('success 가 아닌 응답도 실패로 본다', async () => {
    siteLinksAPI.list.mockResolvedValue({ data: { success: false, message: '오류' } })
    const w = await mountPage()

    expect(w.find('[data-test="load-failed"]').exists()).toBe(true)
  })

  it('추가 — 다듬은 값으로 보내고 목록을 다시 읽는다', async () => {
    siteLinksAPI.add.mockResolvedValue(ok({ id: 9 }))
    const w = await mountPage()

    await w.find('[data-test="add"]').trigger('click')
    await w.find('#sl-name').setValue('  새 사이트  ')
    await w.find('#sl-url').setValue(' https://c.example/x ')
    await w.find('#sl-desc').setValue(' 설명 ')
    await w.find('[data-test="form"]').trigger('submit')
    await flushPromises()

    expect(siteLinksAPI.add).toHaveBeenCalledWith({
      name: '새 사이트', url: 'https://c.example/x', description: '설명', loginRequired: false, sortOrder: 4
    })
    expect(siteLinksAPI.list).toHaveBeenCalledTimes(2)
    expect(w.find('[data-test="form"]').exists()).toBe(false)
  })

  it('스크립트 주소는 서버로 보내지 않고 이유를 말한다', async () => {
    const w = await mountPage()

    await w.find('[data-test="add"]').trigger('click')
    await w.find('#sl-name').setValue('나쁜 링크')
    await w.find('#sl-url').setValue('javascript:alert(1)')
    await w.find('[data-test="form"]').trigger('submit')
    await flushPromises()

    expect(siteLinksAPI.add).not.toHaveBeenCalled()
    expect(w.find('[data-test="form-error"]').text()).toContain('http://')
  })

  it('서버가 거절하면 그 이유를 폼에 보여 주고 창을 닫지 않는다', async () => {
    siteLinksAPI.add.mockRejectedValue({ response: { data: { message: '잘못된 요청입니다.', details: '바로가기는 100개까지 넣을 수 있습니다.' } } })
    const w = await mountPage()

    await w.find('[data-test="add"]').trigger('click')
    await w.find('#sl-name').setValue('하나 더')
    await w.find('#sl-url').setValue('https://d.example')
    await w.find('[data-test="form"]').trigger('submit')
    await flushPromises()

    expect(w.find('[data-test="form-error"]').text()).toBe('바로가기는 100개까지 넣을 수 있습니다.')
    expect(w.find('[data-test="form"]').exists()).toBe(true)
  })

  it('수정 — 그 줄의 값으로 폼을 채우고 id 로 보낸다', async () => {
    siteLinksAPI.update.mockResolvedValue(ok({ id: 2 }))
    const w = await mountPage()

    await w.findAll('[data-test="card"]')[1].find('[data-test="edit"]').trigger('click')
    expect(w.find('#sl-name').element.value).toBe('둘째 사이트')
    expect(w.find('#sl-url').element.value).toBe('https://b.example/admin')

    await w.find('#sl-name').setValue('둘째(새 이름)')
    await w.find('[data-test="form"]').trigger('submit')
    await flushPromises()

    expect(siteLinksAPI.update).toHaveBeenCalledWith(2, {
      name: '둘째(새 이름)', url: 'https://b.example/admin', description: '관리자 화면', loginRequired: true, sortOrder: 2
    })
    expect(siteLinksAPI.add).not.toHaveBeenCalled()
  })

  it('삭제 — 확인을 받은 뒤에만 지운다', async () => {
    siteLinksAPI.remove.mockResolvedValue(ok(null))
    const w = await mountPage()

    confirmSpy.mockReturnValueOnce(false)
    await w.findAll('[data-test="card"]')[0].find('[data-test="delete"]').trigger('click')
    await flushPromises()
    expect(siteLinksAPI.remove).not.toHaveBeenCalled()

    await w.findAll('[data-test="card"]')[0].find('[data-test="delete"]').trigger('click')
    await flushPromises()
    expect(siteLinksAPI.remove).toHaveBeenCalledWith(1)
    expect(siteLinksAPI.list).toHaveBeenCalledTimes(2)
  })

  it('삭제 실패는 알린다', async () => {
    siteLinksAPI.remove.mockRejectedValue(new Error('network'))
    const w = await mountPage()

    await w.findAll('[data-test="card"]')[0].find('[data-test="delete"]').trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith('지우지 못했습니다.')
  })
})

describe('/sites 는 로그인 뒤 화면이고, 화면 파일에 주소를 적지 않는다', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const read = (rel) => readFileSync(resolve(here, rel), 'utf-8')

  it('라우트에 requiresAuth 가 걸려 있다', () => {
    const main = read('../../main.js').replace(/\r\n/g, '\n')
    const block = main.slice(main.indexOf("path: '/sites'"))
    expect(block).not.toBe('')
    expect(block.slice(0, block.indexOf('}'))).toContain('requiresAuth: true')
  })

  it('화면·유틸 소스에 실제 주소(도메인)가 없다 — 목록은 서버 표에만 둔다', () => {
    for (const rel of ['../SiteLinksPage.vue', '../../utils/siteLinks.js']) {
      const src = read(rel)
      expect(src).not.toMatch(/duckdns/i)
      expect(src).not.toMatch(/https?:\/\/[a-z0-9]/i)
    }
  })

  it('v-html 을 쓰지 않는다', () => {
    const template = read('../SiteLinksPage.vue').split('<script')[0].replace(/<!--[\s\S]*?-->/g, '')
    expect(template).not.toContain('v-html')
  })
})
