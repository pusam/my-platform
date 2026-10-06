import { describe, it, expect } from 'vitest'
import { newsTimeText, sortByNewsTime } from './newsTime'

/**
 * 뉴스 시각 — 기사 발행 시각으로(2026-10-06 화면 점검).
 * 재현: 화면이 저장 시각(summarizedAt)으로 '방금 전'을 그렸다. 10/6 운영 31건 기준 저장이 발행보다 평균 141분·최대 564분
 * 늦어(밤사이 기사가 아침 수집에 들어온다) 9시간 지난 기사가 '방금 전'이었다. 또 1시간 미만은 전부 '방금 전'이었다.
 */
const NOW = new Date('2026-10-06T09:50:00')

describe('newsTimeText', () => {
  it('재현: 발행 시각 기준 — 저장 시각이 방금이어도 9시간 전 기사는 9시간 전', () => {
    expect(newsTimeText('2026-10-06T00:30:00', '2026-10-06T09:49:30', NOW)).toBe('9시간 전')
  })

  it('재현: 1시간 미만을 뭉뚱그려 ‘방금 전’이라 하지 않는다 — 분 단위', () => {
    expect(newsTimeText('2026-10-06T09:05:00', null, NOW)).toBe('45분 전')
    expect(newsTimeText('2026-10-06T09:49:40', null, NOW)).toBe('방금 전')
  })

  it('발행 시각이 없으면 저장 시각을 ‘수집’으로 밝힌다 · 하루 넘으면 날짜', () => {
    expect(newsTimeText(null, '2026-10-06T09:20:00', NOW)).toBe('수집 30분 전')
    expect(newsTimeText('2026-10-04T18:00:00', null, NOW)).toBe(new Date('2026-10-04T18:00:00').toLocaleDateString('ko-KR'))
  })

  it('둘 다 없거나 읽을 수 없으면 빈 문자열', () => {
    expect(newsTimeText(null, null, NOW)).toBe('')
    expect(newsTimeText('nope', undefined, NOW)).toBe('')
  })
})

describe('sortByNewsTime', () => {
  it('발행 시각 최신순 — 저장 시각 순으로 오면 ‘9시간 전’이 ‘5분 전’ 위에 선다', () => {
    const list = [
      { id: 1, publishedAt: '2026-10-06T00:30:00', summarizedAt: '2026-10-06T09:49:00' },
      { id: 2, publishedAt: '2026-10-06T09:45:00', summarizedAt: '2026-10-06T09:46:00' },
      { id: 3, publishedAt: null, summarizedAt: '2026-10-06T09:30:00' }
    ]
    expect(sortByNewsTime(list).map(n => n.id)).toEqual([2, 3, 1])
    expect(list.map(n => n.id)).toEqual([1, 2, 3]) // 원본은 그대로
  })
})
