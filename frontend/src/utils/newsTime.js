/**
 * 뉴스 시각(2026-10-06). 화면은 기사 발행 시각(publishedAt, RSS pubDate)으로 말한다 — 예전엔 저장 시각(summarizedAt)으로
 * '방금 전'을 그려, 밤사이 기사가 아침 수집 직후 '방금 전'이었다(10/6 운영: 저장이 발행보다 평균 141분·최대 564분 늦다).
 * 발행 시각이 없을 때만 저장 시각을 쓰되 '수집'이라고 밝힌다.
 */

function toDate(v) {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d
}

function relative(date, now) {
  const minutes = Math.floor((now - date) / 60000)
  if (minutes < 1) return '방금 전'
  if (minutes < 60) return `${minutes}분 전`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 전`
  return date.toLocaleDateString('ko-KR')
}

export function newsTimeText(publishedAt, savedAt, now = new Date()) {
  const published = toDate(publishedAt)
  if (published) return relative(published, now)
  const saved = toDate(savedAt)
  return saved ? '수집 ' + relative(saved, now) : ''
}

/** 발행 시각(없으면 저장 시각) 최신순 사본 — 서버는 저장 시각 순으로 준다. */
export function sortByNewsTime(list) {
  const t = (n) => (toDate(n && n.publishedAt) || toDate(n && n.summarizedAt) || new Date(0)).getTime()
  return [...(Array.isArray(list) ? list : [])].sort((a, b) => t(b) - t(a))
}
