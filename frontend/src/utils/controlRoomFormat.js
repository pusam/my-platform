/**
 * 관제실 표시 로직 — 순수 함수만. 화면(Vue)에서 계산을 빼내 테스트 가능하게 둔다.
 *
 * ⚠ 여기서 새 수치를 만들지 않는다. 백엔드 스냅샷이 이미 계산해 내려준 값을 "어떻게 보여줄지"만
 * 정한다(계약 대비 %·게이트 통과 수 등은 전부 서버가 확정). §4c 를 화면에서 지키는 것도 여기 —
 * dataAvailable=false 는 0 이 아니라 "데이터 없음"으로 렌더된다.
 */

/** 캘린더 요일 헤더 — 일요일 시작(목업과 동일). */
export const DOW_LABELS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']

/** 'YYYY-MM' → { year, month(1~12) }. 형식이 아니면 null. */
export function parseMonth(month) {
  if (typeof month !== 'string') return null
  const m = /^(\d{4})-(\d{2})$/.exec(month.trim())
  if (!m) return null
  const year = Number(m[1])
  const mon = Number(m[2])
  if (mon < 1 || mon > 12) return null
  return { year, month: mon }
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

function isoOf(year, month, day) {
  return `${year}-${pad2(month)}-${pad2(day)}`
}

/**
 * 달력 격자 생성.
 *
 * @param {string} month           'YYYY-MM'
 * @param {Array}  entries         스냅샷 calendar.entries
 * @param {Array}  weeklyFeedback  스냅샷 calendar.weeklyFeedback
 * @param {string} todayIso        스냅샷 today ('YYYY-MM-DD')
 * @returns {Array} 셀 배열. pad 셀은 { pad: true } 하나뿐이다.
 */
export function buildCalendarGrid(month, entries = [], weeklyFeedback = [], todayIso = null) {
  const parsed = parseMonth(month)
  if (!parsed) return []

  const { year, month: mon } = parsed
  const firstDow = new Date(year, mon - 1, 1).getDay()
  const daysInMonth = new Date(year, mon, 0).getDate()

  const entryByDate = new Map()
  for (const e of entries || []) {
    if (!e || !e.due) continue
    const key = e.due.slice(0, 10)
    if (!entryByDate.has(key)) entryByDate.set(key, [])
    entryByDate.get(key).push(e)
  }

  const weeklyByDate = new Map()
  for (const w of weeklyFeedback || []) {
    if (!w || !w.date) continue
    weeklyByDate.set(w.date.slice(0, 10), w.state)
  }

  const cells = []
  for (let i = 0; i < firstDow; i++) cells.push({ pad: true })

  for (let day = 1; day <= daysInMonth; day++) {
    const iso = isoOf(year, mon, day)
    const dayEntries = entryByDate.get(iso) || []
    cells.push({
      pad: false,
      day,
      date: iso,
      entries: dayEntries,
      weekly: weeklyByDate.get(iso) || null,
      isToday: iso === todayIso,
      hasOverdue: dayEntries.some((e) => e.overdue),
      hasMilestone: dayEntries.some((e) => e.kind === 'milestone'),
      hasTrigger: dayEntries.some((e) => e.trigger)
    })
  }
  return cells
}

/**
 * 캘린더 칸에 붙일 CSS 클래스.
 *
 * 우선순위: OVERDUE(적) > 마일스톤(적) > 조건 대기(황) > 일반 판정(보라) > 주간 피드백(녹).
 * 판정과 주간 피드백이 겹치면 판정이 이긴다 — 판정이 사람이 움직여야 하는 쪽이다.
 */
export function cellClass(cell) {
  if (!cell || cell.pad) return []
  const classes = []
  if (cell.entries.length > 0) {
    classes.push('ev')
    if (cell.hasOverdue || cell.hasMilestone) classes.push('blk')
    else if (cell.hasTrigger) classes.push('cond')
  } else if (cell.weekly) {
    classes.push('ev', 'grn')
  }
  if (cell.isToday) classes.push('today')
  return classes
}

/** 칸 안에 쓸 짧은 라벨. 판정이 있으면 판정 제목, 없으면 주간 피드백 상태. */
export function cellLabel(cell) {
  if (!cell || cell.pad) return ''
  if (cell.entries.length > 0) {
    const first = cell.entries[0]
    const suffix = cell.entries.length > 1 ? ` 외 ${cell.entries.length - 1}` : ''
    return `${first.title}${suffix}`
  }
  return cell.weekly ? WEEKLY_LABELS[cell.weekly] || cell.weekly : ''
}

/**
 * 주간 피드백 상태 라벨.
 *
 * §4c: MISSED 와 UNKNOWN 을 같은 말로 표시하지 않는다. 전자는 "안 돌았다"는 결론이고
 * 후자는 "돌았는지 모른다"는 미측정이다.
 */
export const WEEKLY_LABELS = {
  SCHEDULED: '주간 피드백 예정',
  RAN: '주간 피드백 실행됨',
  MISSED: '주간 피드백 미실행',
  UNKNOWN: '주간 피드백 실행 여부 불명'
}

/**
 * 판정 항목의 기한 표기.
 *
 * 조건 트리거가 있으면 날짜는 판정일이 아니라 **확인일**이다 — 조건 미달인데 판정한 것처럼
 * 보이지 않게 문구로 구분한다.
 */
export function dueLabel(entry) {
  if (!entry || !entry.due) return ''
  if (entry.trigger) return `${entry.due} 확인 (조건: ${entry.trigger})`
  if (entry.kind === 'milestone') return `${entry.due} (마일스톤 — 판정 아님)`
  return entry.due
}

/** 날짜 클릭 시 에렌에게 보낼 기본 지시문. */
export function instructionForDate(cell) {
  if (!cell || cell.pad) return ''
  if (cell.entries.length > 0) {
    const titles = cell.entries.map((e) => e.title).join(', ')
    return `${cell.date} 판정(${titles}), 지금 상태로 갈 수 있는지 요약해`
  }
  if (cell.weekly) {
    return `${cell.date} 주간 신호 정확도 피드백 상태(${WEEKLY_LABELS[cell.weekly] || cell.weekly}) 점검해`
  }
  return `${cell.date} 기준으로 지금 챙겨야 할 게 있는지 봐줘`
}

/** FLAGGED 심각도 → CSS 클래스. 알 수 없는 값은 info 취급(색만 결정하므로 안전). */
export function severityClass(severity) {
  if (severity === 'critical') return 'crit'
  if (severity === 'warning') return 'warn'
  return 'info'
}

/** 게이트 상태 → CSS 클래스. UNKNOWN 은 통과도 차단도 아니므로 별도 색. */
export function gateStateClass(state) {
  if (state === 'OPEN') return 'g-open'
  if (state === 'CLOSED') return 'g-closed'
  return 'g-unknown'
}

/** 원 단위 손익 표시. null 은 0 이 아니라 '조회 실패'다(§4c). */
export function formatKrw(value) {
  if (value === null || value === undefined) return null
  return `${value < 0 ? '-' : ''}${Math.abs(value).toLocaleString('ko-KR')}원`
}

/**
 * 일일손실 서킷 여유 — 한도까지 남은 금액.
 * 손익이나 한도가 없으면 null(추정치를 만들지 않는다).
 */
export function breakerHeadroom(realizedPnlKrw, limitKrw) {
  if (realizedPnlKrw === null || realizedPnlKrw === undefined) return null
  if (limitKrw === null || limitKrw === undefined) return null
  return realizedPnlKrw + limitKrw
}

/** D-day 문구. dDay 가 없으면 null → 화면이 배지를 숨긴다. */
export function ddayLabel(dDay) {
  if (dDay === null || dDay === undefined) return null
  if (dDay === 0) return 'D-DAY'
  return dDay > 0 ? `D-${dDay}` : `D+${Math.abs(dDay)}`
}

/** 기록 경과일 문구 — 오래된 플래그를 눈에 띄게. */
export function ageLabel(ageDays) {
  if (ageDays === null || ageDays === undefined) return null
  if (ageDays <= 0) return '오늘'
  return `${ageDays}일 경과`
}

export default {
  DOW_LABELS,
  WEEKLY_LABELS,
  parseMonth,
  buildCalendarGrid,
  cellClass,
  cellLabel,
  dueLabel,
  instructionForDate,
  severityClass,
  gateStateClass,
  formatKrw,
  breakerHeadroom,
  ddayLabel,
  ageLabel
}
