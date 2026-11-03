import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'

/**
 * 종목 상세 — 종목을 바꾼 뒤 늦게 온 이전 종목 응답(2026-10-19, 리뷰 에이전트 지적).
 * 재현: 보드 '다음 ▶'·검색으로 다른 종목으로 넘어가도 이전 종목 요청을 버리지 않아, 그 응답이 늦게 오면 새 종목 화면의
 * 이름·시세·진단 점수를 그대로 덮었다(시세·수급·재무·차트·리스크·AI·진단·차트 패턴 전부). 15초 자동 갱신도 같았다.
 * 하위 섹션(신호 이력·재료 이력·공시·유튜브 의견)은 이미 요청 순서 토큰으로 막고 있었다.
 *
 * 검색 모달(🔍·Ctrl+K)도 같이 본다: visible 을 넘기지 않아 한 번도 뜬 적이 없고(그 사이 showSearch 가 true 로 남아 차트 전체화면이
 * Esc 로 안 닫혔다), 고르면 종목 객체로 '/stock/[object Object]' 를 만들었으며, 같은 화면을 다시 쓰는 라우트라 주소가 바뀌어도
 * 다시 읽지 않았다(브라우저 뒤로 가기도 같다).
 */
const h = vi.hoisted(() => ({ route: null, router: null }))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  h.route = reactive({ params: { stockCode: '900001' }, query: {} })
  h.router = { push: vi.fn(), replace: vi.fn() }
  return { useRoute: () => h.route, useRouter: () => h.router }
})

vi.mock('../../utils/api', async (importOriginal) => {
  const actual = await importOriginal()
  const none = () => Promise.resolve({ data: { success: false } })
  return {
    ...actual,
    default: { get: vi.fn(none), post: vi.fn(none) },
    stockDetailAPI: { getQuick: vi.fn(), getHeavy: vi.fn(), getDiagnosis: vi.fn() },
    stockAPI: { searchStocks: vi.fn(none) },
    quantTaAPI: {
      patterns: vi.fn(none), supportResistance: vi.fn(none), volumeProfile: vi.fn(none),
      compositeSignal: vi.fn(none), relatedStocks: vi.fn(none)
    }
  }
})
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() } }))

import { stockDetailAPI, quantTaAPI } from '../../utils/api'
import StockDetailDashboard from '../StockDetailDashboard.vue'

const A = '900001'
const B = '900002'
const NEVER = () => new Promise(() => {})

const deferred = () => {
  let resolve
  const p = new Promise((r) => { resolve = r })
  return { p, resolve }
}
const quick = (name, price) => ({ data: { success: true, data: {
  stockName: name, price: { currentPrice: price, changePrice: 0, changeRate: 0 },
  supplyDemand: null, financial: null, chartData: null
} } })
const diagnosis = (score) => ({ data: { success: true, data: { overallScore: score, verdict: '관망' } } })

/** 요청을 종목별로 붙잡아 두고, 테스트가 정한 순서로 응답을 보낸다. */
const holdByCode = (fn) => {
  const held = {}
  fn.mockImplementation((code) => {
    const list = held[code] || (held[code] = [])
    const d = deferred()
    list.push(d)
    return d.p
  })
  return (code, value, i = 0) => held[code][i].resolve(value)
}

const mountDetail = () => shallowMount(StockDetailDashboard)
const nextOnBoard = (w) => w.findAll('.board-nav .bn-btn')[1].trigger('click')

describe('StockDetailDashboard — 종목 전환 뒤 늦게 온 이전 종목 응답', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    h.route.params.stockCode = A
    sessionStorage.setItem('judgmentBoard.nav', JSON.stringify({ codes: [A, B] }))
    stockDetailAPI.getHeavy.mockImplementation(NEVER)
    stockDetailAPI.getDiagnosis.mockImplementation(NEVER)
  })

  it('재현: 보드 다음 종목으로 넘어간 뒤 도착한 이전 종목 시세가 새 종목 이름·시세를 덮지 않는다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const w = mountDetail()
    await flushPromises()
    await nextOnBoard(w)

    answerQuick(B, quick('B회사', 2000))
    await flushPromises()
    answerQuick(A, quick('A회사', 1000))   // 이전 종목 응답이 늦게 도착
    await flushPromises()

    expect(w.find('.stock-name').text()).toBe('B회사')
    expect(w.find('.current-price').text()).toContain('2,000')
  })

  it('재현: 이전 종목의 진단(2단계)이 늦게 와도 새 종목 진단 점수를 덮지 않는다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const answerDiagnosis = holdByCode(stockDetailAPI.getDiagnosis)
    stockDetailAPI.getHeavy.mockResolvedValue({ data: { success: false } })
    const w = mountDetail()
    await flushPromises()
    answerQuick(A, quick('A회사', 1000))
    await flushPromises()

    await nextOnBoard(w)
    answerQuick(B, quick('B회사', 2000))
    await flushPromises()
    answerDiagnosis(B, diagnosis(77))
    await flushPromises()
    answerDiagnosis(A, diagnosis(11))   // 이전 종목 진단이 늦게 도착
    await flushPromises()

    expect(w.find('.ai-score-box .score-value').text()).toBe('77')
  })

  it('재현: 이전 종목의 종합 신호(차트 패턴 계열)가 늦게 와도 새 종목 헤더 배지를 덮지 않는다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const answerSignal = holdByCode(quantTaAPI.compositeSignal)
    const w = mountDetail()
    await flushPromises()
    answerQuick(A, quick('A회사', 1000))
    await flushPromises()

    await nextOnBoard(w)
    answerQuick(B, quick('B회사', 2000))
    await flushPromises()
    answerSignal(B, { data: { success: true, data: { matchedCount: 1, totalCount: 5, signals: [] } } })
    await flushPromises()
    answerSignal(A, { data: { success: true, data: { matchedCount: 4, totalCount: 5, signals: [] } } })
    await flushPromises()

    expect(w.find('.composite-badge').text()).toContain('신호 1/5')
  })

  it('재현: 전환 직전에 보낸 시세 갱신 응답이 늦게 와도 새 종목 시세를 덮지 않는다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const w = mountDetail()
    await flushPromises()
    answerQuick(A, quick('A회사', 1000))
    await flushPromises()

    // '방금' 갱신 → A 시세 재요청이 걸려 있는 동안 다음 종목으로
    w.findComponent({ name: 'DataFreshness' }).vm.$emit('refresh')
    await nextOnBoard(w)
    answerQuick(B, quick('B회사', 2000))
    await flushPromises()
    answerQuick(A, quick('A회사', 1500), 1)   // A 갱신 응답이 늦게 도착
    await flushPromises()

    expect(w.find('.current-price').text()).toContain('2,000')
    expect(w.find('.stock-name').text()).toBe('B회사')
  })

  it('재현: 새 종목을 읽는 동안 이전 종목 시세·진단 점수를 보이지 않는다 — 2단계(진단)는 수 초 걸린다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const answerDiagnosis = holdByCode(stockDetailAPI.getDiagnosis)
    stockDetailAPI.getHeavy.mockResolvedValue({ data: { success: false } })
    const w = mountDetail()
    await flushPromises()
    answerQuick(A, quick('A회사', 1000))
    await flushPromises()
    answerDiagnosis(A, diagnosis(11))
    await flushPromises()
    expect(w.find('.ai-score-box .score-value').text()).toBe('11')

    await nextOnBoard(w)
    await flushPromises()
    expect(w.find('.current-price').exists()).toBe(false)   // B 시세를 기다리는 중 — A 시세를 B 옆에 두지 않는다
    expect(w.find('.ai-score-box').exists()).toBe(false)

    answerQuick(B, quick('B회사', 2000))
    await flushPromises()
    expect(w.find('.current-price').text()).toContain('2,000')
    expect(w.find('.ai-score-box').exists()).toBe(false)    // B 진단은 아직 — A 의 11점을 B 옆에 두지 않는다
  })

  it('재현: 새 종목 시세 조회가 실패하면 이전 종목 이름·시세를 새 종목 화면에 남기지 않는다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const w = mountDetail()
    await flushPromises()
    answerQuick(A, quick('A회사', 1000))
    await flushPromises()

    await nextOnBoard(w)
    answerQuick(B, { data: { success: false } })
    await flushPromises()

    expect(w.find('.stock-name').text()).not.toBe('A회사')
    expect(w.find('.current-price').exists()).toBe(false)
  })

  it('정상 순서(이전 종목 응답이 먼저)면 새 종목으로 바뀐다', async () => {
    const answerQuick = holdByCode(stockDetailAPI.getQuick)
    const w = mountDetail()
    await flushPromises()
    await nextOnBoard(w)
    answerQuick(A, quick('A회사', 1000))
    await flushPromises()
    answerQuick(B, quick('B회사', 2000))
    await flushPromises()

    expect(w.find('.stock-name').text()).toBe('B회사')
    expect(w.find('.current-price').text()).toContain('2,000')
  })
})

// 2단계(리스크·AI·진단)까지 끝나야 로딩 막이 걷히고 차트가 보인다
const loadEverything = () => {
  stockDetailAPI.getHeavy.mockResolvedValue({ data: { success: false } })
  stockDetailAPI.getDiagnosis.mockResolvedValue({ data: { success: false } })
}

describe('StockDetailDashboard — 검색 모달·주소 이동', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    h.route.params.stockCode = A
    sessionStorage.removeItem('judgmentBoard.nav')
    stockDetailAPI.getQuick.mockImplementation((code) => Promise.resolve(quick(code === A ? 'A회사' : 'B회사', code === A ? 1000 : 2000)))
    stockDetailAPI.getHeavy.mockImplementation(NEVER)
    stockDetailAPI.getDiagnosis.mockImplementation(NEVER)
  })

  it('재현: 🔍 를 누르면 검색 모달이 실제로 뜬다(visible 을 넘긴다)', async () => {
    const w = mountDetail()
    await flushPromises()
    await w.find('.search-btn').trigger('click')
    expect(w.findComponent({ name: 'StockSearchModal' }).props('visible')).toBe(true)
  })

  it('재현: 모달에서 종목을 고르면 그 종목코드로 이동하고 바로 다시 읽는다 — [object Object] 를 만들지 않는다', async () => {
    const w = mountDetail()
    await flushPromises()
    await w.find('.search-btn').trigger('click')
    w.findComponent({ name: 'StockSearchModal' }).vm.$emit('select', { stockCode: B, stockName: 'B회사' })
    await flushPromises()

    expect(h.router.push).toHaveBeenCalledWith('/stock/' + B)
    expect(w.find('.stock-name').text()).toBe('B회사')
    expect(w.findComponent({ name: 'StockSearchModal' }).props('visible')).toBe(false)
  })

  it('재현: 주소의 종목코드가 바뀌면(뒤로 가기 등) 그 종목을 다시 읽는다', async () => {
    const w = mountDetail()
    await flushPromises()
    expect(w.find('.stock-name').text()).toBe('A회사')

    h.route.params.stockCode = B
    await flushPromises()

    expect(w.find('.stock-name').text()).toBe('B회사')
  })

  it('재현: 모달이 열린 채 Esc 로 닫아도 차트 전체화면은 그대로 — 모달을 닫은 Esc 가 차트까지 닫지 않는다', async () => {
    loadEverything()
    const w = shallowMount(StockDetailDashboard, { attachTo: document.body, global: { stubs: { StockSearchModal: false } } })
    await flushPromises()
    await w.find('.chart-section button[title="차트 크게 보기"]').trigger('click')
    expect(w.find('.chart-section').classes()).toContain('fullscreen')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()
    const input = w.find('.search-modal input')
    expect(input.exists()).toBe(true)
    input.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()

    expect(w.find('.search-modal').exists()).toBe(false)
    expect(w.find('.chart-section').classes()).toContain('fullscreen')
    w.unmount()
  })

  it('모달이 닫혀 있으면 Esc 가 차트 전체화면을 닫는다', async () => {
    loadEverything()
    const w = shallowMount(StockDetailDashboard, { attachTo: document.body })
    await flushPromises()
    await w.find('.chart-section button[title="차트 크게 보기"]').trigger('click')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(w.find('.chart-section').classes()).not.toContain('fullscreen')
    w.unmount()
  })
})
