import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../utils/api', () => ({
  recommendationAPI: { getLeaders: vi.fn() }
}))

import SectionLeaders from './SectionLeaders.vue'
import { recommendationAPI } from '../../utils/api'
import { LEADERS_LIST_BASIS } from '../../utils/marketDataLabels'

// 11/18 운영 자료로 뽑아 본 표본(11/17 기준 값) — 1위 SK하이닉스 · 2위 삼성전자
const LIST = {
  basisDate: '2026-11-18', stale: false, rankedCount: 281, minMarketCapEok: 10000, windowDays: 20,
  netAvailable: true, netFrom: '2026-10-22', netThrough: '2026-11-18',
  items: [
    { rank: 1, stockCode: '000660', stockName: 'SK하이닉스', market: 'KOSPI', marketCapEok: 8000000, closePrice: 1098000,
      avgTradedValueEok: 49484.12, return20d: -10.2, return60d: -26.8, netBuyEok: -63994, netBuyDays: 20 },
    { rank: 2, stockCode: '005930', stockName: '삼성전자', market: 'KOSPI', marketCapEok: 11200000, closePrice: 190300,
      avgTradedValueEok: 37847.5, return20d: -10.0, return60d: null, netBuyEok: 1234.4, netBuyDays: 12 },
    { rank: 3, stockCode: '009150', stockName: '삼성전기', market: 'KOSPI', marketCapEok: 250000, closePrice: 300000,
      avgTradedValueEok: 5120, return20d: 3.44, return60d: 21.0, netBuyEok: null, netBuyDays: null }
  ]
}

const ok = (data) => ({ data: { success: true, dataAvailable: true, data } })

const mountWith = async (response) => {
  if (response instanceof Error) recommendationAPI.getLeaders.mockRejectedValue(response)
  else recommendationAPI.getLeaders.mockResolvedValue(response)
  const w = mount(SectionLeaders)
  await flushPromises()
  return w
}

/**
 * 발굴 → 👑 주도주 목록(2026-11-19).
 * 만든 까닭: 사용자가 원하는 것은 '저평가 종목'과 '국내 주도주(삼성전자·SK하이닉스 같은)'인데 주도주를 보여 주는 목록이 없었다.
 * 시가총액 1조 원 이상을 20거래일 평균 거래대금 순으로 세운다 — 표시 전용이고 '사라'는 신호가 아니다.
 */
describe('SectionLeaders', () => {
  beforeEach(() => {
    recommendationAPI.getLeaders.mockReset()
  })

  it('순위·기준일·값을 보인다 — 금액은 조/억, 등락은 부호와 함께', async () => {
    const w = await mountWith(ok(LIST))
    const text = w.text()
    expect(text).toContain('주도주 TOP 3')
    expect(text).toContain('11.18 종가 기준')
    expect(text).not.toContain('갱신 지연')
    const rows = w.findAll('.leaders-item')
    expect(rows).toHaveLength(3)
    const first = rows[0].text()
    expect(first).toContain('SK하이닉스')
    expect(first).toContain('800조')        // 시가총액 8,000,000억
    expect(first).toContain('4.9조')        // 20일 평균 거래대금 49,484억
    expect(first).toContain('-10.2%')
    expect(first).toContain('-26.8%')
    expect(first).toContain('-6.4조')       // 외국인+기관 20거래일 −63,994억
    expect(recommendationAPI.getLeaders).toHaveBeenCalledWith(20)
  })

  it('모르는 값은 0 이 아니라 "-" — 60거래일 전 봉이 없는 종목, 수급 표에 없는 종목', async () => {
    const w = await mountWith(ok(LIST))
    const rows = w.findAll('.leaders-item')
    const samsungCells = rows[1].findAll('.c-num').map(c => c.text())
    expect(samsungCells[3]).toBe('-')                          // 60거래일 등락 모름
    expect(samsungCells[4]).toBe('+1,234억 (12일치)')            // 창(20거래일)보다 적은 날만 있다
    const semcoCells = rows[2].findAll('.c-num').map(c => c.text())
    expect(semcoCells[4]).toBe('-')                            // 수급 표에 행이 없다
    expect(rows[2].findAll('.c-num')[4].classes()).not.toContain('positive')
    expect(rows[2].findAll('.c-num')[4].classes()).not.toContain('negative')
  })

  it('오른 값은 positive, 내린 값은 negative 로 칠한다', async () => {
    const w = await mountWith(ok(LIST))
    const cells = w.findAll('.leaders-item')[2].findAll('.c-num')
    expect(cells[2].classes()).toContain('positive')    // +3.4%
    expect(cells[2].text()).toBe('+3.4%')
    expect(w.findAll('.leaders-item')[0].findAll('.c-num')[2].classes()).toContain('negative')
  })

  it('줄을 누르면 그 종목을 연다', async () => {
    const w = await mountWith(ok(LIST))
    await w.findAll('.leaders-item')[1].trigger('click')
    expect(w.emitted('open-stock')).toEqual([['005930']])
  })

  it('무엇으로 세웠는지·사라는 신호가 아니라는 것·성과 검증 전이라는 문구를 보인다', async () => {
    const w = await mountWith(ok(LIST))
    expect(w.text()).toContain(LEADERS_LIST_BASIS)
    expect(LEADERS_LIST_BASIS).toContain('시가총액 1조 원 이상')
    expect(LEADERS_LIST_BASIS).toContain('20거래일 평균 거래대금')
    expect(LEADERS_LIST_BASIS).toContain('사라는 신호가 아니')
    expect(LEADERS_LIST_BASIS).toContain('검증 전')
    expect(w.text()).toContain('순위를 매긴 281종목 중 위 3개')
  })

  it('갱신이 이틀 넘게 밀렸으면 기준일 옆에 "갱신 지연"', async () => {
    const w = await mountWith(ok({ ...LIST, stale: true }))
    expect(w.text()).toContain('11.18 종가 기준 · 갱신 지연')
    expect(w.find('.leaders-basis-date').classes()).toContain('stale')
  })

  it('수급 표가 기준일보다 앞에서 끝나면 머리말에 그 날짜를 적고, 표를 못 읽었으면 조회 실패라고 적는다', async () => {
    const early = await mountWith(ok({ ...LIST, netThrough: '2026-11-17' }))
    expect(early.find('.leaders-head').text()).toContain('외국인+기관 20거래일(11.17까지)')

    const failed = await mountWith(ok({ ...LIST, netAvailable: false, netThrough: null }))
    expect(failed.find('.leaders-head').text()).toContain('외국인+기관(조회 실패)')
  })

  it('아직 한 번도 계산하지 않았으면 그렇게 말한다 — 조회 실패와 구분', async () => {
    const w = await mountWith(ok({ basisDate: null, stale: false, rankedCount: 0, items: [] }))
    expect(w.text()).toContain('아직 계산하지 않았습니다')
    expect(w.text()).not.toContain('불러오지 못했습니다')
    expect(w.findAll('.leaders-item')).toHaveLength(0)
  })

  it('재현 방지: 서버가 조회 실패(dataAvailable=false)라고 하면 "계산 전"이 아니라 "불러오지 못했습니다"', async () => {
    const w = await mountWith({ data: { success: false, dataAvailable: false } })
    expect(w.text()).toContain('주도주 목록을 불러오지 못했습니다')
    expect(w.text()).not.toContain('아직 계산하지 않았습니다')
  })

  it('네트워크 실패도 "불러오지 못했습니다" — 받아 둔 목록이 있으면 그대로 보인다', async () => {
    const failed = await mountWith(new Error('network'))
    expect(failed.text()).toContain('주도주 목록을 불러오지 못했습니다')

    const w = await mountWith(ok(LIST))
    recommendationAPI.getLeaders.mockRejectedValue(new Error('network'))
    expect(await w.vm.load()).toBe(false)
    await flushPromises()
    expect(w.findAll('.leaders-item')).toHaveLength(3)
    expect(w.text()).not.toContain('불러오지 못했습니다')
  })
})
