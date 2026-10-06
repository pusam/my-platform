/**
 * 봇 상태·성과 문구(2026-10-03). 못 받은 상태를 '중지됨'·'매매 정상'으로, 정의되지 않은 수익 팩터를 '0.00 · 손실'로
 * 보이지 않게 한다(§4c).
 */

/**
 * 봇 카드의 상태 문구. 상태 조회가 아직이거나 실패했으면 '중지됨'이 아니다 — 예전엔 빈 객체가 '중지됨'으로 읽혀
 * 실전 봇이 돌고 있어도 조회가 실패하면 두 카드 모두 '중지됨'이었다.
 */
export function botStatusText(botStatus, mode, { known = true, failed = false } = {}) {
  if (!known) return failed ? '상태 확인 실패' : '확인 중'
  if (!botStatus || botStatus.tradingMode !== mode) return '중지됨'
  switch (botStatus.status) {
    case 'VIX_PAUSED': return '⏸️ VIX 일시정지'
    case 'KOSPI_DROP_PAUSED': return '⏸️ KOSPI 하락 정지'
    case 'STOP_LOSS_PAUSED': return '🛑 연속손절 정지'
    case 'KILL_SWITCH': return '🛑 킬스위치 발동'
    case 'ERROR': return '⚠️ 오류'
    default: return botStatus.active ? '실행 중' : '중지됨'
  }
}

/** 수익 팩터 표시 — null 은 '-'(거래 없음·전부 본전), 999.99 는 '손실 없음'(손실 0 이라 나눗셈이 없다). */
export function profitFactorText(pf) {
  if (pf === null || pf === undefined) return '-'
  const n = Number(pf)
  if (!Number.isFinite(n)) return '-'
  if (n >= 999.99) return '손실 없음'
  return n.toFixed(2)
}

const DAY_MS = 24 * 60 * 60 * 1000

function parseDay(iso) {
  const m = typeof iso === 'string' ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso) : null
  return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : null
}

function isoDay(ms) {
  return new Date(ms).toISOString().slice(0, 10)
}

/**
 * 주간 리포트 히스토리(최신 주부터) 사이의 빈 주를 '리포트 없음' 행으로 채운다(2026-10-06). 예전엔 저장된 행만 돌아
 * 생성되지 않은 주(8/17~8/23 — 서버 다운)가 조용히 빠졌고 13주에 걸친 12행을 '최근 12주'라 했다(§4c). 날짜를 못 읽는
 * 행 사이는 비교하지 않는다.
 */
export function weeklyHistoryWithGaps(rows) {
  if (!Array.isArray(rows)) return []
  const out = []
  rows.forEach((row, i) => {
    out.push(row)
    const cur = parseDay(row?.weekStart)
    const older = parseDay(rows[i + 1]?.weekStart)
    if (cur == null || older == null) return
    for (let start = cur - 7 * DAY_MS; start > older; start -= 7 * DAY_MS) {
      out.push({ missing: true, key: 'missing-' + isoDay(start), weekStart: isoDay(start), weekEnd: isoDay(start + 6 * DAY_MS) })
    }
  })
  return out
}
