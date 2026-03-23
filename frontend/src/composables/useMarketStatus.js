/**
 * 시장 상태 공통 컴포저블
 * 폭락 감지, ADR 분류, VIX 구간 분류, 시장 상태 텍스트를 한 곳에서 관리
 *
 * 사용처: MarketInfoWidget.vue, SectionMarketMap.vue, GlobalFuturesPage.vue
 */

// ========== 폭락 감지 ==========

// 다양한 필드명에서 KOSPI 등락률 추출
export function extractKospiRate(data) {
  if (!data) return null
  const rate = data.kospiChange ?? data.kospiChangeRate ?? data.kospi_change_rate
    ?? data.kospiRate ?? data.kospi?.indexChangeRate ?? null
  return rate !== null && rate !== undefined ? Number(rate) : null
}

// 다양한 필드명에서 KOSDAQ 등락률 추출
export function extractKosdaqRate(data) {
  if (!data) return null
  const rate = data.kosdaqChange ?? data.kosdaqChangeRate ?? data.kosdaq_change_rate
    ?? data.kosdaqRate ?? data.kosdaq?.indexChangeRate ?? null
  return rate !== null && rate !== undefined ? Number(rate) : null
}

// 폭락 여부 판정 (KOSPI/KOSDAQ -3% 이하 또는 진단 텍스트)
const CRASH_THRESHOLD = -3
const CRASH_KEYWORDS = ['폭락', '패닉', 'CRASH']

export function checkCrash(data) {
  if (!data) return false

  const kospiRate = extractKospiRate(data)
  const kosdaqRate = extractKosdaqRate(data)

  if (kospiRate !== null && kospiRate <= CRASH_THRESHOLD) return true
  if (kosdaqRate !== null && kosdaqRate <= CRASH_THRESHOLD) return true

  const diag = data.diagnosis || data.marketStatus || ''
  return CRASH_KEYWORDS.some(kw => diag.includes(kw))
}

// ========== ADR 기반 시장 상태 ==========

const ADR_TIERS = [
  { min: 120, status: 'overheated', title: '과열', icon: '🔥', desc: '추격 매수 주의, 익절 고려', badgeClass: 'badge-danger', adrClass: 'adr-hot' },
  { min: 100, status: 'bullish',    title: '강세', icon: '📈', desc: '상승 추세, 눌림목 매수 유효', badgeClass: 'badge-success', adrClass: 'adr-normal' },
  { min: 80,  status: 'normal',     title: '보합', icon: '➡️', desc: '방향성 탐색 중',             badgeClass: 'badge-neutral', adrClass: 'adr-normal' },
  { min: 60,  status: 'bearish',    title: '약세', icon: '📉', desc: '하락 추세, 반등 대기',        badgeClass: 'badge-warning', adrClass: 'adr-cold' },
  { min: -Infinity, status: 'extreme-fear', title: '침체', icon: '💎', desc: '저점 매수 기회 탐색', badgeClass: 'badge-info', adrClass: 'adr-cold' }
]

const CRASH_STATUS = {
  status: 'crash',
  title: '폭락장',
  icon: '🚨',
  desc: '관망 및 리스크 관리 필수',
  badgeClass: 'badge-crash',
  adrClass: 'adr-crash',
  displayText: '🚨 폭락장 (관망 및 리스크 관리 필수)'
}

const NO_DATA_STATUS = {
  status: '',
  title: '데이터 없음',
  icon: '📊',
  desc: '시장 데이터를 불러오지 못했습니다.',
  badgeClass: '',
  adrClass: 'adr-cold',
  displayText: ''
}

// ADR 값으로 시장 상태 정보 전체를 반환
export function getMarketStatus(isCrash, adr) {
  if (isCrash) return CRASH_STATUS
  if (adr == null) return NO_DATA_STATUS
  for (const tier of ADR_TIERS) {
    if (adr >= tier.min) return tier
  }
  return ADR_TIERS[ADR_TIERS.length - 1]
}

// ========== VIX 공포지수 구간 분류 ==========

const VIX_TIERS = [
  { min: 30, cssClass: 'vix-extreme', text: '극심한 공포', emoji: '!!' },
  { min: 25, cssClass: 'vix-fear',    text: '공포',       emoji: '!' },
  { min: 20, cssClass: 'vix-caution', text: '경계',       emoji: '~' },
  { min: 15, cssClass: 'vix-normal',  text: '보통',       emoji: '' },
  { min: -Infinity, cssClass: 'vix-calm', text: '안정',   emoji: '' }
]

// VIX 값으로 구간 정보 반환
export function getVixStatus(vixValue) {
  const v = Number(vixValue) || 0
  for (const tier of VIX_TIERS) {
    if (v >= tier.min) return tier
  }
  return VIX_TIERS[VIX_TIERS.length - 1]
}

// VIX 미터 게이지 너비 (0~50+ → 0~100%)
export function getVixMeterWidth(vixValue) {
  return Math.min(100, ((Number(vixValue) || 0) / 50) * 100)
}
