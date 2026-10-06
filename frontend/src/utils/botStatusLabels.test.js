import { describe, it, expect } from 'vitest'
import { botStatusText, profitFactorText, weeklyHistoryWithGaps } from './botStatusLabels'

describe('botStatusText — 못 받은 봇 상태는 중지됨이 아니다(2026-10-03)', () => {
  it('재현: 조회 실패면 상태 확인 실패 — 예전엔 빈 객체가 두 카드 모두 중지됨', () => {
    expect(botStatusText({}, 'REAL', { known: false, failed: true })).toBe('상태 확인 실패')
    expect(botStatusText({}, 'REAL', { known: false, failed: false })).toBe('확인 중')
  })

  it('받은 상태는 종전 규칙대로', () => {
    expect(botStatusText({ tradingMode: 'REAL', status: 'RUNNING', active: true }, 'REAL')).toBe('실행 중')
    expect(botStatusText({ tradingMode: 'REAL', status: 'RUNNING', active: true }, 'VIRTUAL')).toBe('중지됨')
    expect(botStatusText({ tradingMode: 'VIRTUAL', status: 'KILL_SWITCH', active: true }, 'VIRTUAL')).toBe('🛑 킬스위치 발동')
  })
})

describe('profitFactorText — 정의되지 않은 수익 팩터를 0.00 으로 보이지 않는다', () => {
  it('재현: null(거래 없음·전부 본전)은 -, 999.99(손실 없음)는 문구로', () => {
    expect(profitFactorText(null)).toBe('-')
    expect(profitFactorText(undefined)).toBe('-')
    expect(profitFactorText(999.99)).toBe('손실 없음')
  })

  it('실제 값은 소수 둘째 자리', () => {
    expect(profitFactorText(0)).toBe('0.00')
    expect(profitFactorText(1.234)).toBe('1.23')
  })
})

describe('weeklyHistoryWithGaps — 생성되지 않은 주를 조용히 건너뛰지 않는다(2026-10-06)', () => {
  const row = (id, weekStart, weekEnd) => ({ id, weekStart, weekEnd })

  it('재현: 8/17~8/23 행이 없는데 표가 8/24 다음에 8/10 을 붙여 "최근 12주"라 했다', () => {
    const out = weeklyHistoryWithGaps([
      row(3, '2026-08-24', '2026-08-30'),
      row(2, '2026-08-10', '2026-08-16'),
      row(1, '2026-08-03', '2026-08-09')
    ])
    expect(out.map(r => r.weekStart)).toEqual(['2026-08-24', '2026-08-17', '2026-08-10', '2026-08-03'])
    expect(out[1]).toMatchObject({ missing: true, weekStart: '2026-08-17', weekEnd: '2026-08-23' })
    expect(out.filter(r => r.missing)).toHaveLength(1)
  })

  it('빈 주가 여럿이면 전부 — 월말·연말을 넘어도 7일 단위', () => {
    const out = weeklyHistoryWithGaps([row(2, '2027-01-04', '2027-01-10'), row(1, '2026-12-14', '2026-12-20')])
    expect(out.filter(r => r.missing).map(r => r.weekStart)).toEqual(['2026-12-28', '2026-12-21'])
    expect(out.find(r => r.weekStart === '2026-12-28').weekEnd).toBe('2027-01-03')
  })

  it('이어진 주·빈 목록·날짜가 이상한 행은 그대로', () => {
    expect(weeklyHistoryWithGaps([])).toEqual([])
    expect(weeklyHistoryWithGaps(null)).toEqual([])
    const rows = [row(2, '2026-09-28', '2026-10-04'), row(1, '2026-09-21', '2026-09-27')]
    expect(weeklyHistoryWithGaps(rows)).toEqual(rows)
    const odd = [row(2, '2026-09-28', '2026-10-04'), row(1, null, null)]
    expect(weeklyHistoryWithGaps(odd)).toEqual(odd)
  })
})
