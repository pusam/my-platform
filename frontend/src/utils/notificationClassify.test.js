import { describe, it, expect } from 'vitest'
import { classifyCategory, categories } from './notificationClassify.js'

describe('notificationClassify — 알림 카테고리 분류 (RISK 최우선)', () => {
  it('리스크 키워드가 섞인 제목은 BUY_SIGNAL 광범위 키워드(AI 등)보다 RISK 우선', () => {
    // 구 구현(배열 순서 매칭)에선 'AI' 가 먼저 걸려 BUY_SIGNAL 로 오분류 →
    // 리스크만 켜둔 필터에서 경보가 사라졌다
    expect(classifyCategory({ title: 'AI 매수 리스크 경보' })).toBe('RISK')
    expect(classifyCategory({ title: '손실 확대 경보', message: '' })).toBe('RISK')
    expect(classifyCategory({ title: '공매도 과열 주의' })).toBe('RISK')
  })

  it('일반 매수 신호는 BUY_SIGNAL', () => {
    expect(classifyCategory({ title: '강력매수 신호 포착' })).toBe('BUY_SIGNAL')
    expect(classifyCategory({ title: '목표가 도달', message: '수익 +5%' })).toBe('BUY_SIGNAL')
  })

  it('수급/펀더멘털/시장 분류', () => {
    expect(classifyCategory({ title: '외국인 3일 연속 순매수' })).toBe('SUPPLY')
    expect(classifyCategory({ title: '턴어라운드 종목 발견' })).toBe('FUNDAMENTAL')
    expect(classifyCategory({ title: '모닝브리핑' })).toBe('MARKET')
  })

  it('매칭 없는 제목/결측은 ETC', () => {
    expect(classifyCategory({ title: '시스템 점검 안내' })).toBe('ETC')
    expect(classifyCategory({})).toBe('ETC')
    expect(classifyCategory(null)).toBe('ETC')
  })

  it('categories 는 필터 UI 표시용 6종 유지', () => {
    expect(categories.map(c => c.key)).toEqual(
      ['BUY_SIGNAL', 'SUPPLY', 'FUNDAMENTAL', 'MARKET', 'RISK', 'ETC'])
  })
})
