import { describe, it, expect } from 'vitest'
import { getMarketStatus } from './useMarketStatus'

/**
 * 홈 '시장 HUD' 상태 — ADR 이 없을 때 이유를 가른다(2026-10-02).
 *
 * 재현: 시장 폭 데이터 부족으로 서버가 ADR 을 null(판단 보류)로 주자, 지수·환율은 멀쩡히 받았는데도 HUD 가
 * "데이터 없음 — 시장 데이터를 불러오지 못했습니다"라고 했다. 로그인 첫 화면이 약 3주간 고장 난 것처럼 보일 뻔했다.
 */
describe('getMarketStatus — ADR null 의 두 가지 이유', () => {
  it('응답을 받았는데 ADR 만 없으면 "ADR 판단 보류" — 불러오기 실패가 아니다', () => {
    const s = getMarketStatus(false, null, true)
    expect(s.title).toBe('ADR 판단 보류')
    expect(s.desc).not.toMatch(/불러오지 못했/)
  })

  it('응답 자체를 못 받았으면 종전대로 "데이터 없음"', () => {
    expect(getMarketStatus(false, null, false).title).toBe('데이터 없음')
    expect(getMarketStatus(false, undefined).title).toBe('데이터 없음') // 기본값: 못 받음
  })

  it('폭락 감지가 우선이고, ADR 이 있으면 구간대로(경계값 포함)', () => {
    expect(getMarketStatus(true, null, true).title).toBe('폭락장')
    expect(getMarketStatus(false, 120, true).title).toBe('과열')
    expect(getMarketStatus(false, 85, true).title).toBe('보합')
    expect(getMarketStatus(false, 0, true).title).toBe('침체') // 0 은 값이다(결측 아님)
  })
})
