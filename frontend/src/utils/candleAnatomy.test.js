import { describe, it, expect } from 'vitest'
import { detectTailSignal } from './candleAnatomy'

describe('detectTailSignal (캔들 꼬리 관찰, 표시 전용)', () => {
  it('긴 아래꼬리 + 작은 몸통 위쪽 마감 → HAMMER(망치형)', () => {
    // 고가 101, 시가 100→종가 100.5(몸통 0.5), 저가 95 → 아래꼬리 5(범위 6의 83%), 위꼬리 0.5
    const sig = detectTailSignal({ open: 100, high: 101, low: 95, close: 100.5 })
    expect(sig?.type).toBe('HAMMER')
    expect(sig.label).toContain('아래꼬리')
  })

  it('긴 위꼬리 + 아래쪽 마감 → SHOOTING_STAR(유성형)', () => {
    // 저가 99.5, 시가 100→종가 100.3, 고가 106 → 위꼬리 5.7, 아래꼬리 0.5
    const sig = detectTailSignal({ open: 100.3, high: 106, low: 99.5, close: 100 })
    expect(sig?.type).toBe('SHOOTING_STAR')
    expect(sig.label).toContain('위꼬리')
  })

  it('평범한 봉(몸통 위주)·양쪽 긴 꼬리(팽이형) → null', () => {
    expect(detectTailSignal({ open: 100, high: 105, low: 99, close: 104.5 })).toBeNull()   // 장대양봉
    expect(detectTailSignal({ open: 100, high: 104, low: 96, close: 100.2 })).toBeNull()   // 양쪽 꼬리(팽이형)
  })

  it('잠자리형 도지(몸통 ≈0 + 긴 아래꼬리)도 HAMMER 로 관찰', () => {
    const sig = detectTailSignal({ open: 100, high: 100.2, low: 95, close: 100 })
    expect(sig?.type).toBe('HAMMER')
  })

  it('비정상 입력(null/0가격/범위 0) → null', () => {
    expect(detectTailSignal(null)).toBeNull()
    expect(detectTailSignal({ open: 0, high: 1, low: 0, close: 1 })).toBeNull()
    expect(detectTailSignal({ open: 100, high: 100, low: 100, close: 100 })).toBeNull()   // 범위 0
  })
})
