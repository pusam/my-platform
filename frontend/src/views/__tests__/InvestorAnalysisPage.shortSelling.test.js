import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'

/**
 * 시장 탭 수급 → 공매도(2026-10-02) — 잔고 출처(KRX·네이버)가 죽어 KIS 공매도 '거래 비중'으로 옮겼다.
 * ① 잔고가 아니라 거래 비중이라는 것 ② 기준일·출처 ③ '조회 실패'·'아직 수집 전'·'마지막 수집 실패'를 서로 다른
 * 문구로(§4c) ④ 판정용 색(5%·10% 잔고 기준)을 거래 비중에 칠하지 않는다 — 를 고정한다.
 */
vi.mock('../../utils/api', () => ({
  // 수급 탭의 다른 호출(매매 동향·연속 매수)은 빈 결과로 — vi.mock 은 끌어올려지므로 팩토리 안에서 만든다
  investorAPI: new Proxy({}, { get: () => () => Promise.resolve({ data: { success: true, data: [] } }) }),
  shortSellingAPI: { getTop: vi.fn() }
}))
vi.mock('../../utils/toast', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../../composables/useAutoRefresh.js', () => ({
  useAutoRefresh: () => ({ lastUpdated: ref(null), isRefreshing: ref(false), nextRefreshIn: ref(0), manualRefresh: vi.fn() })
}))

import { shortSellingAPI } from '../../utils/api'
import InvestorAnalysisPage from '../InvestorAnalysisPage.vue'

const ROWS = [
  { stockCode: '005930', stockName: '삼성전자', tradeDate: '2026-10-01', rank: 1, shortVolume: 960000,
    shortVolumeShare: 12.5, shortAmount: 68640000000, shortAmountShare: 12.3, totalVolume: 7680000 },
  { stockCode: '000660', stockName: 'SK하이닉스', tradeDate: '2026-10-01', rank: 2, shortVolume: 0,
    shortVolumeShare: 0, shortAmount: 0, shortAmountShare: 0, totalVolume: 3000000 }
]

const respond = (body) => shortSellingAPI.getTop.mockResolvedValue({ data: { success: true, ...body } })

const openShortTab = async () => {
  const w = shallowMount(InvestorAnalysisPage, {
    props: { embedded: true },
    global: { stubs: { LoadingSpinner: true, BackButton: true, DataFreshness: true } }
  })
  await flushPromises()
  const btn = w.findAll('.main-tab-btn').find(b => b.text().includes('공매도'))
  await btn.trigger('click')
  await flushPromises()
  return w
}

describe('InvestorAnalysisPage — 공매도 거래 비중', () => {
  beforeEach(() => shortSellingAPI.getTop.mockReset())

  it('거래 비중이지 잔고가 아니라는 것과 출처·기준일을 밝힌다 — 네이버를 말하지 않는다', async () => {
    respond({ dataAvailable: true, asOf: '2026-10-01', data: ROWS, lastCollection: null })
    const w = await openShortTab()

    expect(w.text()).toContain('공매도 거래 비중')
    expect(w.text()).toContain('잔고가 아닙니다')
    expect(w.text()).toContain('KIS')
    expect(w.find('.short-asof').text()).toContain('10/1')
    expect(w.text()).not.toContain('네이버')
  })

  it('행: 순위·거래량 비중(%)·수량 — 실측 0 은 0.00%', async () => {
    respond({ dataAvailable: true, asOf: '2026-10-01', data: ROWS, lastCollection: null })
    const w = await openShortTab()
    const rows = w.findAll('tr.short-row')

    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('삼성전자')
    expect(rows[0].find('.ratio-cell').text()).toBe('12.50%')
    expect(rows[1].find('.ratio-cell').text()).toBe('0.00%')
  })

  it('잔고 기준(5%·10%) 색을 거래 비중에 칠하지 않는다 — 판정 안 함', async () => {
    respond({ dataAvailable: true, asOf: '2026-10-01', data: ROWS, lastCollection: null })
    const w = await openShortTab()

    expect(w.findAll('.ratio-very-high, .ratio-high, .ratio-medium')).toHaveLength(0)
  })

  it('아직 수집 전이면 그렇게 말하고 언제 수집하는지 알려 준다', async () => {
    respond({ dataAvailable: true, asOf: null, data: [], lastCollection: null })
    const w = await openShortTab()

    expect(w.find('.no-data').text()).toContain('아직 수집된 공매도 거래 비중이 없습니다')
    expect(w.find('.no-data').text()).toContain('18:30')
  })

  it('마지막 수집이 실패했으면 그 사유를 보인다', async () => {
    respond({ dataAvailable: true, asOf: null, data: [],
      lastCollection: { ok: false, stored: 0, message: 'KIS 오류 rt_cd=1 INPUT FIELD NOT FOUND' } })
    const w = await openShortTab()

    expect(w.find('.no-data').text()).toContain('INPUT FIELD NOT FOUND')
  })

  it('조회 실패는 "데이터 없음"과 다른 문구 — dataAvailable=false', async () => {
    respond({ dataAvailable: false, asOf: null, data: [], lastCollection: null })
    const w = await openShortTab()

    expect(w.find('.no-data').text()).toContain('불러오지 못했습니다')
    expect(w.find('.no-data').text()).not.toContain('아직 수집된')
  })
})
