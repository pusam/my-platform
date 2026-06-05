import { describe, it, expect } from 'vitest'
import Comp from '../StockTradingDashboardV2.vue'

// 마운트 없이 매핑 메서드만 검증 (무거운 자식/ API 회피).
const M = Comp.methods

function call(name, thisArg, ...args) {
  return M[name].call(thisArg, ...args)
}

describe('StockTradingDashboardV2 IA 매핑 (P-IA 2단계)', () => {
  describe('mapLegacyTab → market/discover/trade', () => {
    const cases = {
      market: ['market', 'sector', 'news', 'investor', 'timing', 'global'],
      discover: ['discover', 'analysis', 'research', 'premarket', 'live'],
      trade: ['trade', 'trading', 'paper-trading']
    }
    for (const [target, inputs] of Object.entries(cases)) {
      for (const input of inputs) {
        it(`${input} → ${target}`, () => {
          expect(call('mapLegacyTab', {}, input)).toBe(target)
        })
      }
    }
    it('알 수 없는 값 → discover(기본 히어로)', () => {
      expect(call('mapLegacyTab', {}, 'zzz')).toBe('discover')
    })
  })

  describe('resolveInitialTab', () => {
    const fakeThis = (tab) => ({ $route: { query: tab ? { tab } : {} }, mapLegacyTab: M.mapLegacyTab })
    it('?tab=trading → trade', () => {
      expect(call('resolveInitialTab', fakeThis('trading'))).toBe('trade')
    })
    it('?tab=sector → market', () => {
      expect(call('resolveInitialTab', fakeThis('sector'))).toBe('market')
    })
    it('쿼리 없으면 market/discover 중 하나(시각 기반)', () => {
      expect(['market', 'discover']).toContain(call('resolveInitialTab', fakeThis(null)))
    })
  })

  describe('phaseBanner 강조 (위젯 교체 X, 같은 탭 내 강조)', () => {
    const banner = (phase) => Comp.computed.phaseBanner.call({ currentPhaseKey: phase })
    it('during → 실시간 강조(phase-during)', () => {
      const b = banner('during')
      expect(b.cls).toBe('phase-during')
      expect(b.label).toContain('진행')
    })
    it('pre → 준비(phase-pre)', () => {
      expect(banner('pre').cls).toBe('phase-pre')
    })
    it('post → 결산(phase-post)', () => {
      expect(banner('post').cls).toBe('phase-post')
    })
    it('알 수 없는 phase → post 폴백', () => {
      expect(banner('zzz').cls).toBe('phase-post')
    })
  })
})
