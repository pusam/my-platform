/**
 * 화면 점검(2026-10-02)에서 나온 "값은 맞는데 이름표가 틀린" 표시들을 한곳에서 — 전부 순수 함수.
 *
 * 공통 원칙: 날짜·시점은 브라우저 시계가 아니라 **데이터가 가진 날짜**로 말한다. 저장된 과거 가격을 "현재가"라고
 * 부르면 실시간 시세 화면(오늘 탭·종목 상세)과 값이 갈려 보인다(CLAUDE.md §1 시세 단일 경로가 막으려던 불일치).
 */

/** 'YYYY-MM-DD' → 'MM.DD'. 못 읽으면 null. */
export function toMonthDay(iso) {
  if (typeof iso !== 'string' || !/^\d{4}-\d{2}-\d{2}/.test(iso)) return null
  return `${iso.slice(5, 7)}.${iso.slice(8, 10)}`
}

/**
 * 매매 동향(투자자별 순매수 상위) 머리말 — 데이터의 거래일로.
 *
 * 서버는 장 마감 확정치만 저장한다(2026-10-01 감사, InvestorDailyConfirmation). 예전 화면은 브라우저 시계로
 * "오늘 HH:mm (잠정)"을 붙여, 장중엔 어제 확정치가 오늘 잠정치처럼 보였고 16:00~16:29 엔 아무 표시도 맞지 않았다.
 *
 * @param {Object<string, Array<{tradeDate?: string}>>} tradesByInvestor 응답 data(투자자 유형 → 행 목록)
 * @returns {{ text: string, status: 'status-confirmed' | 'status-unknown' }}
 */
export function tradeDataLabel(tradesByInvestor) {
  const dates = Object.values(tradesByInvestor || {})
    .flat()
    .map((r) => r && r.tradeDate)
    .filter((d) => typeof d === 'string' && d.length >= 10)
    .sort()
  if (dates.length === 0) return { text: '-', status: 'status-unknown' }
  const latest = toMonthDay(dates[dates.length - 1])
  return { text: `${latest} 장 마감 집계`, status: 'status-confirmed' }
}

const numOrNull = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/**
 * 원자재 카드 한 칸 — 응답 모양 차이를 흡수한다.
 *
 * - 유가(/oil/price): { success, data: { pricePerBarrel, changeRate } } — 예전 화면은 없는 `price` 를 읽어 "$-"였다.
 * 값이 없으면 null(칸을 만들지 않는다). 등락률이 없으면 0 이 아니라 null.
 *
 * @param {object} body axios response.data
 * @param {'OIL'} kind
 */
export function commodityFromResponse(body, kind) {
  if (!body || body.success === false) return null
  const d = body.data && typeof body.data === 'object' ? body.data : body
  const changeRate = numOrNull(d.changeRate)
  if (kind === 'OIL') {
    const price = numOrNull(d.pricePerBarrel ?? d.price)
    return price == null ? null : { symbol: 'OIL', name: 'WTI 원유', currentPrice: price, changeRate, unit: '$' }
  }
  return null
}

/**
 * USD/KRW 칸 — 글로벌 시세 한 건(GlobalFuturesService 의 KRW, Yahoo KRW=X)에서 읽는다(2026-10-02).
 *
 * 예전 출처 /exchange-rate(수출입은행)는 키가 설정된 적이 없고 네이버 폴백도 죽어 늘 비어 있었다(시장 타이밍 카드에서
 * 항목이 빠지고 홈 위젯은 '데이터 지연'). 같은 화면의 "원/달러 1357원" 요약 문장은 이미 이 시세를 쓴다 — 출처가 둘이면
 * 한 화면에 서로 다른 환율이 뜬다(수출입은행 매매기준율은 하루 한 번 고시, 이쪽은 실시간).
 *
 * @param {object} quote 시세 한 건({ currentPrice, changeRate, success })
 */
export function krwFromFuturesQuote(quote) {
  if (!quote || quote.success === false) return null
  const price = numOrNull(quote.currentPrice)
  return price == null ? null : { symbol: 'KRW', name: 'USD/KRW', currentPrice: price, changeRate: numOrNull(quote.changeRate), unit: '원' }
}

/**
 * 저장된 가격(수급 기록 등)의 이름표 — "현재가"가 아니라 그날 종가다.
 *
 * @param {string} isoDate 그 가격의 거래일(YYYY-MM-DD)
 */
export function storedPriceLabel(isoDate) {
  const md = toMonthDay(isoDate)
  return md ? `종가(${md})` : '종가'
}

/**
 * 등락률 칸 — 값이 없으면 '-'(단위 없이). 금·은 시세는 출처가 등락률을 주지 않아 예전엔 0 으로 채워 늘 "0.00%"(보합)였고,
 * null 을 그대로 그리면 "%"만 남는다(2026-10-02).
 */
export function signedPercentOrDash(v) {
  if (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) return '-'
  const n = Number(v)
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
}

/** 종목 이름 칸 — 이름이 비었거나 코드와 같으면 한 번만(예전엔 "003490003490"). */
export function stockNameOnce(name, code) {
  const n = typeof name === 'string' ? name.trim() : ''
  if (!n || n === code) return code || '-'
  return n
}
