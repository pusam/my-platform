import { describe, it, expect } from 'vitest'
import { botStatusText, profitFactorText } from './botStatusLabels'

describe('botStatusText — 못 받은 봇 상태는 중지됨이 아니다(2026-10-03)', () => {
  it('재현: 조회 실패면 상태 확인 실패 — 예전엔 빈 객체가 두 카드 모두 중지됨', () => {
    expect(botStatusText({}, 'REAL', { known: false, failed: true })).toBe('상태 확인 실패')
    expect(botStatusText({}, 'REAL', { known: false, failed: false })).toBe('확인 중')
  })

  it('받은 상태는 종전 규칙대로', () => {
    expect(botStatusText({ tradingMode: 'REAL', status: 'RUNNING', active: true }, 'REAL')).toBe('실행 중')
    expect(botStatusText({ tradingMode: 'REAL', status: 'RUNNING', active: true }, 'VIRTUAL')).toBe('중지됨')
    expect(botStatusText({ tradingMode: 'VIRTUAL', status: 'KILL_SWITCH', active: true }, 'VIRTUAL')).toBe('🛑 킬스위치 발동')
  })
})

describe('profitFactorText — 정의되지 않은 수익 팩터를 0.00 으로 보이지 않는다', () => {
  it('재현: null(거래 없음·전부 본전)은 -, 999.99(손실 없음)는 문구로', () => {
    expect(profitFactorText(null)).toBe('-')
    expect(profitFactorText(undefined)).toBe('-')
    expect(profitFactorText(999.99)).toBe('손실 없음')
  })

  it('실제 값은 소수 둘째 자리', () => {
    expect(profitFactorText(0)).toBe('0.00')
    expect(profitFactorText(1.234)).toBe('1.23')
  })
})
