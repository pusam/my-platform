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
  return cell.weekly ? WEEKLY_SHORT[cell.weekly] || cell.weekly : ''
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
 * 달력 칸 안에 쓰는 짧은 표기 — 좁은 칸에 긴 문구를 넣으면 뭉개진다(2026-08-25 실측).
 * 전체 문구(WEEKLY_LABELS)는 툴팁과 지시문에서 계속 쓴다. ✗/? 구분은 유지 —
 * 짧아져도 "안 돌았다"와 "모른다"를 같은 기호로 합치지 않는다(§4c).
 */
export const WEEKLY_SHORT = {
  SCHEDULED: '주간 예정',
  RAN: '주간 ✓',
  MISSED: '주간 ✗',
  UNKNOWN: '주간 ?'
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

/**
 * 판정 기록 표의 "결정" 5종 — `docs/SCHEDULE_DECISIONS.md` 판정 규칙 그대로.
 * 이 목록을 늘리려면 문서를 먼저 고칠 것(화면이 문서보다 앞서가면 안 된다).
 */
export const DECISIONS = ['유지', '조정', '승격', '판정보류(표본부족)', '판정불가(데이터없음)']

/** 재판정일을 반드시 채워야 하는 결정 — 비워두면 또 잊힌다(문서 규칙). */
const NEEDS_NEXT_DATE = ['판정보류(표본부족)', '판정불가(데이터없음)']

/** 근거에 표본 수가 적혔는지 — n=12 / n≥30 / 12건 / 34% 같은 형태를 인정한다. */
function hasSampleCount(evidence) {
  if (!evidence) return false
  return /n\s*[=≥>]|[0-9]+\s*(건|개|일|%)/i.test(evidence)
}

/**
 * 판정 기록 표에 붙여넣을 마크다운 행 생성 — 순수 함수.
 *
 * ⚠ **파일을 고치지 않는다.** 텍스트만 만들고 붙여넣기는 사람이 한다 — 관제실 읽기 전용
 * 원칙(CLAUDE.md §7)을 지키면서도 "판정 기록 0건" 을 깨는 마지막 한 걸음이다.
 *
 * 문서에 적힌 규칙 3개를 여기서 강제한다:
 *  ① 결정은 5종 중 하나
 *  ② 근거에 표본 수(n) 필수 — n 없는 결론은 표본 부족을 결론으로 위장하는 것(§4c)
 *  ③ 판정보류·판정불가면 재판정일 필수
 *
 * @returns {{row: string, warnings: string[], valid: boolean}}
 *          warnings 는 막지 않고 알려준다 — 규칙을 알면서 넘길 상황도 있다.
 *          valid=false 는 행을 만들 수 없는 경우(안건·결정 누락)뿐이다.
 */
export function buildDecisionRow({ decidedOn, title, decision, evidence, nextAction } = {}) {
  const warnings = []
  const cleanTitle = (title || '').trim()
  const cleanDecision = (decision || '').trim()
  const cleanEvidence = (evidence || '').trim()
  const cleanNext = (nextAction || '').trim()

  if (!cleanTitle) warnings.push('안건을 고르지 않았다')
  if (!cleanDecision) warnings.push('결정을 고르지 않았다')

  if (cleanDecision && !DECISIONS.includes(cleanDecision)) {
    warnings.push(`결정은 ${DECISIONS.join(' / ')} 중 하나여야 한다`)
  }
  if (!cleanEvidence) {
    warnings.push('근거가 비었다 — 표본 수(n)를 포함해 적을 것')
  } else if (!hasSampleCount(cleanEvidence)) {
    warnings.push('근거에 표본 수(n)가 안 보인다 — n 없는 결론은 §4c 위반')
  }
  if (NEEDS_NEXT_DATE.includes(cleanDecision) && !cleanNext) {
    warnings.push('보류·불가는 다음 재판정일을 반드시 채운다 — 비워두면 또 잊힌다')
  }

  // 파이프는 표를 깨므로 치환한다(문서가 마크다운 표라서).
  const cell = (v) => (v || '').replace(/\|/g, '/').replace(/\s+/g, ' ').trim()
  const row = `| ${cell(decidedOn)} | ${cell(cleanTitle)} | ${cell(cleanDecision)} | `
    + `${cell(cleanEvidence)} | ${cell(cleanNext)} |`

  return { row, warnings, valid: !!cleanTitle && !!cleanDecision }
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
  DECISIONS,
  buildDecisionRow,
  WEEKLY_LABELS,
  WEEKLY_SHORT,
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

/**
 * 관제실에서 나갈 목적지 — 관제실은 GNB 없는 단독 라우트라 화면이 직접 출구를 줘야 한다(§7).
 *
 * **목적지는 KPI 가 가리키는 원본 화면으로 고른다.** 관제실은 "무엇이 이상한가"를 보는 곳이고,
 * 이 링크들은 "그럼 그건 어디서 보나"에 대한 답이다. 그 관계가 없는 화면은 넣지 않는다 —
 * 여기에 화면을 늘려 **제2의 GNB 를 만들지 말 것**.
 *
 * ⚠ `tab` 값은 허브의 `mapLegacyTab` 이 아는 키여야 한다. 모르는 키는 예외가 아니라
 * **조용히 'discover' 로 흘러간다** — '오늘'을 눌렀는데 발굴이 열리고 아무도 모른다.
 * `ControlRoomNav.test.js` 가 허브 매핑과 실제로 대조해 그걸 막는다.
 */
export const CONTROL_ROOM_NAV = [
  { key: 'today', label: '오늘', tab: 'today', why: '종합판단 후보 KPI 의 원본 — 매수 후보 목록' },
  { key: 'discover', label: '발굴', tab: 'discover', why: '종합판단 보드 · 백테스트' },
  { key: 'trade', label: '매매', tab: 'trade', why: '봇 게이트 · 일일손실 서킷의 원본 화면' },
  { key: 'screener', label: '재무수집', path: '/earnings-screener', why: '재무 입력층 KPI 의 원본 — 수집 실행/상태' }
]
