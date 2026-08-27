
/**
 * 20일선 위치 라벨 — **3분기다**(위 / 아래 / 판정 불가).
 *
 * 2026-08-27 표시층 감사 C-2·C-3: 삼항 `isAboveMa20 ? '위' : '아래'` 로 쓰면
 * null(미산출 — 20봉 미만·캐시 미스)이 **false 분기로 떨어져 "20일선 아래"라는
 * 약세 신호로 뒤집힌다.** 결측은 '없음'이지 '아래'가 아니다(§4c).
 *
 * 같은 값을 `QuickSummaryBar` 는 이미 `-` 로 그린다. 두 화면이 어긋나면
 * 사용자가 어느 쪽을 믿어야 할지 알 수 없으므로 이 함수로 통일한다.
 *
 * @param {boolean|null|undefined} isAbove 백엔드 technicalAnalysis.isAboveMa20
 * @param {boolean} withIcon ✅/❌ 아이콘을 붙일지(스크리너 화면 규약)
 */
export function ma20PositionLabel(isAbove, withIcon = false) {
  if (isAbove === true) return withIcon ? '✅ 20일선 위' : '20일선 위'
  if (isAbove === false) return withIcon ? '❌ 20일선 아래' : '20일선 아래'
  return '판정 불가'   // null/undefined — 미산출을 방향성 있는 신호로 바꾸지 않는다
}

/**
 * 순매수 표시 — 결측은 **방향이 없다**(2026-08-27 표시층 감사 A-3~A-6).
 *
 * 기존 패턴 `v >= 0 ? 'positive' : 'negative'` + `v?.toFixed(0) || 0` 은
 * `undefined >= 0` 이 **false** 라 결측을 '순매도 색'으로 칠하고, `|| 0` 이 "0억"을 만들어
 * **"외국인 순매도 0억"이라는 틀린 방향의 사실**을 그렸다.
 *
 * 실측 0(그날 데이터가 있고 순매수가 정확히 0)은 '+0억'으로 그대로 나간다 — 구분되는 상태다.
 */
export function netBuyClass(v) {
  return v == null ? '' : (v >= 0 ? 'positive' : 'negative')
}

export function netBuyText(v) {
  if (v == null) return '-'
  return (v >= 0 ? '+' : '') + Number(v).toFixed(0) + '억'
}
