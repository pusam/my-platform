/**
 * 알림 카테고리 분류 — NotificationBell 필터용 순수 함수(테스트 대상).
 *
 * 분류 우선순위(CLASSIFY_ORDER)는 표시 순서(categories)와 별개다:
 * RISK 를 최우선으로 평가한다 — BUY_SIGNAL 키워드('AI'/'수익'/'손실')가 광범위해서
 * "AI 매수 리스크 경보" 같은 제목이 매수신호로 오분류되면, 리스크만 켜둔 사용자의
 * 필터에서 정작 경보가 사라진다(안전 관련 알림 소실). BUY_SIGNAL 은 가장 마지막.
 */

export const categories = [
  { key: 'BUY_SIGNAL', label: '매수 신호', icon: '🚀',
    keywords: ['강력매수', '매수후보', '매수신호', 'AI', '도달', '수익', '손실'] },
  { key: 'SUPPLY', label: '수급/외인기관', icon: '💰',
    keywords: ['외국인', '기관', '수급', '복합', '연속', '레이더'] },
  { key: 'FUNDAMENTAL', label: '펀더멘털', icon: '📊',
    keywords: ['마법공식', '턴어라운드', '어닝', '실적', 'PEG', '서프라이즈'] },
  { key: 'MARKET', label: '시장 분위기', icon: '🌅',
    keywords: ['모닝브리핑', '브리핑', '시장', '장 마감', '장마감'] },
  { key: 'RISK', label: '리스크', icon: '⚠️',
    keywords: ['공매도', '리스크', '위험', '경보', '주의'] },
  { key: 'ETC', label: '기타', icon: '📌', keywords: [] }
];

const CLASSIFY_ORDER = ['RISK', 'SUPPLY', 'FUNDAMENTAL', 'MARKET', 'BUY_SIGNAL'];

export function classifyCategory(notif) {
  const text = (notif?.title || '') + ' ' + (notif?.message || '');
  for (const key of CLASSIFY_ORDER) {
    const cat = categories.find(c => c.key === key);
    if (cat.keywords.some(k => text.includes(k))) return key;
  }
  return 'ETC';
}
