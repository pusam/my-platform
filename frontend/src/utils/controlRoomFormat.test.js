import { describe, it, expect } from 'vitest'
import {
  parseMonth,
  buildCalendarGrid,
  cellClass,
  cellLabel,
  dueLabel,
  instructionForDate,
  formatKrw,
  breakerHeadroom,
  ddayLabel,
  ageLabel,
  severityClass,
  gateStateClass,
  WEEKLY_LABELS,
  WEEKLY_SHORT,
  DECISIONS,
  buildDecisionRow
} from './controlRoomFormat'

/**
 * 관제실 표시 로직.
 *
 * 여기서 지켜야 할 §4c 두 가지:
 *  ① null 손익을 0원으로 바꾸지 않는다("조회 실패"와 "0원"은 다르다).
 *  ② 주간 피드백 MISSED(안 돌았다)와 UNKNOWN(모른다)을 같은 말로 쓰지 않는다.
 */
describe('controlRoomFormat — 월 파싱', () => {
  it('YYYY-MM 을 읽는다', () => {
    expect(parseMonth('2026-09')).toEqual({ year: 2026, month: 9 })
  })

  it('형식이 아니거나 월 범위를 벗어나면 null', () => {
    expect(parseMonth('2026-13')).toBeNull()
    expect(parseMonth('2026/09')).toBeNull()
    expect(parseMonth(null)).toBeNull()
  })
})

describe('controlRoomFormat — 달력 격자', () => {
  // 2026-09-01 은 화요일 → 앞에 pad 2칸(일, 월)
  const entries = [
    { id: 'a', title: '수급 캡10 사후검증', due: '2026-09-02', status: 'pending', kind: 'decision', overdue: false },
    { id: 'b', title: '무작위 대조군', due: '2026-09-07', status: 'pending', kind: 'decision', trigger: 'n>=30', overdue: false },
    { id: 'c', title: 'NXT 개시', due: '2026-09-14', status: 'pending', kind: 'milestone', overdue: false }
  ]
  const weekly = [
    { date: '2026-09-06', state: 'RAN' },
    { date: '2026-09-13', state: 'MISSED' },
    { date: '2026-09-20', state: 'UNKNOWN' }
  ]

  const grid = buildCalendarGrid('2026-09', entries, weekly, '2026-09-02')

  it('선행 pad + 해당 월 일수만큼 칸을 만든다', () => {
    expect(grid.filter((c) => c.pad)).toHaveLength(2)
    expect(grid.filter((c) => !c.pad)).toHaveLength(30)
  })

  it('판정이 있는 날에 항목을 붙인다', () => {
    const d2 = grid.find((c) => c.date === '2026-09-02')
    expect(d2.entries).toHaveLength(1)
    expect(d2.isToday).toBe(true)
  })

  it('잘못된 월이면 빈 격자', () => {
    expect(buildCalendarGrid('nope', entries, weekly, null)).toEqual([])
  })

  it('마일스톤 칸은 판정 칸과 다른 색(blk)을 쓴다', () => {
    const d14 = grid.find((c) => c.date === '2026-09-14')
    expect(cellClass(d14)).toContain('blk')
  })

  it('조건 트리거 칸은 cond 색으로 판정일과 구분된다', () => {
    const d7 = grid.find((c) => c.date === '2026-09-07')
    expect(cellClass(d7)).toContain('cond')
    expect(cellClass(d7)).not.toContain('blk')
  })

  it('주간 피드백만 있는 날은 녹색이고, 판정과 겹치면 판정이 이긴다', () => {
    const d6 = grid.find((c) => c.date === '2026-09-06')
    expect(cellClass(d6)).toEqual(expect.arrayContaining(['ev', 'grn']))

    const overlap = buildCalendarGrid(
      '2026-09',
      [{ id: 'x', title: '겹침 판정', due: '2026-09-06', status: 'pending', kind: 'decision', overdue: false }],
      weekly,
      null
    ).find((c) => c.date === '2026-09-06')
    expect(cellClass(overlap)).not.toContain('grn')
  })

  it('오늘 칸에 today 클래스가 붙는다', () => {
    expect(cellClass(grid.find((c) => c.date === '2026-09-02'))).toContain('today')
  })

  it('칸 라벨은 판정 제목, 주간 피드백은 짧은 표기(긴 문구는 툴팁용)', () => {
    expect(cellLabel(grid.find((c) => c.date === '2026-09-02'))).toBe('수급 캡10 사후검증')
    // 좁은 칸엔 짧은 표기 — 긴 WEEKLY_LABELS 는 툴팁·지시문에서 쓴다
    expect(cellLabel(grid.find((c) => c.date === '2026-09-13'))).toBe(WEEKLY_SHORT.MISSED)
    expect(WEEKLY_SHORT.MISSED.length).toBeLessThanOrEqual(6)
  })

  it('MISSED 와 UNKNOWN 문구가 서로 다르다 (안 돌았다 ≠ 모른다) — 짧은 표기도 마찬가지', () => {
    expect(WEEKLY_LABELS.MISSED).not.toBe(WEEKLY_LABELS.UNKNOWN)
    expect(WEEKLY_LABELS.UNKNOWN).toContain('불명')
    expect(WEEKLY_SHORT.MISSED).not.toBe(WEEKLY_SHORT.UNKNOWN)
  })
})

describe('controlRoomFormat — 기한 표기', () => {
  it('조건 트리거는 판정일이 아니라 확인일로 적는다', () => {
    const label = dueLabel({ due: '2026-09-07', trigger: '양쪽 각 n>=30' })
    expect(label).toContain('확인')
    expect(label).toContain('n>=30')
  })

  it('마일스톤은 판정이 아니라고 명시한다', () => {
    expect(dueLabel({ due: '2026-09-14', kind: 'milestone' })).toContain('판정 아님')
  })

  it('일반 판정은 날짜만', () => {
    expect(dueLabel({ due: '2026-09-16', kind: 'decision' })).toBe('2026-09-16')
  })
})

describe('controlRoomFormat — 날짜 클릭 지시문', () => {
  it('판정이 있으면 제목을 포함한 요약 지시', () => {
    const cell = {
      pad: false,
      date: '2026-09-16',
      entries: [{ title: '캔들 패턴 shadow 승격' }],
      weekly: null
    }
    expect(instructionForDate(cell)).toContain('캔들 패턴 shadow 승격')
    expect(instructionForDate(cell)).toContain('2026-09-16')
  })

  it('주간 피드백만 있는 날은 그 상태를 묻는다', () => {
    const cell = { pad: false, date: '2026-09-13', entries: [], weekly: 'MISSED' }
    expect(instructionForDate(cell)).toContain(WEEKLY_LABELS.MISSED)
  })

  it('빈 날도 지시문을 만든다', () => {
    const cell = { pad: false, date: '2026-09-03', entries: [], weekly: null }
    expect(instructionForDate(cell)).toContain('2026-09-03')
  })
})

describe('controlRoomFormat — 금액/여유 (§4c)', () => {
  it('null 손익은 0원이 아니라 null 이다', () => {
    expect(formatKrw(null)).toBeNull()
    expect(formatKrw(undefined)).toBeNull()
  })

  it('0원은 0원으로 표시한다 (null 과 구분)', () => {
    expect(formatKrw(0)).toBe('0원')
  })

  it('음수는 부호를 살려 천단위 구분', () => {
    expect(formatKrw(-123456)).toBe('-123,456원')
  })

  it('여유는 손익·한도가 모두 있어야 계산한다', () => {
    expect(breakerHeadroom(-60000, 300000)).toBe(240000)
    expect(breakerHeadroom(null, 300000)).toBeNull()
    expect(breakerHeadroom(-60000, null)).toBeNull()
  })
})

describe('controlRoomFormat — 배지 문구', () => {
  it('D-day', () => {
    expect(ddayLabel(9)).toBe('D-9')
    expect(ddayLabel(0)).toBe('D-DAY')
    expect(ddayLabel(-3)).toBe('D+3')
    expect(ddayLabel(null)).toBeNull()
  })

  it('경과일', () => {
    expect(ageLabel(0)).toBe('오늘')
    expect(ageLabel(3)).toBe('3일 경과')
    expect(ageLabel(null)).toBeNull()
  })

  it('심각도/게이트 클래스', () => {
    expect(severityClass('critical')).toBe('crit')
    expect(severityClass('warning')).toBe('warn')
    expect(severityClass('뭔가이상')).toBe('info')

    expect(gateStateClass('OPEN')).toBe('g-open')
    expect(gateStateClass('CLOSED')).toBe('g-closed')
    expect(gateStateClass('UNKNOWN')).toBe('g-unknown')
  })
})

describe('controlRoomFormat — 판정 기록 행 생성', () => {
  const base = {
    decidedOn: '2026-08-25',
    title: 'VKOSPI 게이트 승격 (P2-18)',
    decision: '유지',
    evidence: 'HIGH_VOL 41%(distinctDays=12) vs NORMAL 44%(distinctDays=31)',
    nextAction: '다음 주간 리포트에서 재확인'
  }

  it('문서 표 형식대로 5칸 행을 만든다', () => {
    const { row, valid, warnings } = buildDecisionRow(base)

    expect(valid).toBe(true)
    expect(warnings).toEqual([])
    expect(row).toBe(
      '| 2026-08-25 | VKOSPI 게이트 승격 (P2-18) | 유지 | '
      + 'HIGH_VOL 41%(distinctDays=12) vs NORMAL 44%(distinctDays=31) | 다음 주간 리포트에서 재확인 |'
    )
  })

  it('결정 목록은 문서의 5종과 같다', () => {
    expect(DECISIONS).toEqual([
      '유지', '조정', '승격', '판정보류(표본부족)', '판정불가(데이터없음)'
    ])
  })

  it('근거에 표본 수가 없으면 경고한다 (§4c — n 없는 결론 금지)', () => {
    const { warnings } = buildDecisionRow({ ...base, evidence: '괜찮아 보임' })
    expect(warnings.join(' ')).toContain('표본 수')
  })

  it('n=12 · n≥30 · 12건 · 34% 는 표본 수로 인정한다', () => {
    for (const evidence of ['n=12 로 충분', 'n≥30 도달', '평가 12건', '적중률 34%']) {
      expect(buildDecisionRow({ ...base, evidence }).warnings).toEqual([])
    }
  })

  it('보류·불가인데 재판정일이 비면 경고한다 (비워두면 또 잊힌다)', () => {
    for (const decision of ['판정보류(표본부족)', '판정불가(데이터없음)']) {
      const { warnings } = buildDecisionRow({ ...base, decision, nextAction: '' })
      expect(warnings.join(' ')).toContain('재판정일')
    }
  })

  it('유지·조정·승격은 재판정일이 비어도 경고하지 않는다', () => {
    const { warnings } = buildDecisionRow({ ...base, decision: '유지', nextAction: '' })
    expect(warnings.join(' ')).not.toContain('재판정일')
  })

  it('안건이나 결정이 없으면 valid=false — 행을 만들 수 없다', () => {
    expect(buildDecisionRow({ ...base, title: '' }).valid).toBe(false)
    expect(buildDecisionRow({ ...base, decision: '' }).valid).toBe(false)
    expect(buildDecisionRow().valid).toBe(false)
  })

  it('근거에 파이프가 들어가도 표를 깨지 않는다', () => {
    const { row } = buildDecisionRow({ ...base, evidence: 'A | B 비교 n=30' })
    expect(row.split('|')).toHaveLength(7)   // 앞뒤 빈칸 + 5칸
    expect(row).toContain('A / B 비교')
  })

  it('경고가 있어도 행 자체는 만들어진다 — 막지 않고 알려준다', () => {
    const { row, warnings, valid } = buildDecisionRow({ ...base, evidence: '느낌상 괜찮음' })
    expect(valid).toBe(true)
    expect(warnings.length).toBeGreaterThan(0)
    expect(row).toContain('느낌상 괜찮음')
  })
})
