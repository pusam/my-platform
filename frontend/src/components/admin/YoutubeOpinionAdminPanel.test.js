import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import YoutubeOpinionAdminPanel from './YoutubeOpinionAdminPanel.vue'
import { youtubeOpinionAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() },
  youtubeOpinionAPI: {
    getConfig: vi.fn(), getVideos: vi.fn(), getReviewQueue: vi.fn(), registerVideo: vi.fn(),
    uploadTranscript: vi.fn(), analyze: vi.fn(), getRuns: vi.fn(), review: vi.fn()
  }
}))

const config = (over = {}) => ({
  enabled: true, autoCollection: false, channels: [{ id: 'UC_test_ch1', name: '테스트채널A' }], geminiConfigured: true,
  windowDays: 7, maxChunksPerRun: 20, dailyCallLimit: 60, dailyCallsUsed: 3, quietWindow: '07:20-08:40',
  maxTranscriptKb: 512, model: 'gemini-2.5-flash-lite', promptVersion: 'yt-opinion-v1', ...over
})

const video = {
  videoId: 'TESTvid0001', title: '<b>테스트 영상</b>', channelId: 'UC_test_ch1', channelName: '테스트채널A',
  publishedAt: '2026-09-27T20:00:00', createdAt: '2026-09-28T09:00:00', sourceNote: '테스트', duplicateOf: null,
  status: 'FAILED', currentRunId: 5, lastAttemptAt: '2026-09-28T10:00:00', lastSuccessAt: '2026-09-28T09:30:00',
  lastError: '구간 1/1: Gemini 응답 없음(키·쿼터·일시 오류)', transcriptVersions: 2,
  participants: [{ personId: 1, name: '테스트운영자', role: 'HOST' }], videoUrl: 'https://www.youtube.com/watch?v=TESTvid0001&t=0s'
}

const reviewRow = {
  id: 9, videoId: 'TESTvid0001', videoTitle: '테스트 영상', speakerName: '테스트운영자', stockNameRaw: '동명회사',
  stockCode: null, mappingStatus: 'AMBIGUOUS', stance: 'POSITIVE', statementType: 'CURRENT_VIEW',
  claimSummary: '지금 사도 좋다', conditions: null, evidenceQuote: '동명회사는 지금 사도 좋습니다',
  reviewStatus: 'NEEDS_REVIEW', reviewReasons: 'STOCK_AMBIGUOUS', startSec: 90,
  sourceUrl: 'https://www.youtube.com/watch?v=TESTvid0001&t=90s'
}

function stub({ cfg = config(), videos = [video], review = [reviewRow] } = {}) {
  youtubeOpinionAPI.getConfig.mockResolvedValue({ data: { success: true, data: cfg } })
  youtubeOpinionAPI.getVideos.mockResolvedValue({ data: { success: true, data: videos } })
  youtubeOpinionAPI.getReviewQueue.mockResolvedValue({ data: { success: true, data: review } })
}

async function mountPanel() {
  const w = mount(YoutubeOpinionAdminPanel)
  await flushPromises()
  return w
}

describe('YoutubeOpinionAdminPanel — 관리자 등록·분석·검토', () => {
  beforeEach(() => vi.clearAllMocks())

  it('자동 수집이 연결되지 않았음을 밝히고, 한도·보호 시간대를 보여 준다', async () => {
    stub()
    const w = await mountPanel()
    expect(w.text()).toContain('자동 수집: 연결 안 됨')
    expect(w.text()).toContain('제목만으로 내용을 추측하지 않습니다')
    expect(w.text()).toContain('오늘 분석 호출 3 / 60')
    expect(w.text()).toContain('07:20-08:40')
  })

  it('꺼져 있으면 켜는 방법만 안내하고 영상·검토 API 를 부르지 않는다', async () => {
    stub({ cfg: config({ enabled: false }) })
    const w = await mountPanel()
    expect(w.text()).toContain('YOUTUBE_OPINION_ENABLED=true')
    expect(youtubeOpinionAPI.getVideos).not.toHaveBeenCalled()
    expect(youtubeOpinionAPI.getReviewQueue).not.toHaveBeenCalled()
  })

  it('마지막 실패 원인·자막 버전을 보여 주고, 제목은 태그 없이 글자 그대로', async () => {
    stub()
    const w = await mountPanel()
    expect(w.text()).toContain('마지막 실패: 구간 1/1: Gemini 응답 없음')
    expect(w.text()).toContain('자막 2버전')
    expect(w.find('.yoa-video b').exists()).toBe(false)
    expect(w.find('.yoa-video .yoa-link').text()).toBe('<b>테스트 영상</b>')
  })

  it('등록 — 빈 출연자 줄은 빼고 보낸다, 이미 등록이면 그렇게 말한다', async () => {
    stub()
    youtubeOpinionAPI.registerVideo.mockResolvedValue({ data: { success: true, data: { alreadyRegistered: true } } })
    const w = await mountPanel()
    await w.find('input[placeholder^="https://www.youtube.com"]').setValue('https://youtu.be/TESTvid0001')
    await w.findAll('.yoa-form input')[1].setValue('제목')
    await w.find('.yoa-form select').setValue('UC_test_ch1')
    await w.find('input[type="datetime-local"]').setValue('2026-09-27T20:00')
    await w.findAll('.yoa-form input')[3].setValue('테스트 출처')
    await w.find('.yoa-form').trigger('submit')
    await flushPromises()

    const payload = youtubeOpinionAPI.registerVideo.mock.calls[0][0]
    expect(payload.url).toBe('https://youtu.be/TESTvid0001')
    expect(payload.channelId).toBe('UC_test_ch1')
    expect(payload.publishedAt).toBe('2026-09-27T20:00')
    expect(payload.participants).toEqual([])
    expect(w.text()).toContain('이미 등록된 영상입니다')
  })

  it('서버가 거절하면 이유를 그대로 보여 준다(429·409 등)', async () => {
    stub()
    youtubeOpinionAPI.analyze.mockRejectedValue({ response: { status: 429, data: { message: '분석 요청은 한 시간에 10건까지입니다.' } } })
    const w = await mountPanel()
    await w.findAll('.yoa-video .yoa-btn').find(b => b.text() === '재분석').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('분석 요청은 한 시간에 10건까지입니다.')
  })

  it('검토 — 동명 종목은 코드를 지정해 승인, 사유는 사람이 읽는 말로', async () => {
    stub()
    youtubeOpinionAPI.review.mockResolvedValue({ data: { success: true, data: {} } })
    const w = await mountPanel()
    expect(w.find('.yoa-reasons').text()).toContain('동명 종목')
    await w.find('.yoa-code').setValue('111111')
    await w.findAll('.yoa-review .yoa-btn').find(b => b.text() === '승인').trigger('click')
    await flushPromises()
    expect(youtubeOpinionAPI.review).toHaveBeenCalledWith(9, 'APPROVE', '111111')
  })
})
