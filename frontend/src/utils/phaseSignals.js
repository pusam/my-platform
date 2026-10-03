/**
 * '오늘' 탭 시간대 카드(오늘 장 준비 / 오늘 결산) — 2026-10-03 화면 점검.
 *
 * 1) 봇 성과는 계좌 누적 통계다(`/paper-trading/statistics`). 예전엔 '오늘 결산' 안에 "56승 58패 (승률 49.12%)"를
 *    오늘 성적처럼 띄웠다 — 봇은 7/27 부터 꺼져 있다. 또 그 응답엔 손익비가 없어 '손익비 -' 가 늘 붙었다.
 * 2) 등락률은 시세 단일 경로(`/api/stock/{code}` → StockPriceService)의 값만 보인다. 예전엔 카드마다 출처가 달랐다 —
 *    외국인 TOP 은 수급 수집 시각(15:50, NXT 시간외) 가격, AI 픽은 스냅샷 시각 가격이라 같은 화면의 매수 후보 카드와
 *    삼성전기가 +1.28% / +1.02% 로 갈렸다(KRX 종가 기준은 +1.09%). 시세를 못 받으면 비운다(옛 값으로 채우지 않는다).
 */
import { toMonthDay } from './marketDataLabels'

export function botSummarySignal(stats) {
  if (!stats || !(Number(stats.totalTrades) > 0)) return null
  const win = Number(stats.winCount) || 0
  const lose = Number(stats.loseCount) || 0
  const rate = Number(stats.winRate)
  const rateText = win + lose > 0 && stats.winRate != null && Number.isFinite(rate) ? `승률 ${rate.toFixed(2)}%` : '승률 -'
  const today = Number(stats.todayTrades) || 0
  return {
    type: 'bot',
    badge: '🤖 봇 성과(누적)',
    stockName: `${win}승 ${lose}패 · ${rateText}`,
    reason: today > 0 ? `오늘 ${today}건 매매` : '오늘 매매 없음',
    stockCode: null,
    changeRate: null
  }
}

/** 외국인 순매수 카드 문구 — 집계 기준일을 붙인다(주말·장전엔 지난 거래일 값이다). */
export function investorReason(trade) {
  const day = toMonthDay(trade && trade.tradeDate)
  const amount = trade && trade.netBuyAmount != null ? `순매수 ${trade.netBuyAmount}억` : '순매수 -'
  return day ? `${amount} · ${day} 집계` : amount
}

/** 종목 카드의 등락률을 단일 경로 시세로 바꾼다 — 시세가 없으면 null(화면에서 숨김). */
export function withUnifiedChange(signals, quotes) {
  return (signals || []).map(s => {
    if (!s || !s.stockCode) return s
    const q = quotes ? quotes[s.stockCode] : undefined
    const v = q && q.changeRate != null && q.changeRate !== '' ? Number(q.changeRate) : NaN
    return { ...s, changeRate: Number.isFinite(v) ? v : null }
  })
}

/** 시세를 받아야 할 종목코드(중복 제거). */
export function signalCodes(signals) {
  return [...new Set((signals || []).map(s => s && s.stockCode).filter(Boolean))]
}
