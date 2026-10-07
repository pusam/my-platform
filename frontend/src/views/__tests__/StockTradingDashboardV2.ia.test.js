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
      loadMarketLine: vi.fn().mockResolvedValue(), _markWhenReceived: M._markWhenReceived
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
    activeGnbTab: tab, isRefreshing: false, lastUpdated: null, $refs: {},
    loadMarketMap: vi.fn().mockResolvedValue(), loadSupplyPanel: vi.fn().mockResolvedValue(),
    loadMarketLine: vi.fn().mockResolvedValue(), _markWhenReceived: M._markWhenReceived
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
      const market = { ...ctxFor('market'), dataLoaded: { market: true }, loadTabData: M.loadTabData }
      Comp.watch.activeGnbTab.call(market, 'market')
      expect(market.loadMarketMap).toHaveBeenCalledOnce()
      expect(market.loadSupplyPanel).toHaveBeenCalledOnce()

      const today = { ...ctxFor('today'), dataLoaded: { market: true }, loadTabData: M.loadTabData }
      Comp.watch.activeGnbTab.call(today, 'today')
      expect(today.loadMarketLine).toHaveBeenCalledOnce()
    } finally {
      window.scrollTo = scrollTo
    }
  })

  it('재현: 첫 진입에도 시장 지도는 한 번만 — 예전엔 loadTabData 가 dataLoaded 를 바꾼 뒤 그 값을 봐서 같은 지도를 두 번 불렀다', () => {
    const scrollTo = window.scrollTo
    window.scrollTo = vi.fn()
    try {
      // 매매 탭으로 열었다가 처음 시장 탭으로 — 실제 loadTabData(가짜로 두면 이 중복이 안 보인다)
      const first = { ...ctxFor('market'), dataLoaded: { market: false }, loadTabData: M.loadTabData, loadAiStrategy: vi.fn() }
      Comp.watch.activeGnbTab.call(first, 'market')
      expect(first.loadMarketMap).toHaveBeenCalledOnce()
      expect(first.loadSupplyPanel).toHaveBeenCalledOnce()

      // 오늘 탭 첫 진입은 지도가 시장 한 줄까지 채우므로 따로 읽지 않는다
      const today = { ...ctxFor('today'), dataLoaded: { market: false }, loadTabData: M.loadTabData, loadAiStrategy: vi.fn() }
      Comp.watch.activeGnbTab.call(today, 'today')
      expect(today.loadMarketMap).toHaveBeenCalledOnce()
      expect(today.loadMarketLine).not.toHaveBeenCalled()
    } finally {
      window.scrollTo = scrollTo
    }
  })
})

describe('갱신 시각은 실제로 받았을 때만(2026-10-07)', () => {
  const ctxWith = (tab, ok) => ({
    activeGnbTab: tab, isRefreshing: false, lastUpdated: null, nextRefreshIn: 0, $refs: {},
    loadMarketMap: vi.fn().mockResolvedValue(ok), loadSupplyPanel: vi.fn().mockResolvedValue(ok),
    loadMarketLine: vi.fn().mockResolvedValue(ok), _markWhenReceived: M._markWhenReceived
  })

  it('재현: 갱신이 전부 실패하면 시각을 바꾸지 않는다 — 예전엔 실패해도 “방금 갱신”이었다', async () => {
    const ctx = ctxWith('market', false)
    await M._refreshAll.call(ctx)
    expect(ctx.lastUpdated).toBeNull()
    expect(ctx.nextRefreshIn).toBe(60)   // 다음 시도까지 카운트다운은 그대로
  })

  it('하나라도 받았으면 그 시각', async () => {
    const ctx = ctxWith('market', false)
    ctx.loadMarketMap = vi.fn().mockResolvedValue(true)
    await M._refreshAll.call(ctx)
    expect(ctx.lastUpdated).toBeInstanceOf(Date)
  })

  it('발굴 탭은 종합판단 보드가 받았는지로 본다', async () => {
    const ok = ctxWith('discover', false)
    ok.$refs = { judgmentBoard: { refresh: vi.fn().mockResolvedValue(true) } }
    await M._refreshAll.call(ok)
    expect(ok.lastUpdated).toBeInstanceOf(Date)

    const failed = ctxWith('discover', false)
    failed.$refs = { judgmentBoard: { refresh: vi.fn().mockResolvedValue(false) } }
    await M._refreshAll.call(failed)
    expect(failed.lastUpdated).toBeNull()
  })

  it('기다리는 사이 다른 탭으로 옮겼으면 찍지 않는다 — 그 탭의 시각이 아니다', async () => {
    const ctx = ctxWith('market', true)
    let resolveMap
    const pending = M._markWhenReceived.call(ctx, [new Promise(r => { resolveMap = r })], 'market')
    ctx.activeGnbTab = 'discover'
    resolveMap(true)
    expect(await pending).toBe(true)
    expect(ctx.lastUpdated).toBeNull()
  })

  it('탭을 옮기면 이전 탭의 시각을 지우고, 시장 탭은 받은 뒤에 다시 찍는다', async () => {
    const scrollTo = window.scrollTo
    window.scrollTo = vi.fn()
    try {
      const earlier = new Date(Date.now() - 50_000)
      const ctx = { ...ctxWith('market', true), lastUpdated: earlier, dataLoaded: { market: true }, loadTabData: M.loadTabData }
      Comp.watch.activeGnbTab.call(ctx, 'market')
      expect(ctx.lastUpdated).toBeNull()          // 받기 전엔 '대기 중'
      await vi.waitFor(() => expect(ctx.lastUpdated).toBeInstanceOf(Date))
      expect(ctx.lastUpdated.getTime()).toBeGreaterThan(earlier.getTime())

      const discover = { ...ctxWith('discover', true), lastUpdated: earlier, dataLoaded: { market: true },
        loadTabData: M.loadTabData, discoverGroup: 'deep', refreshSectorStrength: vi.fn() }
      Comp.watch.activeGnbTab.call(discover, 'discover')
      expect(discover.lastUpdated).toBeNull()     // 발굴은 보드가 받은 뒤(@loaded) 찍는다
    } finally {
      window.scrollTo = scrollTo
    }
  })

  it('발굴 보드가 스스로 읽은 결과 — 받았을 때만, 발굴 탭일 때만', () => {
    const ctx = { activeGnbTab: 'discover', lastUpdated: null }
    M.onBoardLoaded.call(ctx, false)
    expect(ctx.lastUpdated).toBeNull()
    M.onBoardLoaded.call(ctx, true)
    expect(ctx.lastUpdated).toBeInstanceOf(Date)

    const elsewhere = { activeGnbTab: 'market', lastUpdated: null }
    M.onBoardLoaded.call(elsewhere, true)
    expect(elsewhere.lastUpdated).toBeNull()
  })

  it('발굴 서브탭을 옮기면 이전 화면의 시각을 지운다', () => {
    const ctx = { lastUpdated: new Date() }
    Comp.watch.discoverSubTab.call(ctx, 'backtest')
    expect(ctx.lastUpdated).toBeNull()
  })

  it('갱신 시각 막대는 60초마다 다시 읽는 화면에만 — 백테스트는 다시 읽는 게 없다', () => {
    const show = (tab, sub) => Comp.computed.showFreshness.call({ activeGnbTab: tab, discoverSubTab: sub })
    expect(show('market', 'board')).toBe(true)
    expect(show('discover', 'board')).toBe(true)
    expect(show('discover', 'backtest')).toBe(false)
    expect(show('today', 'board')).toBe(false)
    expect(show('trade', 'board')).toBe(false)
  })

  it('재현: 마운트가 결과와 무관하게 갱신 시각을 찍지 않는다 — 예전엔 1.5초 뒤 무조건 찍었다', async () => {
    const { readFileSync } = await import('node:fs')
    const { join } = await import('node:path')
    const src = readFileSync(join(process.cwd(), 'src', 'views', 'StockTradingDashboardV2.vue'), 'utf8')
    const mountedBody = src.slice(src.indexOf('  mounted() {'), src.indexOf('  beforeUnmount() {'))
    expect(mountedBody).not.toMatch(/this\.lastUpdated = new Date\(\)/)
    expect(mountedBody).toMatch(/_markWhenReceived\(\[firstMap\], 'market'\)/)
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

describe('시장 상태 바의 나스닥은 선물이라고 말한다(2026-10-07)', () => {
  it('재현: NQ=F(나스닥100 선물) 값에 "나스닥"만 붙어 오늘 탭의 간밤 나스닥(현물 지수)과 같은 이름이었다', async () => {
    const { readFileSync } = await import('node:fs')
    const { join } = await import('node:path')
    const src = readFileSync(join(process.cwd(), 'src', 'views', 'StockTradingDashboardV2.vue'), 'utf8')
    const bar = src.slice(src.indexOf('class="market-status-bar"'), src.indexOf('market-status-bar skeleton'))
    expect(bar).toMatch(/nasdaqFutures[\s\S]*<span class="msb-label"[^>]*>나스닥 선물<\/span>/)
    expect(bar).not.toMatch(/<span class="msb-label">나스닥<\/span>/)
  })
})
