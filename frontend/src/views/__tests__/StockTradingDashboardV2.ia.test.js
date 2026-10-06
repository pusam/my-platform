import { describe, it, expect, vi } from 'vitest'
import Comp from '../StockTradingDashboardV2.vue'

// 마운트 없이 매핑 메서드만 검증 (무거운 자식/ API 회피).
const M = Comp.methods

describe('후보·발굴 갱신 연결', () => {
  it.each(['today', 'discover'])('%s 갱신은 표시 중인 추천 자식까지 기다린다', async (tab) => {
    const refresh = vi.fn().mockResolvedValue()
    const ctx = {
      activeGnbTab: tab, isRefreshing: false,
      $refs: tab === 'today' ? { todayBriefing: { refresh } } : { judgmentBoard: { refresh } },
      loadMarketMap: vi.fn().mockResolvedValue(), loadSupplyPanel: vi.fn().mockResolvedValue(),
      loadMarketLine: vi.fn().mockResolvedValue()
    }
    await M._refreshAll.call(ctx)
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('오늘 탭도 기존 60초 폴링으로 후보를 다시 읽는다', () => {
    vi.useFakeTimers()
    const ctx = { activeGnbTab: 'today', isLiveTab: false, _refreshAll: vi.fn() }
    M._startPolling.call(ctx)
    try {
      vi.advanceTimersByTime(60000)
      expect(ctx._refreshAll).toHaveBeenCalledOnce()
    } finally {
      M._stopPolling.call(ctx)
      vi.useRealTimers()
    }
  })
})

describe('60초 갱신은 보이는 탭의 데이터만(2026-10-07)', () => {
  const ctxFor = (tab) => ({
    activeGnbTab: tab, isRefreshing: false, $refs: {},
    loadMarketMap: vi.fn().mockResolvedValue(), loadSupplyPanel: vi.fn().mockResolvedValue(),
    loadMarketLine: vi.fn().mockResolvedValue()
  })

  it('재현: 발굴 탭에선 시장 탭 데이터(시장 지도 5콜·수급 패널 3콜)를 다시 읽지 않는다 — 화면에 없다', async () => {
    const ctx = ctxFor('discover')
    await M._refreshAll.call(ctx)
    expect(ctx.loadMarketMap).not.toHaveBeenCalled()
    expect(ctx.loadSupplyPanel).not.toHaveBeenCalled()
  })

  it('재현: 오늘 탭은 시장 한 줄(KOSPI·KOSDAQ·진단)을 다시 읽는다 — 예전엔 페이지를 연 시점 값 그대로였다', async () => {
    const ctx = ctxFor('today')
    await M._refreshAll.call(ctx)
    expect(ctx.loadMarketLine).toHaveBeenCalledOnce()
    expect(ctx.loadMarketMap).not.toHaveBeenCalled()
  })

  it('시장 탭은 지도·수급을 다시 읽는다', async () => {
    const ctx = ctxFor('market')
    await M._refreshAll.call(ctx)
    expect(ctx.loadMarketMap).toHaveBeenCalledOnce()
    expect(ctx.loadSupplyPanel).toHaveBeenCalledOnce()
  })

  it('다른 탭에 있다 돌아오면 그 탭 데이터를 바로 한 번 읽는다 — 다른 탭에선 갱신하지 않으니까', () => {
    const scrollTo = window.scrollTo
    window.scrollTo = vi.fn()
    try {
      const market = { ...ctxFor('market'), dataLoaded: { market: true }, loadTabData: vi.fn() }
      Comp.watch.activeGnbTab.call(market, 'market')
      expect(market.loadMarketMap).toHaveBeenCalledOnce()
      expect(market.loadSupplyPanel).toHaveBeenCalledOnce()

      const today = { ...ctxFor('today'), dataLoaded: { market: true }, loadTabData: vi.fn() }
      Comp.watch.activeGnbTab.call(today, 'today')
      expect(today.loadMarketLine).toHaveBeenCalledOnce()

      const first = { ...ctxFor('market'), dataLoaded: { market: false }, loadTabData: vi.fn() }
      Comp.watch.activeGnbTab.call(first, 'market')
      expect(first.loadMarketMap).not.toHaveBeenCalled()   // 첫 진입은 loadTabData 가 이미 읽는다
    } finally {
      window.scrollTo = scrollTo
    }
  })
})

describe('시장 지도는 화면에 쓰는 것만 부른다(2026-10-07)', () => {
  it('재현: 선행 섹터·USD/KRW 는 받아서 버리고 있었다 — 시장 지도 컴포넌트는 globalData 에서 나스닥 선물만 쓴다', async () => {
    const { readFileSync } = await import('node:fs')
    const { join } = await import('node:path')
    const src = readFileSync(join(process.cwd(), 'src', 'views', 'StockTradingDashboardV2.vue'), 'utf8')
    const body = src.slice(src.indexOf('    async loadMarketMap()'), src.indexOf('    // Section A: AI 전략'))
    expect(body).toMatch(/getNasdaqFutures\(\)/)
    expect(body).not.toMatch(/getLeadingSectors\(\)/)
    expect(body).not.toMatch(/getQuote\('KRW'\)/)
  })
})

function call(name, thisArg, ...args) {
  return M[name].call(thisArg, ...args)
}

describe('StockTradingDashboardV2 IA 매핑 (P-IA 2단계)', () => {
  describe('mapLegacyTab → today/market/discover/trade', () => {
    const cases = {
      today: ['today', 'home', 'briefing'],
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
    it('쿼리 없으면 today — P-IA 3단계: 홈은 항상 오늘의 결론', () => {
      expect(call('resolveInitialTab', fakeThis(null))).toBe('today')
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
