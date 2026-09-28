import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import YoutubeOpinionSection from './YoutubeOpinionSection.vue'
import { youtubeOpinionAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() },
  youtubeOpinionAPI: { getStock: vi.fn() }
}))

const coverage = { analyzedVideos: 2, analyzingVideos: 1, failedVideos: 1, awaitingAnalysisVideos: 0, noTranscriptVideos: 0 }

const view = (over = {}) => ({
  enabled: true, dataAvailable: true, status: 'HAS_OPINIONS', scope: '분석된 영상 기준', windowDays: 7,
  windowFrom: '2026-09-21T20:00:00', windowTo: '2026-09-28T20:00:00', coverage,
  summary: { statementCounts: {}, personCounts: { POSITIVE: 1, MIXED: 1 }, totalPersons: 2, totalStatements: 3,
    unknownSpeakerStatements: 0, latestPublishedAt: '2026-09-27T20:00:00' },
  persons: [
    { personId: 1, name: '테스트운영자', role: 'HOST', stance: 'POSITIVE', sequence: ['POSITIVE'], statements: 1, lastPublishedAt: '2026-09-27T20:00:00' },
    { personId: 2, name: '테스트출연자', role: 'GUEST', stance: 'MIXED', sequence: ['POSITIVE', 'NEGATIVE'], statements: 2, lastPublishedAt: '2026-09-26T19:00:00' }
  ],
  opinions: [
    { id: 1, videoId: 'TESTvid0001', videoTitle: '테스트 영상', channelName: '테스트채널', publishedAt: '2026-09-27T20:00:00',
      analyzedAt: '2026-09-28T09:10:00', speakerName: '테스트운영자', speakerRole: 'HOST', stance: 'POSITIVE',
      claimSummary: '<img src=x onerror=alert(1)> 지금 매수해도 좋다', rationale: '실적이 좋다', conditions: null, horizon: null,
      targetPrice: null, evidenceQuote: '지금 사도 좋다고 봅니다', startSec: 65, startLabel: '01:05',
      sourceUrl: 'https://www.youtube.com/watch?v=TESTvid0001&t=65s', model: 'gemini-2.5-flash-lite', promptVersion: 'yt-opinion-v1' },
    { id: 2, videoId: 'TESTvid0002', videoTitle: '두 번째', channelName: '테스트채널', publishedAt: '2026-09-26T19:00:00',
      analyzedAt: '2026-09-27T10:00:00', speakerName: null, speakerRole: null, stance: 'CONDITIONAL',
      claimSummary: '조정 시 매수', conditions: '조정이 오면', horizon: '3개월', targetPrice: 85000,
      evidenceQuote: '조정 오면 매수', startSec: 3725, startLabel: '1:02:05',
      sourceUrl: 'javascript:alert(1)', model: 'm', promptVersion: 'v' }
  ],
  older: [
    { id: 3, videoId: 'TESTvid0003', videoTitle: '지난 영상', channelName: '테스트채널', publishedAt: '2026-08-01T20:00:00',
      analyzedAt: '2026-08-02T10:00:00', speakerName: '테스트운영자', speakerRole: 'HOST', stance: 'NEGATIVE',
      claimSummary: '지난 의견', evidenceQuote: 'x', startSec: 5, startLabel: '00:05',
      sourceUrl: 'https://www.youtube.com/watch?v=TESTvid0003&t=5s' }
  ],
  ...over
})

async function mountWith(response) {
  if (response instanceof Error) youtubeOpinionAPI.getStock.mockRejectedValue(response)
  else youtubeOpinionAPI.getStock.mockResolvedValue({ data: { success: true, data: response } })
  const w = mount(YoutubeOpinionSection, { props: { stockCode: '005930' } })
  await flushPromises()
  return w
}

describe('YoutubeOpinionSection — 종목 상세 유튜브 참고 의견', () => {
  beforeEach(() => vi.clearAllMocks())

  it('범위·무관 표기와 제목 요약 — 인물 수와 발언 수를 따로', async () => {
    const w = await mountWith(view())
    expect(youtubeOpinionAPI.getStock).toHaveBeenCalledWith('005930')
    expect(w.find('.ds-title').text()).toBe('📺 유튜브 참고 의견 — 최근 7일 · 긍정 1명 · 혼재·변경 1명')
    expect(w.text()).toContain('분석된 영상 기준')
    expect(w.text()).toContain('추천 점수·순위와 무관한 외부 참고')
    expect(w.find('.yos-summary').text()).toBe('긍정 1명 · 혼재·변경 1명 · 발언 3건')
    expect(w.find('.yos-coverage').text()).toBe('분석 영상 2개 · 분석 중 1 · 분석 실패 1')
  })

  it('혼재는 순서를, 조건부는 조건·기간·가격을, 발언자 미상은 미상으로 보여 준다', async () => {
    const w = await mountWith(view())
    expect(w.find('.yo-seq').text()).toBe('긍정 → 부정')
    const second = w.findAll('.yo-op')[1]
    expect(second.find('.yo-speaker').text()).toBe('발언자 미상')
    expect(second.text()).toContain('조건부')
    expect(second.text()).toContain('조정이 오면')
    expect(second.text()).toContain('3개월')
    expect(second.text()).toContain('85,000원')
  })

  it('원문은 타임스탬프 링크로, 새 탭 + noopener — 서버 주소가 아니면 링크를 만들지 않는다', async () => {
    const w = await mountWith(view())
    const ops = w.findAll('.yo-op')
    const link = ops[0].find('a.yo-src')
    expect(link.attributes('href')).toBe('https://www.youtube.com/watch?v=TESTvid0001&t=65s')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(link.text()).toBe('▶ 원문 01:05')
    expect(ops[1].find('a.yo-src').exists()).toBe(false)
  })

  it('자막·모델 출력은 글자 그대로 — HTML 로 해석하지 않는다', async () => {
    const w = await mountWith(view())
    expect(w.find('.yo-op img').exists()).toBe(false)
    expect(w.findAll('.yo-claim')[0].text()).toContain('<img src=x onerror=alert(1)>')
  })

  it('게시 시각과 분석 시각을 함께, 지난 의견은 창 밖으로 따로', async () => {
    const w = await mountWith(view())
    expect(w.findAll('.yo-times')[0].text()).toBe('게시 9/27 20:00 · 분석 9/28 09:10')
    expect(w.find('.yo-older summary').text()).toContain('지난 의견 1건')
    expect(w.find('.yo-older summary').text()).toContain('현재 집계에서 제외')
  })

  it('분석된 영상 없음 — 의견 없음과 다른 문구', async () => {
    const w = await mountWith(view({ status: 'NO_ANALYZED_VIDEOS', persons: [], opinions: [], older: [],
      coverage: { analyzedVideos: 0, analyzingVideos: 0, failedVideos: 1, awaitingAnalysisVideos: 0, noTranscriptVideos: 2 } }))
    expect(w.find('.ds-title').text()).toBe('📺 유튜브 참고 의견 — 분석된 영상 없음')
    expect(w.find('.yos-empty').text()).toContain('아직 분석하지 않은 것')
    expect(w.find('.yos-coverage').text()).toBe('분석 영상 0개 · 분석 실패 1 · 자막 미등록 2')
    expect(w.find('.yo-list').exists()).toBe(false)
  })

  it('분석했지만 언급 없음', async () => {
    const w = await mountWith(view({ status: 'NO_OPINION', persons: [], opinions: [], older: [] }))
    expect(w.find('.yos-empty').text()).toContain('의견이 나오지 않았습니다')
  })

  it('조회 실패(dataAvailable=false 또는 HTTP 오류) — 확인 불가로 표시, 상세 화면은 계속', async () => {
    let w = await mountWith(view({ dataAvailable: false, status: null }))
    expect(w.find('.yos-unavailable').text()).toContain('확인하지 못한 것')
    expect(w.find('.yos-coverage').exists()).toBe(false)
    w = await mountWith(new Error('500'))
    expect(w.find('.ds-title').text()).toBe('📺 유튜브 참고 의견 — 조회 실패')
    expect(w.find('.yos-unavailable').exists()).toBe(true)
  })

  it('기능이 꺼져 있으면 섹션을 그리지 않는다', async () => {
    const w = await mountWith({ enabled: false, dataAvailable: true, windowDays: 7, persons: [], opinions: [], older: [] })
    expect(w.find('.detail-section').exists()).toBe(false)
  })

  it('종목을 바꾸면 늦게 온 이전 종목 응답을 버린다', async () => {
    let resolveFirst
    youtubeOpinionAPI.getStock
      .mockImplementationOnce(() => new Promise(r => { resolveFirst = r }))
      .mockResolvedValueOnce({ data: { success: true, data: view({ status: 'NO_OPINION', persons: [], opinions: [], older: [] }) } })
    const w = mount(YoutubeOpinionSection, { props: { stockCode: '005930' } })
    await w.setProps({ stockCode: '000660' })
    await flushPromises()
    resolveFirst({ data: { success: true, data: view() } })
    await flushPromises()
    expect(w.find('.yos-empty').text()).toContain('의견이 나오지 않았습니다')
    expect(w.find('.yo-list').exists()).toBe(false)
  })
})
