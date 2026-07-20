/**
 * 시장 데이터 공통 포맷터/유틸리티
 * MarketInfoWidget, SectionMarketMap 등에서 공유
 */

// 숫자 포맷 (한국어 로케일) — 문자열 숫자도 코어싱(API string 방어), 비숫자는 '-'
export function formatNumber(num, decimals = 2) {
  const n = Number(num)
  if (num == null || Number.isNaN(n)) return '-'
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  })
}

// 등락률 포맷 (+x.xx%) — 문자열 숫자 코어싱, 비숫자는 '-'
export function formatChange(change) {
  const n = Number(change)
  if (change == null || Number.isNaN(n)) return '-'
  const sign = n >= 0 ? '+' : ''
  return `${sign}${n.toFixed(2)}%`
}

// 등락률 CSS 클래스 (inverse: 환율은 상승=부정적)
export function getChangeClass(change, inverse = false) {
  if (change == null) return ''
  if (inverse) {
    return change >= 0 ? 'negative' : 'positive'
  }
  return change >= 0 ? 'positive' : 'negative'
}

// 거래대금 포맷 (조/억/만) — 결측(null/undefined/비숫자)은 '-'(§4c, 0원 거래로 위장 금지), 실측 0 만 '0원'
export function formatTradingValue(value) {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '-'
  if (Number(value) === 0) return '0원'
  const num = parseFloat(value)
  if (num >= 1e12) return (num / 1e12).toFixed(2) + '조'
  if (num >= 1e8) return Math.round(num / 1e8) + '억'
  if (num >= 1e4) return Math.round(num / 1e4) + '만'
  return num.toLocaleString('ko-KR') + '원'
}
