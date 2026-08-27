import { describe, it, expect } from 'vitest'
import { ma20PositionLabel, netBuyClass, netBuyText } from './stockFormat'

/**
 * 종목상세 표시층 정직성 — 2026-08-27 감사 수정분 회귀.
 *
 * 감사에서 가장 위험하다고 본 것은 "결측이 안 보이는 것"이 아니라
 * **결측이 틀린 방향의 사실로 뒤집히는 것**이었다. 그 경계를 여기서 못 박는다.
 */
describe('ma20PositionLabel — 3분기(위/아래/판정 불가)', () => {
  it('true/false 는 그대로', () => {
    expect(ma20PositionLabel(true)).toBe('20일선 위')
    expect(ma20PositionLabel(false)).toBe('20일선 아래')
  })

  it('결측을 "20일선 아래"로 뒤집지 않는다 — 삼항이 만들던 결함', () => {
    // 기존 `isAboveMa20 ? '위' : '아래'` 는 null 을 false 분기로 떨어뜨려
    // 미산출(20봉 미만·캐시 미스)을 약세 신호로 확정했다.
    expect(ma20PositionLabel(null)).toBe('판정 불가')
    expect(ma20PositionLabel(undefined)).toBe('판정 불가')
  })

  it('아이콘 판도 결측엔 ❌ 를 붙이지 않는다 — 스크리너 화면', () => {
    expect(ma20PositionLabel(true, true)).toBe('✅ 20일선 위')
    expect(ma20PositionLabel(false, true)).toBe('❌ 20일선 아래')
    expect(ma20PositionLabel(null, true)).toBe('판정 불가')
    expect(ma20PositionLabel(null, true)).not.toContain('❌')
  })
})

describe('netBuyText / netBuyClass — 결측은 방향이 없다', () => {
  it('결측은 "-" 이고 색이 없다 — "0억"을 순매도 색으로 칠하던 결함', () => {
    // 기존: `undefined >= 0` → false → negative 색, `undefined?.toFixed(0) || 0` → "0억"
    // 결과적으로 미수집이 "외국인 순매도 0억"이라는 틀린 방향의 사실로 그려졌다.
    expect(netBuyText(null)).toBe('-')
    expect(netBuyText(undefined)).toBe('-')
    expect(netBuyClass(null)).toBe('')
    expect(netBuyClass(undefined)).toBe('')
  })

  it('실측 0 은 결측과 구분된다 — 데이터가 있고 순매수가 정확히 0인 경우', () => {
    expect(netBuyText(0)).toBe('+0억')
    expect(netBuyClass(0)).toBe('positive')
  })

  it('양수·음수는 부호와 색을 갖는다', () => {
    expect(netBuyText(211)).toBe('+211억')
    expect(netBuyClass(211)).toBe('positive')
    expect(netBuyText(-58.7)).toBe('-59억')
    expect(netBuyClass(-58.7)).toBe('negative')
  })
})
