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

/**
 * 섹터 거래대금 '총 거래대금' — 백엔드가 종목 단위로 한 번만 센 marketTotalTradingValue 를 쓴다(2026-10-02).
 * 섹터 합계를 더하면 여러 섹터에 속한 종목이 섹터 수만큼 더해진다(통신에 들어 있던 삼성전자·SK하이닉스 → 23.58조).
 * 그 필드가 없는 옛 응답만 합산으로 폴백한다.
 */
export function marketTotalOf(sectors) {
  const list = Array.isArray(sectors) ? sectors : []
  const withTotal = list.find((s) => s && s.marketTotalTradingValue != null && Number.isFinite(Number(s.marketTotalTradingValue)))
  if (withTotal) return Number(withTotal.marketTotalTradingValue)
  return list.reduce((sum, s) => sum + (parseFloat(s && s.totalTradingValue) || 0), 0)
}

/** 종목 이름 칸 — 이름이 비었거나 코드와 같으면 한 번만(예전엔 "003490003490"). */
export function stockNameOnce(name, code) {
  const n = typeof name === 'string' ? name.trim() : ''
  if (!n || n === code) return code || '-'
  return n
}

/**
 * '종합 시장 방향성' 등급 → 화면 문구. 백엔드 alertLevel 단일 출처 — 화면이 점수로 다시 나누지 않는다(2026-10-03).
 * 예전엔 시장 타이밍 미니 배너가 점수 60/40 으로 따로 나눠 같은 58점이 글로벌 화면에선 '소폭 강세', 여기선 '중립'이었다.
 * UNKNOWN(해외 시세를 못 받음)은 '판단 보류' — 50점·'보합 예상'으로 보이지 않게.
 */
export function impactLabel(alertLevel) {
  switch (alertLevel) {
    case 'CRISIS': return '폭락 경계'
    case 'EXTREME_NEGATIVE': return '극심한 약세'
    case 'NEGATIVE': return '약세 주의'
    case 'WEAK_NEGATIVE': return '소폭 약세'
    case 'NEUTRAL': return '보합 예상'
    case 'WEAK_POSITIVE': return '소폭 강세'
    case 'POSITIVE': return '강세 예상'
    case 'EXTREME_POSITIVE': return '강한 강세'
    default: return '판단 보류'
  }
}

/** 방향성 색 — positive / negative / neutral / unknown. */
export function impactTone(alertLevel) {
  if (alertLevel === 'CRISIS' || alertLevel === 'EXTREME_NEGATIVE' || alertLevel === 'NEGATIVE' || alertLevel === 'WEAK_NEGATIVE') return 'negative'
  if (alertLevel === 'EXTREME_POSITIVE' || alertLevel === 'POSITIVE' || alertLevel === 'WEAK_POSITIVE') return 'positive'
  if (alertLevel === 'NEUTRAL') return 'neutral'
  return 'unknown'
}

/**
 * 시장 상태(ADR 진단) 문구. 백엔드 MarketTimingDto.MarketCondition 은 JSON 에서 이름 문자열('NORMAL')로 온다 —
 * 예전 화면은 `overallCondition.emoji` 를 읽어 늘 undefined 라 '종합 시장 상태'·시장별 상태 칸이 비었다(2026-10-03).
 * 문구는 그 enum 의 emoji 필드와 같다(바꾸면 함께). 급락일(CRASH)도 포함 — 모르는 값은 null.
 */
const MARKET_CONDITION_TEXT = {
  OVERHEATED: '🔥 과열 (현금 확보 필요)',
  NORMAL: '☁️ 보통',
  OVERSOLD: '💧 침체 (저점 매수 기회)',
  EXTREME_FEAR: '🥶 극심한 공포 (적극 매수 검토)',
  CRASH: '🚨 폭락/패닉 (관망 필수)'
}

export function marketConditionText(condition) {
  if (condition == null) return null
  const key = typeof condition === 'string' ? condition : condition.name
  return MARKET_CONDITION_TEXT[key] ?? null
}

/**
 * 순매수 상위 N종목의 합(억원) — 조회 실패·빈 응답·값 없음은 null(2026-10-03). 예전 시장 탭 수급 패널은 0 에서 더해
 * 실패해도 "+0억"(균형처럼)으로 보였다.
 */
export function topNetSum(rows) {
  if (!Array.isArray(rows) || rows.length === 0) return null
  const nums = rows.map((r) => numOrNull(r && r.netBuyAmount)).filter((v) => v !== null)
  if (nums.length === 0) return null
  return nums.reduce((a, b) => a + b, 0)
}

/** 행들의 가장 최근 거래일 'MM.DD' — 없으면 null. 브라우저 시계가 아니라 데이터의 날짜. */
export function latestTradeDay(rows) {
  if (!Array.isArray(rows)) return null
  const dates = rows.map((r) => r && r.tradeDate).filter((d) => typeof d === 'string' && d.length >= 10).sort()
  return dates.length ? toMonthDay(dates[dates.length - 1]) : null
}

/**
 * PER·PBR 같은 배수 표시 — 없거나 0 이하이면 '-'(2026-10-03). 재무 테이블은 파싱 실패를 0 으로 적어 PER·PBR 의 0 은 결측이다
 * (CLAUDE.md §4c). 예전 화면은 값이 없으면 '-배', 0 이면 '0.0배'였다.
 */
export function multipleOrDash(v, digits = 1) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return '-'
  return `${n.toFixed(digits)}배`
}

/**
 * 종목 상세 PER·PBR 아래 '최근 실적 기준' 줄(2026-10-07). 큰 숫자는 KIS 최근 결산(연간) EPS·BPS 기준이고, 이 줄은 점수·목록
 * (저평가·AI 스윙·마법의 공식)이 쓰는 재무 행 — 최근 4분기 이익·최근 분기 자본 — 을 지금 주가로 다시 나눈 값이다(백엔드 단일
 * 출처 StockDetailService.recentMultiples). 이익이 크게 변한 해엔 몇 배씩 다르다(삼성전자 10/7: 결산 41.4 · 최근 4분기 10.6).
 * 모르면 null(줄을 숨긴다). 비지배 포함 연결(CONSOL)이면 그렇게 적는다.
 */
export function recentMultipleLine(financial, kind) {
  if (!financial) return null
  const isPbr = kind === 'pbr'
  const v = isPbr ? financial.pbrRecent : financial.perRecent
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return null
  const main = Number(isPbr ? financial.pbr : financial.per)
  const prefix = Number.isFinite(main) && main > 0 ? '결산 기준 · ' : ''
  const consol = financial.recentBasis === 'CONSOL' ? ' · 연결' : ''
  return `${prefix}${isPbr ? '최근 분기' : '최근 4분기'} ${n.toFixed(isPbr ? 2 : 1)}배${consol}`
}

/** 위 줄의 설명(툴팁) — 어떤 정의·언제 수집한 재무인지. 줄이 없으면 null. */
export function recentMultipleTitle(financial) {
  if (!financial || (recentMultipleLine(financial, 'per') === null && recentMultipleLine(financial, 'pbr') === null)) return null
  const basis = financial.recentBasis === 'CONSOL' ? 'KIS 연결 — 비지배지분 포함' : 'DART 지배주주'
  const asOf = financial.recentAsOf ? ` · ${financial.recentAsOf} 수집 재무` : ''
  return `점수·목록이 쓰는 값: 최근 4분기 이익·최근 분기 자본(${basis})을 지금 주가로 나눴습니다${asOf}`
}

/**
 * 해외 시세 숫자(가격·차액) — 크기에 맞춘 자릿수(2026-10-06). 10 미만(유로/달러 1.1218·구리 6.65)은 소수 넷째 자리까지,
 * 나머지는 둘째 자리. 예전엔 전부 둘째 자리로 잘라 유로/달러 등락 −0.0005 가 '0.00'이었다. 모르면 '-'.
 */
export function quoteNumberText(v, { signed = false } = {}) {
  if (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) return '-'
  const n = Number(v)
  const text = n.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: Math.abs(n) < 10 ? 4 : 2
  })
  return signed && n > 0 ? '+' + text : text
}

/**
 * 시세 기준 시각 '오전 9:41 기준' — 분까지(2026-10-06). 예전 금·은·원유 화면은 시(時)만 남겨 60초마다 갱신되는 원유가
 * 09:41 에도 '오전 9시 기준'이었다. 모르면 빈 문자열.
 */
export function asOfTimeText(dateTime) {
  if (!dateTime) return ''
  const d = new Date(dateTime)
  if (Number.isNaN(d.getTime())) return ''
  const h = d.getHours()
  const h12 = h % 12 === 0 ? 12 : h % 12
  return `${h < 12 ? '오전' : '오후'} ${h12}:${String(d.getMinutes()).padStart(2, '0')} 기준`
}

/**
 * 시장 폭 등락비 이름표 — 그 값의 거래일로(2026-10-07). 등락 수는 장 마감 뒤(16:30) 확정치라 장 시작 전·장중엔 직전 거래일
 * 값이다 — '당일 등락비'라 부르면 어제 값이 오늘 값처럼 읽힌다(서버 진단 문장 dailyRatioNote 와 같은 'MM/DD' 표기).
 * @param {string|null} analysisDate 시장 상태 응답의 analysisDate(ISO)
 */
export function dailyRatioLabel(analysisDate) {
  if (typeof analysisDate !== 'string' || !/^\d{4}-\d{2}-\d{2}/.test(analysisDate)) return '등락비'
  return `등락비(${analysisDate.slice(5, 7)}/${analysisDate.slice(8, 10)})`
}

/**
 * 지난 시세 표시 — '5시간 전 값'(2026-10-07). 해외 시세는 서버가 마지막 거래가 30분 넘게 지났으면 stale·dataAgeMinutes 를 준다
 * (Yahoo regularMarketTime 기준). 한국 낮에는 미국 현물 지수(VIX·SOX)·국채 금리가 닫혀 있어 그 등락은 지난밤 것인데,
 * 화면은 실시간 선물과 같은 모양으로 그려 오늘 움직임처럼 읽혔다. 실시간이거나 실패·나이를 모르면 빈 문자열.
 */
export function staleQuoteNote(quote) {
  if (!quote || !quote.success || !quote.stale) return ''
  const minutes = Number(quote.dataAgeMinutes)
  if (!Number.isFinite(minutes) || minutes <= 0) return ''
  if (minutes < 60) return `${Math.floor(minutes)}분 전 값`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 전 값`
  return `${Math.floor(hours / 24)}일 전 값`
}

/**
 * 시장 탭 수급 패널 한 줄의 시점(2026-10-07). KIS 순위로 받은 값(asOf = 조회 시각)이면 15:50 전에 받은 것은
 * 'MM.DD HH:mm 장중 잠정', 그 뒤에 받은 것은 'MM.DD 장 마감 집계'. DB 행(asOf 없음)은 장 마감 뒤 확정치만 저장된다
 * (InvestorDailyConfirmation — 같은 15:50 기준)이라 'MM.DD 장 마감 집계'. 예전엔 패널 전체를 '10.07 기준' 하나로 적어
 * 장중 잠정 합계가 하루치처럼 읽혔고, 10시 전 기관 줄(DB 전일)과 외국인 줄(오늘 장중)의 날짜가 섞였다. 행이 없으면 null.
 */
export function supplyBasisLabel(rows) {
  if (!Array.isArray(rows) || rows.length === 0) return null
  const fetched = rows
    .map((r) => r && r.asOf)
    .filter((s) => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s))
    .sort()
  if (fetched.length) {
    const last = fetched[fetched.length - 1]
    const hm = last.slice(11, 16)
    return hm < '15:50' ? `${toMonthDay(last)} ${hm} 장중 잠정` : `${toMonthDay(last)} 장 마감 집계`
  }
  const day = latestTradeDay(rows)
  return day ? `${day} 장 마감 집계` : null
}

/** 연속 순매수 목록이 어느 날까지의 확정치인지 'MM.DD까지' — 마지막 endDate. 없으면 null(2026-10-07). */
export function consecutiveThroughLabel(items) {
  if (!Array.isArray(items)) return null
  const ends = items
    .map((i) => i && i.endDate)
    .filter((d) => typeof d === 'string' && /^\d{4}-\d{2}-\d{2}/.test(d))
    .sort()
  return ends.length ? `${toMonthDay(ends[ends.length - 1])}까지` : null
}

/**
 * 5일 수급의 날짜 수 — '(순매수 3일 · 순매도 2일)'(2026-10-07). 예전 화면은 5일 합이 음수면 무조건 '(연속 순매도 주의!)'라
 * 했다 — 삼성전자 기관은 최근 3일 연속 순매수인데도 그랬다. 모르는 쪽(옛 응답엔 매도일이 없다)은 빼고, 둘 다 없으면 빈 문자열.
 */
export function supplyDaysNote(buyDays, sellDays) {
  const count = (v) => (v === null || v === undefined || !Number.isFinite(Number(v)) ? 0 : Number(v))
  const parts = []
  if (count(buyDays) > 0) parts.push(`순매수 ${count(buyDays)}일`)
  if (count(sellDays) > 0) parts.push(`순매도 ${count(sellDays)}일`)
  return parts.length ? `(${parts.join(' · ')})` : ''
}

/**
 * 턴어라운드 카드의 순이익 변화율(2026-10-07). 이전 분기 순이익이 적자면 %가 아니라 '적자→흑자' — 적자를 분모로 한 변화율은
 * 성장률이 아니다(CLAUDE.md 실적 서프라이즈 규칙, 'FSN −1억 → 49억 · +5000%'). 이전이 0 이거나 값을 모르면 '-'.
 */
export function netIncomeChangeLabel(previous, rate) {
  const p = previous === null || previous === undefined || previous === '' ? null : Number(previous)
  if (p !== null && Number.isFinite(p) && p < 0) return '적자→흑자'
  if (p === 0) return '-'
  const r = rate === null || rate === undefined || rate === '' ? null : Number(rate)
  if (r === null || !Number.isFinite(r)) return '-'
  return `${r > 0 ? '+' : ''}${r.toFixed(2)}%`
}

/**
 * 💎저평가 목록이 무엇을 재는지(2026-10-07) — 화면 문구 단일 출처. 규칙 점수(PBR·이익 대비 주가·부채비율·흑자)이고, 동점은
 * PER 낮은 순, 순이익이 영업이익으로 설명되지 않는 종목은 뺀다(RecommendationService.VALUE_ORDER·EarningsQuality). 실제 성과는
 * 재 본 적이 없다 — '매수 추천'으로 읽히지 않게 그렇게 적는다.
 */
export const VALUE_LIST_BASIS = 'PBR·이익 대비 주가(PER)·부채비율·흑자로 매긴 규칙 점수 · 동점은 PER 낮은 순 · '
  + '순이익이 영업이익으로 설명되지 않는(일회성 이익) 종목 제외 · 실제 성과는 아직 검증 전입니다'
