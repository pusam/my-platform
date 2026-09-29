import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TodayBriefingTab from './TodayBriefingTab.vue'
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

  it('포지션 있으면 요약 표시 + 평가손익 합산', async () => {
    stubAll({ portfolio: { data: { success: true, data: [
      { stockCode: '005930', stockName: '삼성전자', quantity: 10, profitLoss: 50000, profitRate: 7.1 },
      { stockCode: '000660', stockName: 'SK하이닉스', quantity: 2, profitLoss: -20000, profitRate: -4.5 }
    ] } } })
    const w = await mountTab()
    expect(w.text()).toContain('내 포지션 2종목')
    expect(w.text()).toContain('+30,000원')
  })

  it('포지션 비어있으면 섹션 숨김', async () => {
    stubAll()
    const w = await mountTab()
    expect(w.text()).not.toContain('내 포지션')
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
