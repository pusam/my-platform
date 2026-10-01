import { describe, it, expect } from 'vitest'
import {
  stanceLabel, stanceClass, formatKst, safeYoutubeUrl, summaryText, coverageText, viewState,
  sectionTitle, sequenceText, reviewReasonText, formatPrice, runStatusLabel, analyzerLabel
} from './youtubeOpinion'

const summary = (personCounts, totalStatements, unknown = 0) => ({
  personCounts, totalStatements, unknownSpeakerStatements: unknown, totalPersons: 0
})

describe('분석 실행 상태 — Claude 작업자 대기열·대기는 실패가 아니다(2026-10-01)', () => {
  it('대기열·실행 중·대기(사유별)·성공·실패를 구분한다', () => {
    expect(runStatusLabel({ status: 'QUEUED' })).toBe('대기열(작업자 대기)')
    expect(runStatusLabel({ status: 'RUNNING' })).toBe('실행 중')
    expect(runStatusLabel({ status: 'WAITING', waitReason: 'QUOTA' })).toBe('대기(사용량 한도)')
    expect(runStatusLabel({ status: 'WAITING', waitReason: 'LOGIN' })).toBe('대기(Claude 로그인 필요)')
    expect(runStatusLabel({ status: 'SUCCEEDED' })).toBe('성공')
    expect(runStatusLabel({ status: 'FAILED' })).toBe('실패')
    expect(runStatusLabel(null)).toBe('-')
  })

  it('분석기 — Claude 는 로컬 작업자가 돌아야 진행된다고 말한다, 기본은 Gemini', () => {
    expect(analyzerLabel('CLAUDE')).toContain('로컬 작업자')
    expect(analyzerLabel('GEMINI')).toBe('Gemini(서버)')
    expect(analyzerLabel(undefined)).toBe('Gemini(서버)')
  })
})

describe('유튜브 참고 의견 표시 규칙', () => {
  it('입장 라벨 — 조건부는 조건부, 모르는 값은 판단 불가', () => {
    expect(stanceLabel('CONDITIONAL')).toBe('조건부')
    expect(stanceLabel('MIXED')).toBe('혼재·변경')
    expect(stanceLabel('???')).toBe('판단 불가')
    expect(stanceClass('POSITIVE')).toBe('yo-stance-positive')
    expect(stanceClass(undefined)).toBe('yo-stance-undeterminable')
  })

  it('시각은 KST 문자열 그대로 — 브라우저 시간대로 밀리지 않는다', () => {
    expect(formatKst('2026-09-27T20:05:00')).toBe('9/27 20:05')
    expect(formatKst('2026-01-02T08:00')).toBe('1/2 08:00')
    expect(formatKst('2026-09-27T20:05:00', { withYear: true })).toBe('2026/9/27 20:05')
    expect(formatKst(null)).toBeNull()
    expect(formatKst('어제')).toBeNull()
  })

  it('원문 링크는 서버가 만든 YouTube 시청 주소만 — 그 밖의 값은 링크를 만들지 않는다', () => {
    expect(safeYoutubeUrl('https://www.youtube.com/watch?v=TESTvid0001&t=65s')).toBe('https://www.youtube.com/watch?v=TESTvid0001&t=65s')
    expect(safeYoutubeUrl('https://www.youtube.com/watch?v=TESTvid0001')).not.toBeNull()
    expect(safeYoutubeUrl('javascript:alert(1)')).toBeNull()
    expect(safeYoutubeUrl('https://evil.example/watch?v=TESTvid0001&t=65s')).toBeNull()
    expect(safeYoutubeUrl('https://www.youtube.com/watch?v=TESTvid0001&t=65s"onmouseover=x')).toBeNull()
    expect(safeYoutubeUrl(undefined)).toBeNull()
  })

  it('인물 수와 발언 수를 따로 말한다 — 같은 사람 3번 발언은 1명·3건', () => {
    expect(summaryText(summary({ POSITIVE: 1 }, 3))).toBe('긍정 1명 · 발언 3건')
    expect(summaryText(summary({ POSITIVE: 2, CONDITIONAL: 1, NEGATIVE: 0 }, 4, 1)))
      .toBe('긍정 2명 · 조건부 1명 · 발언 4건(발언자 미상 1건)')
    expect(summaryText(summary({}, 2, 2))).toBe('발언자 확인 안 됨 · 발언 2건(발언자 미상 2건)')
    expect(summaryText(summary({ POSITIVE: 0 }, 0))).toBeNull()
  })

  it('영상 상태 — 분석 영상 수는 항상, 나머지는 있을 때만', () => {
    expect(coverageText({ analyzedVideos: 0, analyzingVideos: 0, failedVideos: 0, awaitingAnalysisVideos: 0, noTranscriptVideos: 0 }))
      .toBe('분석 영상 0개')
    expect(coverageText({ analyzedVideos: 3, analyzingVideos: 1, failedVideos: 2, awaitingAnalysisVideos: 1, noTranscriptVideos: 4 }))
      .toBe('분석 영상 3개 · 분석 중 1 · 분석 실패 2 · 분석 대기 1 · 자막 미등록 4')
    expect(coverageText(null)).toBeNull()
  })

  it('조회 실패 · 분석된 영상 없음 · 언급 없음 · 의견 있음 · 꺼짐을 서로 다르게 말한다', () => {
    const base = { enabled: true, dataAvailable: true, windowDays: 7 }
    expect(viewState(null).kind).toBe('unavailable')
    expect(viewState({ ...base, dataAvailable: false }).kind).toBe('unavailable')
    expect(viewState({ ...base, enabled: false }).kind).toBe('disabled')
    const notAnalyzed = viewState({ ...base, status: 'NO_ANALYZED_VIDEOS' })
    expect(notAnalyzed.kind).toBe('not-analyzed')
    expect(notAnalyzed.text).toContain('아직 분석하지 않은 것')
    const none = viewState({ ...base, status: 'NO_OPINION' })
    expect(none.kind).toBe('none')
    expect(none.text).toContain('의견이 나오지 않았습니다')
    const has = viewState({ ...base, status: 'HAS_OPINIONS', summary: summary({ NEGATIVE: 1 }, 1) })
    expect(has).toEqual({ kind: 'has', text: '부정 1명 · 발언 1건' })
  })

  it('섹션 제목에 요지를 싣는다', () => {
    const base = { enabled: true, dataAvailable: true, windowDays: 7 }
    expect(sectionTitle({ ...base, status: 'HAS_OPINIONS', summary: summary({ POSITIVE: 2, MIXED: 1 }, 5) }))
      .toBe('📺 유튜브 참고 의견 — 최근 7일 · 긍정 2명 · 혼재·변경 1명')
    expect(sectionTitle({ ...base, status: 'NO_OPINION' })).toBe('📺 유튜브 참고 의견 — 최근 7일 언급 없음')
    expect(sectionTitle({ ...base, status: 'NO_ANALYZED_VIDEOS' })).toBe('📺 유튜브 참고 의견 — 분석된 영상 없음')
    expect(sectionTitle(null)).toBe('📺 유튜브 참고 의견 — 조회 실패')
  })

  it('혼재·변경은 순서를 남긴다, 검토 사유는 사람이 읽는 말로, 가격은 원 단위', () => {
    expect(sequenceText(['POSITIVE', 'NEGATIVE'])).toBe('긍정 → 부정')
    expect(sequenceText(['POSITIVE'])).toBeNull()
    expect(reviewReasonText('STOCK_AMBIGUOUS,CONDITION_MARKER')).toBe('동명 종목 · 조건 표현 → 조건부로 낮춤')
    expect(reviewReasonText(null)).toBeNull()
    // 재분석 승계 표시 — 백엔드 ReviewCarryOver 가 사유 맨 앞에 붙인다(코드 그대로 노출하지 않는다)
    expect(reviewReasonText('PREVIOUSLY_REJECTED,STOCK_AMBIGUOUS'))
      .toBe('이전에 거절한 발언 — 다시 확인 · 동명 종목')
    expect(reviewReasonText('REVIEW_NOT_CARRIED,SPEAKER_CONFLICT'))
      .toBe('이전 결정 뒤 내용·검토 사유가 바뀜 — 다시 확인 · 발언자 불일치')
    expect(formatPrice(85000)).toBe('85,000원')
    expect(formatPrice(null)).toBeNull()
    expect(formatPrice('abc')).toBeNull()
  })
})
