import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TodayBriefingTab from './TodayBriefingTab.vue'
import { todayKstIso } from '../../utils/stockFormat'
import apiClient, { recommendationAPI, paperTradingAPI, youtubeOpinionAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() },
  recommendationAPI: { getTop5: vi.fn(), getTrendPullbackTop10: vi.fn() },
  paperTradingAPI: { getPortfolio: vi.fn() },
  youtubeOpinionAPI: { getSummary: vi.fn(), getStock: vi.fn() }
}))

const top5Response = {
  data: { success: true, dataTime: '10:30 기준', realtime: true, data: [
    { stockCode: '005930', stockName: '삼성전자', totalScore: 82, tags: ['외국인3일연속', '골든크로스'], currentPrice: 70000, changeRate: 1.2 },
    { stockCode: '000660', stockName: 'SK하이닉스', totalScore: 61, tags: ['기관순매수'], currentPrice: 210000, changeRate: -0.5 },
    { stockCode: '035420', stockName: 'NAVER', totalScore: 48, tags: [], currentPrice: 180000, changeRate: 0.1 }
  ] }
}

// accuracy-by-band(보드 격리 + phase-38 컷오프) — 신뢰도 스트립 입력
const bandAccuracyResponse = {
  data: { success: true, data: {
    since: '2026-06-25',
    bands: [
      { band: '55~64', scoreFrom: 55, scoreTo: 64, totalSignals: 115, hitCount: 41, hitRate: 35.65, avgPctChange: -2.83 },
      { band: '65~74', scoreFrom: 65, scoreTo: 74, totalSignals: 8, hitCount: 2, hitRate: 25.0, avgPctChange: -3.15 },
      { band: '75~84', scoreFrom: 75, scoreTo: 84, totalSignals: 0, hitCount: 0, hitRate: 0 }
    ]
  } }
}

const catalystResponse = {
  data: { success: true, data: { catalystType: 'ORDER_WIN', typeLabel: '수주', direction: 'POSITIVE', summary: '대형 공급계약' } }
}

function stubAll({ catalyst = catalystResponse, portfolio = { data: { success: true, data: [] } } } = {}) {
  recommendationAPI.getTop5.mockResolvedValue(top5Response)
  recommendationAPI.getTrendPullbackTop10.mockResolvedValue({ data: { success: true, data: [] } })
  paperTradingAPI.getPortfolio.mockResolvedValue(portfolio)
  // 유튜브 참고 의견 — 기본은 기능 꺼짐(기존 테스트의 화면을 바꾸지 않는다)
  youtubeOpinionAPI.getSummary.mockResolvedValue({ data: { success: true, data: { enabled: false } } })
  apiClient.get.mockImplementation((url) => {
    if (url.includes('/catalyst')) return Promise.resolve(catalyst)
    if (url.includes('accuracy-by-band')) return Promise.resolve(bandAccuracyResponse)
    return Promise.resolve({ data: { success: false } })
  })
}

async function mountTab(props = {}) {
  const wrapper = mount(TodayBriefingTab, { props })
  await flushPromises()
  return wrapper
}

describe('TodayBriefingTab — 오늘의 결론 홈', () => {
  it('부모 갱신을 받으면 새 후보를 읽어 이전 후보를 교체한다', async () => {
    stubAll()
    const w = await mountTab()
    recommendationAPI.getTop5.mockResolvedValue({ data: { success: true, data: [] } })
    await w.vm.refresh()
    await flushPromises()
    expect(w.findAll('.candidate-card')).toHaveLength(0)
    expect(w.text()).toContain('관망이 결론입니다')
  })
  it('60초 갱신 중에는 기존 후보를 비우지 않는다 — 자리표시는 첫 로드에만', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.findAll('.candidate-card')).toHaveLength(2)
    let resolveNext
    recommendationAPI.getTop5.mockImplementation(() => new Promise(r => { resolveNext = r }))
    const pending = w.vm.refresh()
    w.vm.refresh()                       // 진행 중 중복 호출은 무시
    await flushPromises()
    expect(w.findAll('.candidate-card')).toHaveLength(2)
    expect(w.text()).not.toContain('후보 분석 중')
    expect(recommendationAPI.getTop5).toHaveBeenCalledTimes(2)   // mount 1 + refresh 1
    resolveNext({ data: { success: true, data: [] } })
    await pending
    await flushPromises()
    expect(w.findAll('.candidate-card')).toHaveLength(0)
  })
  it.each([
    { success: false, data: [] },
    { success: true, dataAvailable: false, data: [] },
    { success: true, data: null }
  ])('판단 불가 응답은 관망으로 표시하지 않는다: %j', async (payload) => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: payload })
    const w = await mountTab()
    expect(w.find('.ts-state.failed').exists()).toBe(true)
    expect(w.text()).not.toContain('관망이 결론입니다')
  })

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('55점 이상만 후보로 — 82점/61점 표시, 48점 제외', async () => {
    stubAll()
    const w = await mountTab()
    const cards = w.findAll('.candidate-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('삼성전자')
    expect(cards[0].text()).toContain('강력 매수')   // 82 ≥ 75
    expect(cards[1].text()).toContain('SK하이닉스')
    expect(cards[1].text()).toContain('매수')
    expect(w.text()).not.toContain('NAVER')
  })

  it('재료 배지 — 🔥 재료: 수주(호재)', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.find('.cc-catalyst').exists()).toBe(true)
    expect(w.find('.cc-catalyst').text()).toContain('재료: 수주(호재)')
  })

  it('차트 신호 관찰 — 기본 접힘(매수신호 아님·31%) → 펼치면 카드(점수 미표시)', async () => {
    stubAll()
    recommendationAPI.getTrendPullbackTop10.mockResolvedValue({ data: { success: true, data: [
      { code: '207940', name: '삼성바이오로직스', signals: ['정배열', '엔벨로프눌림'], timingScore: 8 }
    ] } })
    const w = await mountTab()
    // 기본 접힘 — 제목 '차트 타이밍 관찰' + 실측 한 줄(31%·매수신호 아님), 배너/카드 숨김
    expect(w.text()).toContain('차트 타이밍 관찰')
    expect(w.find('.observe-collapsed').exists()).toBe(true)
    expect(w.find('.observe-collapsed').text()).toContain('31%')
    expect(w.find('.beta-banner').exists()).toBe(false)
    // 펼치기 → 배너(매수 신호 아님) + 종목, 단 점수(8/10)는 미표시(역상관 오해 방지)
    await w.find('.ts-toggle').trigger('click')
    expect(w.find('.beta-banner').exists()).toBe(true)
    expect(w.find('.beta-banner').text()).toContain('매수 신호 아님')
    expect(w.text()).toContain('삼성바이오로직스')
    expect(w.text()).not.toContain('8/10')
  })

  it('차트 타이밍 후보 0건이면 베타 섹션 숨김', async () => {
    stubAll()   // getTrendPullbackTop10 → 빈 배열(기본)
    const w = await mountTab()
    expect(w.find('.beta-banner').exists()).toBe(false)
  })

  it('재료 NONE 이면 배지 생략', async () => {
    stubAll({ catalyst: { data: { success: true, data: { catalystType: 'NONE', direction: 'NONE' } } } })
    const w = await mountTab()
    expect(w.find('.cc-catalyst').exists()).toBe(false)
  })

  it('후보 0건 → 관망 메시지', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: { success: true, data: [
      { stockCode: '035420', stockName: 'NAVER', totalScore: 48 }
    ] } })
    const w = await mountTab()
    expect(w.find('.ts-state.empty').text()).toContain('관망')
  })

  it('신뢰도 스트립 — 실측 밴드(보드 격리·컷오프) 표시 + 표본부족 구분', async () => {
    stubAll()
    const w = await mountTab()
    const trust = w.find('.today-trust')
    expect(trust.exists()).toBe(true)
    expect(trust.text()).toContain('2026-06-25')
    expect(trust.text()).toContain('55~64점 35.65%')
    expect(trust.text()).toContain('115건')
    expect(trust.text()).toContain('표본부족')      // 65~74 (n=8)
    expect(trust.text()).not.toContain('75~84')     // n=0 밴드 미표시
  })

  // ── 2026-10-01 감사: 현재 산식 표본만(경계 이후·교정 D+3) — 미정이면 옛 수치로 채우지 않는다 ──
  function stubBand(data) {
    stubAll()
    apiClient.get.mockImplementation((url) => {
      if (url.includes('accuracy-by-band')) return Promise.resolve({ data: { success: true, data } })
      return Promise.resolve({ data: { success: false } })
    })
  }

  it('표본 시작일 미정이면 옛 수치 대신 "검증 중" — 현재 산식 성적이 아직 없다', async () => {
    stubBand({ sampleStatus: 'UNSET', sampleSince: null, since: null, basis: 'D3_CORRECTED',
      evaluatedCount: 0, typeStats: [], bands: [] })
    const w = await mountTab()
    const trust = w.find('.today-trust')
    expect(trust.exists()).toBe(true)
    expect(trust.text()).toContain('검증 중')
    expect(trust.text()).toContain('표본 시작일 미정')
    expect(trust.text()).not.toContain('%')
  })

  it('잠정 경계 — 시작일·잠정·D+3 종가 기준을 밝힌다', async () => {
    stubBand({ sampleStatus: 'PROVISIONAL', sampleSince: '2026-10-05', since: '2026-10-05', basis: 'D3_CORRECTED',
      evaluatedCount: 12, bands: [{ band: '55~64', scoreFrom: 55, scoreTo: 64, totalSignals: 12, hitCount: 5, hitRate: 41.67 }] })
    const w = await mountTab()
    const trust = w.find('.today-trust')
    expect(trust.text()).toContain('2026-10-05~')
    expect(trust.text()).toContain('잠정')
    expect(trust.text()).toContain('D+3')
    expect(trust.text()).toContain('55~64점 41.67%')
  })

  it('경계는 정해졌지만 평가된 표본이 아직 없으면 검증 중 — 첫 평가는 3거래일 뒤', async () => {
    stubBand({ sampleStatus: 'CONFIRMED', sampleSince: '2026-10-05', since: '2026-10-05', basis: 'D3_CORRECTED',
      evaluatedCount: 0, typeStats: [], bands: [{ band: '55~64', scoreFrom: 55, scoreTo: 64, totalSignals: 0, hitCount: 0, hitRate: 0 }] })
    const w = await mountTab()
    const trust = w.find('.today-trust')
    expect(trust.text()).toContain('검증 중')
    expect(trust.text()).toContain('2026-10-05')
    expect(trust.text()).not.toContain('%')
  })

  // ── 2026-10-07: 적중률 조회 실패를 숨기지 않는다 — 스트립이 사라지면 '강력 매수' 배지만 설명 없이 남았다 ──
  function stubTrust(trust) {
    stubAll()
    apiClient.get.mockImplementation((url) => {
      if (url.includes('accuracy-by-band')) return trust()
      return Promise.resolve({ data: { success: false } })
    })
  }

  it('재현: 적중률 조회가 실패하면 스트립이 사라지지 않고 "확인할 수 없다"고 말한다 — 강력 매수 배지만 남지 않게', async () => {
    stubTrust(() => Promise.reject(new Error('500')))
    const w = await mountTab()
    expect(w.find('.grade-strong').exists()).toBe(true)            // 82점 '강력 매수' 후보
    const trust = w.find('.today-trust')
    expect(trust.exists()).toBe(true)
    expect(trust.text()).toContain('불러오지 못했습니다')
    expect(trust.text()).toContain('확인할 수 없습니다')
    expect(trust.text()).not.toContain('%')                         // 성적을 지어내지 않는다
    expect(trust.text()).not.toContain('검증 중')                    // '아직 성적 없음'과 다르다
  })

  it('서버가 실패로 답해도(success=false) 같은 실패 문구', async () => {
    stubTrust(() => Promise.resolve({ data: { success: false } }))
    const w = await mountTab()
    expect(w.find('.today-trust').text()).toContain('불러오지 못했습니다')
  })

  it('다시 시도로 받으면 실측 밴드로 바뀐다', async () => {
    let fail = true
    stubTrust(() => (fail ? Promise.reject(new Error('500')) : Promise.resolve(bandAccuracyResponse)))
    const w = await mountTab()
    fail = false
    await w.find('.today-trust .retry-btn').trigger('click')
    await flushPromises()
    expect(w.find('.today-trust').text()).toContain('55~64점 35.65%')
    expect(w.find('.today-trust').text()).not.toContain('불러오지 못했습니다')
  })

  it('60초 갱신이 실패한 적중률을 다시 읽는다 — 받은 뒤엔 다시 부르지 않는다', async () => {
    let fail = true
    stubTrust(() => (fail ? Promise.reject(new Error('500')) : Promise.resolve(bandAccuracyResponse)))
    const w = await mountTab()
    fail = false
    await w.vm.refresh()
    await flushPromises()
    expect(w.find('.today-trust').text()).toContain('55~64점 35.65%')
    const calls = () => apiClient.get.mock.calls.filter(([u]) => u.includes('accuracy-by-band')).length
    expect(calls()).toBe(2)
    await w.vm.refresh()
    await flushPromises()
    expect(calls()).toBe(2)
  })

  it('신뢰도 — 적중률 50% 미만이면 경고 문구 표시(성적 미화 금지)', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.find('.tt-caution').exists()).toBe(true)
    expect(w.find('.tt-caution').text()).toContain('50% 미만')
  })

  it('as-of — dataTime 표시, realtime=false 면 스냅샷 배지', async () => {
    stubAll()
    let w = await mountTab()
    expect(w.find('.ts-asof').text()).toContain('10:30 기준')
    expect(w.find('.ts-stale').exists()).toBe(false)

    recommendationAPI.getTop5.mockResolvedValue({
      data: { ...top5Response.data, dataTime: '07/25 20:05 기준 (종가)', realtime: false }
    })
    w = await mountTab()
    expect(w.find('.ts-stale').exists()).toBe(true)
    expect(w.find('.ts-asof').text()).toContain('07/25 20:05')
  })

  it('재료 배지 — read-only 조회(stockName 미전달 = Gemini 신규 분류 트리거 금지)', async () => {
    stubAll()
    await mountTab()
    const catalystCalls = apiClient.get.mock.calls.filter(([url]) => url.includes('/catalyst'))
    expect(catalystCalls.length).toBeGreaterThan(0)
    for (const call of catalystCalls) {
      expect(call[1]?.params?.stockName).toBeUndefined()
    }
  })

  it('재료 배지 — 2일 초과 경과 재료는 생략, 1일 전은 경과일 표기', async () => {
    const oldDate = new Date(Date.now() - 5 * 86400000).toISOString().slice(0, 10)
    stubAll({ catalyst: { data: { success: true, data: {
      catalystType: 'ORDER_WIN', typeLabel: '수주', direction: 'POSITIVE', catalystDate: oldDate
    } } } })
    let w = await mountTab()
    expect(w.find('.cc-catalyst').exists()).toBe(false)

    const yesterday = new Date(Date.now() - 1 * 86400000).toISOString().slice(0, 10)
    stubAll({ catalyst: { data: { success: true, data: {
      catalystType: 'ORDER_WIN', typeLabel: '수주', direction: 'POSITIVE', catalystDate: yesterday
    } } } })
    w = await mountTab()
    expect(w.find('.cc-catalyst').exists()).toBe(true)
    expect(w.find('.cc-catalyst').text()).toContain('1일 전')
  })

  it('⚠ 경고 태그는 잘리지 않고 우선 표시', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({
      data: { ...top5Response.data, data: [
        { stockCode: '005930', stockName: '삼성전자', totalScore: 82,
          tags: ['외국인3일연속', '골든크로스', '⚠리스크공시', 'regime:BEAR'] }
      ] }
    })
    const w = await mountTab()
    const warn = w.find('.cc-tag-warn')
    expect(warn.exists()).toBe(true)
    expect(warn.text()).toContain('⚠리스크공시')
  })

  // 2026-11-10 재현(운영 08:18 파세코): 시장 국면 가중 내부 태그가 'regime:BULL' 그대로 카드에 보였다.
  it('재현: 국면 가중 태그는 사람 말로 — "regime:BULL" 이 그대로 보이지 않는다', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({
      data: { ...top5Response.data, data: [
        { stockCode: '082740', stockName: '파세코', totalScore: 58, tags: ['흑자전환', '골든크로스', 'regime:BULL'] },
        { stockCode: '000660', stockName: 'SK하이닉스', totalScore: 61, tags: ['regime:BEAR'] }
      ] }
    })
    const w = await mountTab()
    const text = w.findAll('.cc-tag').map(t => t.text()).join(' ')
    expect(text).not.toContain('regime:')
    expect(text).toContain('강세장 가중')
    expect(text).toContain('약세장 가중')
  })

  it('포지션 있으면 요약 표시 + 평가손익 합산', async () => {
    stubAll({ portfolio: { data: { success: true, data: [
      { stockCode: '005930', stockName: '삼성전자', quantity: 10, profitLoss: 50000, profitRate: 7.1 },
      { stockCode: '000660', stockName: 'SK하이닉스', quantity: 2, profitLoss: -20000, profitRate: -4.5 }
    ] } } })
    const w = await mountTab()
    expect(w.text()).toContain('모의투자 포지션 2종목')
    expect(w.text()).toContain('+30,000원')
  })

  it('포지션 비어있으면 섹션 숨김', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.text()).not.toContain('포지션')
  })

  // 2026-10-03: 평가손익이 DB 에 저장된 마지막 시세 그대로였다(현재가 갱신은 매매 탭의 버튼을 눌러야만) — 봇이 꺼진 뒤로는
  // 며칠 묵은 손익이 '오늘' 탭에 떴다. 이 목록은 모의투자 계좌인데 '내 포지션'(실계좌처럼)이라 불렀고, 조회 실패는 섹션을 숨겼다.
  it('재현: 현재가를 갱신해 받아 오고(refresh) 시세 기준 시각을 보인다', async () => {
    stubAll({ portfolio: { data: { success: true, data: [
      { stockCode: '005930', stockName: '삼성전자', quantity: 10, profitLoss: 50000, profitRate: 7.1, updatedAt: '2026-10-02T15:31:00' },
      { stockCode: '000660', stockName: 'SK하이닉스', quantity: 2, profitLoss: -20000, profitRate: -4.5, updatedAt: '2026-10-02T15:30:00' }
    ] } } })
    const w = await mountTab()
    expect(paperTradingAPI.getPortfolio).toHaveBeenCalledWith(true)
    expect(w.text()).toContain('10/02 15:31 시세 기준')
  })

  it('재현: 포지션 조회 실패는 숨기지 않고 실패라고 말한다', async () => {
    stubAll()
    paperTradingAPI.getPortfolio.mockRejectedValue(new Error('500'))
    const w = await mountTab()
    expect(w.text()).toContain('모의투자 포지션을 불러오지 못했습니다')
  })

  it('후보 클릭 → open-stock emit, "매매 탭 전체 보기" → navigate emit', async () => {
    stubAll({ portfolio: { data: { success: true, data: [
      { stockCode: '005930', stockName: '삼성전자', quantity: 10, profitRate: 2.1 }
    ] } } })
    const w = await mountTab()
    await w.find('.candidate-card').trigger('click')
    expect(w.emitted('open-stock')[0]).toEqual(['005930'])
    await w.find('.ts-more').trigger('click')
    expect(w.emitted('navigate')[0]).toEqual(['trade'])
  })

  it('marketData prop 있으면 시장 한 줄 표시', async () => {
    stubAll()
    const w = await mountTab({ marketData: { kospiIndex: '2,712.14', kospiChangeRate: 0.8, kosdaqIndex: '870.10', kosdaqChangeRate: -0.3, adr: 95 } })
    const market = w.find('.today-market')
    expect(market.exists()).toBe(true)
    expect(market.text()).toContain('KOSPI 2,712.14 (+0.8%)')
    expect(market.text()).toContain('ADR 95')
  })

  it('진단 문구에 ADR 이 이미 있으면 ADR 숫자를 따로 또 쓰지 않는다 — 같은 값이 두 번(84.68·84.7) 나왔다', async () => {
    stubAll()
    const w = await mountTab({ marketData: { kospiIndex: '6,896.54', kospiChangeRate: 0.86, kosdaqIndex: '879.47', kosdaqChangeRate: 2.75,
      adr: 84.68, marketStatus: '종합 ADR(20일): 84.7 - 시장이 정상 범위입니다.' } })
    const market = w.find('.today-market')
    expect(market.find('.tm-adr').exists()).toBe(false)
    expect(market.text().match(/ADR/g)).toHaveLength(1)
    expect(market.text()).toContain('종합 ADR(20일): 84.7')
  })

  it('API 전부 실패해도 빈 화면이 되지 않고, 조회 실패임을 밝힌다', async () => {
    // 2026-08-05 감사: 예전엔 조회 실패도 '관망이 결론입니다'(.ts-state.empty)로 렌더했다.
    // 컷 통과 0건은 시장 판단이고 조회 실패는 판단 불가라 같은 문구로 덮으면 안 된다(§4c).
    recommendationAPI.getTop5.mockRejectedValue(new Error('500'))
    paperTradingAPI.getPortfolio.mockRejectedValue(new Error('401'))
    apiClient.get.mockRejectedValue(new Error('500'))
    const w = await mountTab()

    const failed = w.find('.ts-state.failed')
    expect(failed.exists()).toBe(true)                       // 빈 화면 방지(원 의도 유지)
    expect(failed.text()).toContain('불러오지 못했습니다')
    expect(w.find('.ts-state.empty').exists()).toBe(false)   // '관망' 결론으로 위장 금지
    expect(w.text()).not.toContain('관망이 결론입니다')
  })

  it('컷 통과 0건은 조회 실패와 구분해 관망으로 표시한다', async () => {
    recommendationAPI.getTop5.mockResolvedValue({ data: { data: [] } })
    const w = await mountTab()

    expect(w.find('.ts-state.empty').exists()).toBe(true)
    expect(w.find('.ts-state.failed').exists()).toBe(false)
    expect(w.text()).toContain('관망이 결론입니다')
  })
})

// ── 유튜브 참고 의견(분석된 영상 기준 외부 참고) — 후보 순서·점수와 무관, 카드 클릭과 분리 ──
const ytSummary = {
  data: { success: true, data: {
    enabled: true, dataAvailable: true, scope: '분석된 영상 기준', windowDays: 7,
    coverage: { analyzedVideos: 3, analyzingVideos: 0, failedVideos: 1, awaitingAnalysisVideos: 0, noTranscriptVideos: 0 },
    items: {
      '005930': { status: 'HAS_OPINIONS', summary: {
        statementCounts: { POSITIVE: 2, CONDITIONAL: 1 }, personCounts: { POSITIVE: 1, CONDITIONAL: 1, MIXED: 0 },
        totalPersons: 2, totalStatements: 3, unknownSpeakerStatements: 0, latestPublishedAt: '2026-09-27T20:00:00' } },
      '000660': { status: 'NO_OPINION', summary: {
        statementCounts: {}, personCounts: {}, totalPersons: 0, totalStatements: 0, unknownSpeakerStatements: 0, latestPublishedAt: null } }
    }
  } }
}

const ytStockView = {
  data: { success: true, data: {
    enabled: true, dataAvailable: true, status: 'HAS_OPINIONS', scope: '분석된 영상 기준', windowDays: 7,
    coverage: ytSummary.data.data.coverage, summary: ytSummary.data.data.items['005930'].summary,
    persons: [{ personId: 1, name: '테스트운영자', role: 'HOST', stance: 'POSITIVE', sequence: ['POSITIVE'], statements: 2, lastPublishedAt: '2026-09-27T20:00:00' }],
    opinions: [{ id: 11, videoId: 'TESTvid0001', videoTitle: '테스트 영상', channelName: '테스트채널', publishedAt: '2026-09-27T20:00:00',
      analyzedAt: '2026-09-28T09:10:00', speakerName: '테스트운영자', speakerRole: 'HOST', stance: 'CONDITIONAL',
      claimSummary: '조정 시 매수', conditions: '조정이 오면', evidenceQuote: '조정 오면 매수하겠습니다', startSec: 65, startLabel: '01:05',
      sourceUrl: 'https://www.youtube.com/watch?v=TESTvid0001&t=65s' }],
    older: []
  } }
}

describe('TodayBriefingTab — 유튜브 참고 의견', () => {
  it('후보 전체를 한 번에 조회하고, 의견 있는 후보에만 인물 수·발언 수를 따로 적는다', async () => {
    stubAll()
    youtubeOpinionAPI.getSummary.mockReset().mockResolvedValue(ytSummary)
    const w = await mountTab()

    expect(youtubeOpinionAPI.getSummary).toHaveBeenCalledTimes(1)
    expect(youtubeOpinionAPI.getSummary).toHaveBeenCalledWith(['005930', '000660'])
    const strips = w.findAll('.cc-yt')
    expect(strips).toHaveLength(1)
    expect(strips[0].text()).toContain('긍정 1명 · 조건부 1명 · 발언 3건')
    expect(w.find('.cc-yt-note').text()).toContain('분석된 영상 기준')
    expect(w.find('.cc-yt-note').text()).toContain('추천 점수·순위와 무관')
    expect(w.find('.cc-yt-note').text()).toContain('의견 있는 후보 1 · 언급 없음 1')
  })

  it('유튜브 의견이 있어도 후보 순서·점수는 그대로다', async () => {
    stubAll()
    const before = await mountTab()
    const order = (w) => w.findAll('.cc-name').map(n => n.text())
    const scores = (w) => w.findAll('.cc-score').map(n => n.text())
    const baseOrder = order(before)
    const baseScores = scores(before)

    youtubeOpinionAPI.getSummary.mockReset().mockResolvedValue(ytSummary)
    const after = await mountTab()
    expect(order(after)).toEqual(baseOrder)
    expect(scores(after)).toEqual(baseScores)
  })

  it('요약 조회가 실패해도 후보는 그대로 보이고, 실패임을 밝힌다(의견 없음으로 말하지 않는다)', async () => {
    stubAll()
    youtubeOpinionAPI.getSummary.mockReset().mockRejectedValue(new Error('500'))
    const w = await mountTab()
    expect(w.findAll('.candidate-card')).toHaveLength(2)
    expect(w.find('.cc-yt').exists()).toBe(false)
    expect(w.find('.cc-yt-note').text()).toContain('불러오지 못했습니다')
    expect(w.find('.cc-yt-note').classes()).toContain('cc-yt-note-warn')
  })

  it('서버가 조회 실패(dataAvailable=false)로 답해도 같은 경고로 보인다 — 평범한 안내처럼 보이면 안 된다', async () => {
    stubAll()
    // 백엔드 조회 실패 경로(YoutubeOpinionQueryService)는 예외 대신 200 + dataAvailable=false 로 답한다
    youtubeOpinionAPI.getSummary.mockReset().mockResolvedValue({ data: { success: true, data: {
      enabled: true, dataAvailable: false, scope: '분석된 영상 기준', windowDays: 7, items: {} } } })
    const w = await mountTab()
    expect(w.find('.cc-yt').exists()).toBe(false)
    expect(w.find('.cc-yt-note').text()).toContain('불러오지 못했습니다')
    expect(w.find('.cc-yt-note').classes()).toContain('cc-yt-note-warn')
  })

  it('분석을 마친 영상이 없으면 후보마다 반복하지 않고 목록에 한 줄로 말한다', async () => {
    stubAll()
    youtubeOpinionAPI.getSummary.mockReset().mockResolvedValue({ data: { success: true, data: {
      enabled: true, dataAvailable: true, scope: '분석된 영상 기준', windowDays: 7,
      coverage: { analyzedVideos: 0, analyzingVideos: 1, failedVideos: 0, awaitingAnalysisVideos: 0, noTranscriptVideos: 2 },
      items: { '005930': { status: 'NO_ANALYZED_VIDEOS', summary: { totalStatements: 0 } } } } } })
    const w = await mountTab()
    expect(w.find('.cc-yt').exists()).toBe(false)
    expect(w.find('.cc-yt-note').text()).toContain('분석을 마친 영상이 없습니다')
    expect(w.find('.cc-yt-note').text()).toContain('분석 중 1')
  })

  it('기능이 꺼져 있으면 아무것도 그리지 않는다', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.find('.cc-yt').exists()).toBe(false)
    expect(w.find('.cc-yt-note').exists()).toBe(false)
  })

  it('상세 버튼은 종목을 열지 않고 그 자리에서 펼친다 — 조건부·조건·타임스탬프 원문 링크', async () => {
    stubAll()
    youtubeOpinionAPI.getSummary.mockReset().mockResolvedValue(ytSummary)
    youtubeOpinionAPI.getStock.mockReset().mockResolvedValue(ytStockView)
    const w = await mountTab()

    await w.find('.cc-yt-toggle').trigger('click')
    await flushPromises()

    expect(w.emitted('open-stock')).toBeUndefined()
    expect(youtubeOpinionAPI.getStock).toHaveBeenCalledWith('005930')
    const detail = w.find('.cc-yt-detail')
    expect(detail.text()).toContain('조건부')
    expect(detail.text()).toContain('조정이 오면')
    const link = detail.find('a.yo-src')
    expect(link.attributes('href')).toBe('https://www.youtube.com/watch?v=TESTvid0001&t=65s')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })
})

/**
 * 키보드로 연다(2026-10-01 '오늘' 탭 점검). 카드형 버튼(role="button" div)이 Enter 만 받아 Space 를 누르면
 * 화면이 스크롤됐고, 후보 카드(버튼) 안에 근거 기사 링크가 들어 있어 '버튼 안의 링크'였다 — 링크에서
 * Enter 를 누르면 기사와 종목이 함께 열릴 수 있었다.
 */
describe('TodayBriefingTab — 키보드로 연다', () => {
  it('후보 종목은 진짜 버튼으로 연다 — Enter·Space 는 브라우저가 처리하고, 카드 클릭으로 두 번 열리지 않는다', async () => {
    stubAll()
    const w = await mountTab()
    const open = w.find('.candidate-card .cc-open')
    expect(open.exists()).toBe(true)
    expect(open.element.tagName).toBe('BUTTON')
    expect(open.attributes('type')).toBe('button')
    await open.trigger('click')
    expect(w.emitted('open-stock')).toEqual([['005930']])
  })

  it('근거 기사 링크는 버튼(role="button") 안에 있지 않다', async () => {
    stubAll({ catalyst: { data: { success: true, data: { ...catalystResponse.data.data, newsLink: 'https://news.example/1' } } } })
    const w = await mountTab()
    const link = w.find('.cc-cat-link')
    expect(link.exists()).toBe(true)
    expect(link.element.closest('[role="button"]')).toBeNull()
  })

  it('포지션 행은 Space 로도 열린다', async () => {
    stubAll({ portfolio: { data: { success: true, data: [
      { stockCode: '005930', stockName: '삼성전자', quantity: 3, profitRate: 1.2, profitLoss: 100 }
    ] } } })
    const w = await mountTab()
    await w.find('.position-row').trigger('keydown', { key: ' ' })
    expect(w.emitted('open-stock')).toEqual([['005930']])
  })

  it('차트 타이밍 카드도 Space 로 열린다', async () => {
    stubAll()
    recommendationAPI.getTrendPullbackTop10.mockResolvedValue({ data: { success: true, data: [
      { code: '207940', name: '삼성바이오로직스', signals: ['정배열'] }
    ] } })
    const w = await mountTab()
    await w.find('.ts-toggle').trigger('click')
    await w.find('.observe-card').trigger('keydown', { key: ' ' })
    expect(w.emitted('open-stock')).toEqual([['207940']])
  })
})

// 2026-10-19: 후보 카드 등락률은 실시간 시세(캐시)가 없으면 후보가 실어 온 값이었다 — 수급 후보는 직전 거래일, AI 후보는 스냅샷
// 시점 값이 시각 표시 없이 '오늘' 등락률처럼 보였다(가격 칸이 비어 등락률만 보이는 경우가 그것). 이제 서버가 마지막 확정 봉 등락률과
// 그 날짜(changeRateBasisDate)를 보내고, 실시간 시세로 덮으면 날짜를 지운다.
describe('TodayBriefingTab — 후보 등락률 기준일', () => {
  it('재현: 마지막 확정 봉 기준이면 그 날짜를 붙인다 — 실시간처럼 보이지 않게', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: { success: true, dataTime: '10:30 기준', realtime: true, data: [
      { stockCode: '005930', stockName: '삼성전자', totalScore: 82, tags: [], changeRate: 1.2, changeRateBasisDate: '2026-10-16' }
    ] } })
    const w = await mountTab()
    expect(w.find('.cc-basis').exists()).toBe(true)
    expect(w.find('.cc-basis').text()).toBe('10/16 종가')
  })

  it('실시간 시세면(기준일 없음) 날짜를 붙이지 않는다', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.find('.cc-basis').exists()).toBe(false)
  })
})

// 2026-10-19: 장 시작 전·휴장일의 지수 등락률은 직전 거래일 세션 값이다 — 시장 한 줄이 그걸 오늘 등락처럼 보였다.
// 서버가 세션 날짜(indexAsOf)를 싣고, 오늘이 아니면 그 날짜를 붙인다.
describe('TodayBriefingTab — 시장 한 줄의 지수 기준일', () => {
  it('재현: 지수가 지난 거래일 값이면 "10/30 종가"를 붙인다', async () => {
    stubAll()
    const w = await mountTab({ marketData: { kospiIndex: '2,500.00', kospiChangeRate: -2.8, kosdaqIndex: '850.00',
      kosdaqChangeRate: -1.1, indexAsOf: '2026-10-30' } })
    expect(w.find('.today-market .tm-basis').exists()).toBe(true)
    expect(w.find('.today-market .tm-basis').text()).toBe('10/30 종가')
  })

  it('오늘 세션 값이면 날짜를 붙이지 않는다', async () => {
    stubAll()
    const w = await mountTab({ marketData: { kospiIndex: '2,500.00', kospiChangeRate: -2.8, kosdaqIndex: '850.00',
      kosdaqChangeRate: -1.1, indexAsOf: todayKstIso() } })
    expect(w.find('.today-market .tm-basis').exists()).toBe(false)
  })
})

// 2026-10-19: 지수 등락이 전부 간밤 세션 값이 아니면(미국 휴장 다음 날) 서버가 tilt=UNKNOWN 을 준다 — 예전엔 VIX 만으로 '중립'.
// 화면은 그걸 '판단 보류'로 말하고(영문 코드 그대로 보이지 않게), 판정에 쓴 시세의 기준 시각을 붙인다.
describe('TodayBriefingTab — 🌙 간밤 미국장', () => {
  function stubOvernight(overnight) {
    stubAll()
    const base = apiClient.get.getMockImplementation()
    apiClient.get.mockImplementation((url) => (url.includes('/global-futures/overnight-us')
      ? Promise.resolve({ data: { success: true, data: overnight } })
      : base(url)))
  }

  it('재현: tilt UNKNOWN 은 "판단 보류" — 영문 코드를 그대로 보이지 않는다', async () => {
    stubOvernight({ tilt: 'UNKNOWN', dataAvailable: true, asOf: '10/10 05:15 (토) KST',
      drivers: ['미국 지수 간밤 세션 값 없음', 'VIX 18.0(보통)'] })
    const w = await mountTab()
    expect(w.find('.today-overnight .ov-tilt').text()).toBe('판단 보류')
    expect(w.find('.today-overnight').text()).toContain('미국 지수 간밤 세션 값 없음')
  })

  it('재현: 판정에 쓴 시세의 기준 시각을 붙인다 — 월요일 아침의 "간밤"은 금요일 세션이다', async () => {
    stubOvernight({ tilt: 'BULL', dataAvailable: true, asOf: '10/17 05:00 (토) KST',
      drivers: ['S&P500 +0.80%', '나스닥 +1.20%', 'SOX +2.00%', 'VIX 14.0(안정)'] })
    const w = await mountTab()
    expect(w.find('.today-overnight .ov-tilt').text()).toBe('강세')
    expect(w.find('.today-overnight .ov-asof').text()).toBe('10/17 05:00 (토) KST 기준')
  })

  it('기준 시각을 모르면 붙이지 않는다', async () => {
    stubOvernight({ tilt: 'NEUTRAL', dataAvailable: true, asOf: null, drivers: ['S&P500 +0.10%'] })
    const w = await mountTab()
    expect(w.find('.today-overnight .ov-tilt').text()).toBe('중립')
    expect(w.find('.today-overnight .ov-asof').exists()).toBe(false)
  })

  // 2026-11-09 재현(운영 17:05): 세 지수가 전부 올랐는데 '약세'였다 — VIX 25.6 ≥ 25 규칙. 이유가 화면에 없어
  // '간밤 미국장이 빠졌다'로 읽혔다. 서버가 주는 판정 근거(reason)를 이름표 옆에 붙인다.
  it('재현(11/9): 지수가 올랐는데 약세면 판정 근거를 이름표 옆에 붙인다', async () => {
    stubOvernight({ tilt: 'BEAR', dataAvailable: true, asOf: '11/07 07:15 (토) KST',
      reason: 'VIX 25 이상(공포 경계) — 지수 등락과 별개',
      drivers: ['S&P500 +0.31%', '나스닥 +1.35%', 'SOX +4.21%', 'VIX 25.6(공포)'] })
    const w = await mountTab()
    expect(w.find('.today-overnight .ov-tilt').text()).toBe('약세')
    expect(w.find('.today-overnight .ov-reason').text()).toBe('판정: VIX 25 이상(공포 경계) — 지수 등락과 별개')
  })

  it('근거가 없으면(보합권 중립) 근거 줄을 만들지 않는다', async () => {
    stubOvernight({ tilt: 'NEUTRAL', dataAvailable: true, reason: null, drivers: ['S&P500 +0.10%'] })
    const w = await mountTab()
    expect(w.find('.today-overnight .ov-reason').exists()).toBe(false)
  })
})

describe('TodayBriefingTab — 거래대금 하한(2026-11-11)', () => {
  const card = (code, score, avg) => ({ stockCode: code, stockName: code, totalScore: score, tags: [], avgTradedValueEok: avg })
  // 백엔드 /recommendation/top5 응답 그대로 — minAvgTradedValueEok · belowLiquidityFloorCount · candidateCount, 후보별 avgTradedValueEok
  const liquidResponse = {
    data: { success: true, dataTime: '11:30 기준', realtime: true,
      minAvgTradedValueEok: 5, belowLiquidityFloorCount: 5, candidateCount: 7,
      data: [card('036710', 80, 19.7), card('010640', 78, 29.3), card('053800', 70, 536), card('030530', 66, 810.04),
        card('043150', 60, 5.25), card('A00006', 58, null), card('A00007', 56, 6)] }
  }

  it('재현: 7건 중 5건만 보일 때 "매수 후보 7건 중 상위 5건"이라고 말한다', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue(liquidResponse)
    const w = await mountTab()
    expect(w.findAll('.candidate-card')).toHaveLength(5)
    expect(w.text()).toContain('매수 후보 7건 중 상위 5건')
  })

  it('하한으로 뺀 종목이 있으면 그 수와 하한(응답 값)을 한 줄로 말한다', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: { ...liquidResponse.data, minAvgTradedValueEok: 7 } })
    const w = await mountTab()
    expect(w.find('.ts-liquidity-note').text()).toContain('20일 평균 거래대금 7억 미만 5종목은 후보에서 뺐습니다')
  })

  it('재현: 컷은 넘었지만 하한으로 전부 빠진 날은 "컷을 넘은 후보가 없다"고 말하지 않는다', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: { success: true, data: [],
      minAvgTradedValueEok: 5, belowLiquidityFloorCount: 3, candidateCount: 0 } })
    const w = await mountTab()
    expect(w.find('.ts-state.empty').text())
      .toBe('오늘은 매수 컷(55점)을 넘은 3종목이 모두 20일 평균 거래대금 5억 미만이라 후보가 없습니다 — 관망이 결론입니다.')
    expect(w.text()).not.toContain('넘은 후보가 없습니다')
    expect(w.text()).not.toContain('매수 후보 0건 중')
  })

  it('하한이 켜져 있는데 평균을 모르는 후보는 "거래대금 확인 불가"', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: { success: true, data: [card('A00006', 58, null)],
      minAvgTradedValueEok: 5, belowLiquidityFloorCount: 0, candidateCount: 1 } })
    const w = await mountTab()
    expect(w.find('.cc-liq').text()).toBe('거래대금 확인 불가')
  })

  it('후보 카드에 20일 평균 거래대금 — 모르면(null) 표시하지 않는다', async () => {
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue(liquidResponse)
    const w = await mountTab()
    const liq = w.findAll('.candidate-card').map(c => c.find('.cc-liq'))
    expect(liq[0].text()).toBe('거래대금 20일 평균 19.7억')
    expect(liq[2].text()).toBe('거래대금 20일 평균 536억')
    expect(liq[4].text()).toBe('거래대금 20일 평균 5.25억')
    stubAll()
    recommendationAPI.getTop5.mockResolvedValue({ data: { success: true, data: [card('A00006', 58, null)] } })
    const w2 = await mountTab()
    expect(w2.find('.cc-liq').exists()).toBe(false)
  })

  it('값이 없으면 숨긴다 — 뺀 수 0·하한 꺼짐·DB 스냅샷(모름)·후보 5건 이하', async () => {
    for (const extra of [
      { minAvgTradedValueEok: 5, belowLiquidityFloorCount: 0, candidateCount: 2 },
      { minAvgTradedValueEok: null, belowLiquidityFloorCount: null, candidateCount: 2 },
      { minAvgTradedValueEok: 5, belowLiquidityFloorCount: null, candidateCount: null },
      {}
    ]) {
      stubAll()
      recommendationAPI.getTop5.mockResolvedValue({ data: { ...top5Response.data, ...extra } })
      const w = await mountTab()
      expect(w.find('.ts-liquidity-note').exists()).toBe(false)
      expect(w.text()).not.toContain('건 중 상위')
    }
  })
})
