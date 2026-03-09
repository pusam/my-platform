/**
 * 시장 데이터 공통 포맷터/유틸리티
 * MarketInfoWidget, SectionMarketMap 등에서 공유
 */

// 숫자 포맷 (한국어 로케일)
export function formatNumber(num, decimals = 2) {
  if (num == null) return '-'
  return num.toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  })
}

// 등락률 포맷 (+x.xx%)
export function formatChange(change) {
  if (change == null) return '-'
  const sign = change >= 0 ? '+' : ''
  return `${sign}${change.toFixed(2)}%`
}

// 등락률 CSS 클래스 (inverse: 환율은 상승=부정적)
export function getChangeClass(change, inverse = false) {
  if (change == null) return ''
  if (inverse) {
    return change >= 0 ? 'negative' : 'positive'
  }
  return change >= 0 ? 'positive' : 'negative'
}

// 거래대금 포맷 (조/억/만)
export function formatTradingValue(value) {
  if (!value) return '0원'
  const num = parseFloat(value)
  if (num >= 1e12) return (num / 1e12).toFixed(2) + '조'
  if (num >= 1e8) return Math.round(num / 1e8) + '억'
  if (num >= 1e4) return Math.round(num / 1e4) + '만'
  return num.toLocaleString('ko-KR') + '원'
}
