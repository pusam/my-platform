/**
 * 허브 시간대 국면의 '오늘 휴장' 판정 도우미(2026-10-19).
 *
 * 휴장 여부는 백엔드 달력 한 곳(시장 상태 응답의 marketClosedToday)에서 받는다 — 화면에 두 번째 달력을 두지 않는다. 예전엔 그 값을
 * 시장 데이터 객체(marketData)에만 두어, 상태 조회가 실패하거나(객체를 비운다) 지수가 없는 빈 진단이 오면(변환이 null) 판정이
 * 사라져 평일 공휴일에 다시 '장 진행 중'이 됐다. 이제 날짜와 함께 따로 기억한다.
 */

/** 시각(ms)의 한국 날짜 'YYYY-MM-DD'. */
export function kstDateOf(ms) {
  return new Date(ms).toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' })
}

/** 시장 상태 응답의 '오늘 휴장' — 칸이 없으면 null(모른다). 지수가 없는 빈 진단에도 실린다. */
export function closedTodayFromStatus(raw) {
  if (!raw || typeof raw.marketClosedToday !== 'boolean') return null
  return raw.marketClosedToday
}

/** 기억해 둔 판정이 그 날짜의 휴장인가 — 다른 날짜(어제 받은 것)면 쓰지 않는다. */
export function closedOn(info, dateKst) {
  return !!info && info.date === dateKst && info.closed === true
}
