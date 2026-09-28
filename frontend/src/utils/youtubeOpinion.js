/**
 * 유튜브 참고 의견 표시 규칙 — 순수 함수(화면 두 곳 + 관리자 패널이 공유).
 *
 * 원칙: 추천 점수·순위와 무관한 외부 참고다. 인물 수와 발언 수를 섞지 않고, "조회 실패 / 분석된 영상 없음 /
 * 분석했지만 언급 없음 / 의견 있음"을 서로 다른 문구로 말한다(§4c). 모든 문구는 평문으로 렌더링한다(v-html 금지).
 */

export const STANCE_ORDER = ['POSITIVE', 'CONDITIONAL', 'NEUTRAL', 'NEGATIVE', 'MIXED', 'UNDETERMINABLE']

export const STANCE_LABELS = {
  POSITIVE: '긍정',
  CONDITIONAL: '조건부',
  NEUTRAL: '중립',
  NEGATIVE: '부정',
  MIXED: '혼재·변경',
  UNDETERMINABLE: '판단 불가'
}

const ROLE_LABELS = { HOST: '채널 운영자', GUEST: '출연자' }

const REVIEW_REASON_LABELS = {
  EVIDENCE_NOT_IN_TRANSCRIPT: '근거 발췌가 자막에 없음',
  STOCK_NOT_IN_WINDOW: '종목 표기가 발언 근처 자막에 없음',
  STOCK_AMBIGUOUS: '동명 종목',
  STOCK_UNMATCHED: '종목 마스터에 없음',
  STOCK_CODE_MISMATCH: '종목코드·이름 불일치',
  SPEAKER_CONFLICT: '발언자 불일치',
  CONDITION_MARKER: '조건 표현 → 조건부로 낮춤',
  STANCE_TO_CONDITIONAL: '조건 있음 → 조건부',
  TARGET_NOT_IN_TRANSCRIPT: '목표가가 원문에 없어 삭제',
  HORIZON_NOT_IN_TRANSCRIPT: '기간이 원문에 없어 삭제',
  SOLE_PARTICIPANT: '단독 출연자로 발언자 지정',
  SPEAKER_UNKNOWN: '발언자 미상',
  REVIEW_CARRIED: '이전 검토 결정 유지'
}

const VIDEO_STATUS_LABELS = {
  REGISTERED: '자막 미등록',
  TRANSCRIPT_READY: '분석 대기',
  ANALYZING: '분석 중',
  ANALYZED: '분석 완료',
  FAILED: '분석 실패'
}

export function stanceLabel(stance) {
  return STANCE_LABELS[stance] || '판단 불가'
}

export function stanceClass(stance) {
  return 'yo-stance-' + String(STANCE_LABELS[stance] ? stance : 'UNDETERMINABLE').toLowerCase()
}

export function roleLabel(role) {
  return ROLE_LABELS[role] || null
}

export function reviewReasonText(reasons) {
  if (!reasons) return null
  return String(reasons).split(',').map(r => REVIEW_REASON_LABELS[r.trim()] || r.trim()).join(' · ')
}

export function videoStatusLabel(status) {
  return VIDEO_STATUS_LABELS[status] || status || '-'
}

/**
 * 백엔드 LocalDateTime(KST, 오프셋 없음) → '9/27 20:00'. Date 로 파싱하지 않는다 —
 * 브라우저 시간대가 KST 가 아니면 시각이 밀린다.
 */
export function formatKst(value, { withYear = false } = {}) {
  if (!value) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(String(value))
  if (!m) return null
  const [, y, mo, d, h, mi] = m
  return `${withYear ? y + '/' : ''}${Number(mo)}/${Number(d)} ${h}:${mi}`
}

/** 원문 링크는 서버가 만든 YouTube 시청 주소만 쓴다 — 그 밖의 값이면 링크를 만들지 않는다. */
export function safeYoutubeUrl(url) {
  if (typeof url !== 'string') return null
  return /^https:\/\/www\.youtube\.com\/watch\?v=[A-Za-z0-9_-]{11}(&t=\d+s)?$/.test(url) ? url : null
}

/** 인물 수(입장별) — 0 인 입장은 뺀다. */
export function personCounts(summary) {
  const pc = summary?.personCounts || {}
  return STANCE_ORDER
    .filter(s => Number(pc[s]) > 0)
    .map(s => ({ stance: s, label: stanceLabel(s), count: Number(pc[s]) }))
}

/** '긍정 2명 · 조건부 1명 · 발언 4건(발언자 미상 1건)' — 인물 수와 발언 수를 따로 말한다. */
export function summaryText(summary) {
  if (!summary || !Number(summary.totalStatements)) return null
  const persons = personCounts(summary).map(p => `${p.label} ${p.count}명`)
  let text = persons.length ? persons.join(' · ') : '발언자 확인 안 됨'
  text += ` · 발언 ${summary.totalStatements}건`
  if (Number(summary.unknownSpeakerStatements)) text += `(발언자 미상 ${summary.unknownSpeakerStatements}건)`
  return text
}

/** 집계 창 안 영상 상태 — 분석 영상 수는 항상, 나머지는 있을 때만. */
export function coverageText(coverage) {
  if (!coverage) return null
  const parts = [`분석 영상 ${coverage.analyzedVideos}개`]
  if (coverage.analyzingVideos) parts.push(`분석 중 ${coverage.analyzingVideos}`)
  if (coverage.failedVideos) parts.push(`분석 실패 ${coverage.failedVideos}`)
  if (coverage.awaitingAnalysisVideos) parts.push(`분석 대기 ${coverage.awaitingAnalysisVideos}`)
  if (coverage.noTranscriptVideos) parts.push(`자막 미등록 ${coverage.noTranscriptVideos}`)
  return parts.join(' · ')
}

export const UNAVAILABLE_TEXT = '유튜브 참고 의견을 불러오지 못했습니다 — 의견이 없는 것이 아니라 확인하지 못한 것입니다.'

/**
 * 종목 화면 상태. kind: disabled(기능 꺼짐 — 아무것도 안 그림) · unavailable(조회 실패) ·
 * not-analyzed(창 안에 분석된 영상 없음) · none(분석했지만 이 종목 언급 없음) · has(의견 있음).
 */
export function viewState(view) {
  if (!view) return { kind: 'unavailable', text: UNAVAILABLE_TEXT }
  if (view.enabled === false) return { kind: 'disabled', text: null }
  if (view.dataAvailable === false) return { kind: 'unavailable', text: UNAVAILABLE_TEXT }
  const days = view.windowDays
  if (view.status === 'HAS_OPINIONS') return { kind: 'has', text: summaryText(view.summary) }
  if (view.status === 'NO_OPINION') {
    return { kind: 'none', text: `최근 ${days}일 분석된 영상에서 이 종목에 대한 의견이 나오지 않았습니다.` }
  }
  return {
    kind: 'not-analyzed',
    text: `최근 ${days}일 안에 분석을 마친 영상이 없습니다 — 의견이 없는 것이 아니라 아직 분석하지 않은 것입니다.`
  }
}

/** 접이식 섹션 제목 — 펼치지 않아도 요지가 보이게. */
export function sectionTitle(view) {
  const base = '📺 유튜브 참고 의견'
  const st = viewState(view)
  if (st.kind === 'unavailable') return `${base} — 조회 실패`
  if (st.kind === 'not-analyzed') return `${base} — 분석된 영상 없음`
  if (st.kind === 'none') return `${base} — 최근 ${view.windowDays}일 언급 없음`
  if (st.kind === 'has') {
    const persons = personCounts(view.summary).map(p => `${p.label} ${p.count}명`).join(' · ')
    return `${base} — 최근 ${view.windowDays}일 · ${persons || '발언자 확인 안 됨'}`
  }
  return base
}

/** 혼재·변경 순서 — '긍정 → 부정'. */
export function sequenceText(sequence) {
  if (!Array.isArray(sequence) || sequence.length < 2) return null
  return sequence.map(stanceLabel).join(' → ')
}

export function formatPrice(value) {
  if (value == null || value === '') return null
  const n = Number(value)
  if (!Number.isFinite(n)) return null
  return `${n.toLocaleString('ko-KR')}원`
}
