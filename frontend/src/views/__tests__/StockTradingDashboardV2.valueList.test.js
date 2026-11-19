import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import Comp from '../StockTradingDashboardV2.vue'
import { VALUE_LIST_BASIS } from '../../utils/marketDataLabels'

vi.mock('../../utils/api', async (orig) => {
  const real = await orig()
  return {
    ...real,
    recommendationAPI: { ...real.recommendationAPI, getValueTop10: vi.fn() },
    watchlistAPI: { ...real.watchlistAPI, getList: vi.fn().mockResolvedValue({ data: { data: [] } }) }
  }
})
import { recommendationAPI } from '../../utils/api'

/**
 * 💎저평가 목록을 발굴 탭에 다시 보인다(2026-10-07).
 *
 * <p>재현: 사용자 "주식 가치에 비해 저가인 종목도 볼 수 있는 거지?" — 7/1 발굴 축소로 목록 5트랙이 전부 숨어 있어 저평가 목록은
 * 화면 어디에서도 열 수 없었다(종합판단 '발굴 트랙 포함' 토글에 섞여 나올 뿐). 그날 백엔드에서 그 목록의 입력(부채비율)·순위
 * (이익의 질·동점 순서)를 고쳤다. 나머지 4트랙은 그대로 숨긴다.
 */
describe('발굴 탭 💎저평가 목록', () => {
  const data = Comp.data.call(Object.assign({ $route: { query: {} } }, Comp.methods))

  it('목록 줄이 보이고, 보이는 트랙은 💎저평가·👑주도주 둘이다(2026-11-19) — 나머지 4트랙은 그대로 숨김', () => {
    expect(data.discoverListVisible).toBe(true)
    const shown = Comp.computed.shownDiscoverListTabs.call(data)
    expect(shown.map(t => t.key)).toEqual(['value', 'leaders'])
    expect(shown[0].label).toContain('저평가')
    expect(shown[1].label).toContain('주도주')
  })

  it('발굴 첫 화면은 목록(2026-11-19) — 심화 화면 딥링크(?sub=board·backtest·…)만 종합판단 쪽(deep)으로', () => {
    const group = (query) => Comp.methods.resolveInitialDiscoverGroup.call({ $route: { query } })
    expect(group({})).toBe('list')
    expect(group({ sub: 'value' })).toBe('list')
    expect(group({ sub: 'leaders' })).toBe('list')
    expect(group({ sub: 'zzz' })).toBe('list')
    for (const sub of ['board', 'backtest', 'total', 'ai-strategy', 'screener', 'quant-ta']) {
      expect(group({ sub })).toBe('deep')
    }
    // 첫 화면의 기본 트랙은 저평가, ?sub=leaders 면 주도주
    expect(data.discoverGroup).toBe('list')
    expect(data.discoverListTab).toBe('value')
    expect(Comp.methods.resolveInitialDiscoverList.call({ $route: { query: { sub: 'leaders' } } })).toBe('leaders')
    expect(Comp.methods.resolveInitialDiscoverList.call({ $route: { query: { sub: 'board' } } })).toBe('value')
  })

  it('목록 화면엔 갱신 시각 막대를 띄우지 않는다 — 60초마다 다시 읽지 않는다', () => {
    const show = (group) => Comp.computed.showFreshness.call({ activeGnbTab: 'discover', discoverSubTab: 'board', discoverGroup: group })
    expect(show('list')).toBe(false)
    expect(show('deep')).toBe(true)
  })

  it('서버가 계산 실패(dataAvailable=false)라고 하면 "수집 중"이 아니라 "불러오지 못했습니다"', async () => {
    recommendationAPI.getValueTop10.mockResolvedValueOnce({ data: { success: false, dataAvailable: false, data: [] } })
    const ctx = { valueTopLoading: false, valueTop10: [], valueTopUnavailable: false, valueTopDataTime: '' }
    await Comp.methods.refreshValueTop10.call(ctx)
    expect(ctx.valueTopUnavailable).toBe(true)

    recommendationAPI.getValueTop10.mockResolvedValueOnce({ data: { success: true, dataAvailable: true, data: [{ stockCode: '052790' }], dataTime: '14:10 기준' } })
    await Comp.methods.refreshValueTop10.call(ctx)
    expect(ctx.valueTopUnavailable).toBe(false)
    expect(ctx.valueTop10).toHaveLength(1)

    recommendationAPI.getValueTop10.mockRejectedValueOnce(new Error('network'))
    await Comp.methods.refreshValueTop10.call(ctx)
    expect(ctx.valueTopUnavailable).toBe(false)   // 받아 둔 목록이 있으면 그대로 보인다
    expect(ctx.valueTop10).toHaveLength(1)
  })

  it('재현(11/10): 상위가 전부 만점이라 순서는 PER → PBR 인데 카드엔 그 값이 없었다 — 카드가 PER·PBR 을 보인다', () => {
    const src = readFileSync(join(process.cwd(), 'src/views/StockTradingDashboardV2.vue'), 'utf8')
    expect(src).toContain('{{ valueMultiplesLine(rec) }}')
    expect(Comp.methods.valueMultiplesLine({ valuePer: 1.3, valuePbr: 0.18 })).toBe('PER 1.3배 · PBR 0.18배')
    // 하나만 모르면 '-', 둘 다 모르면 줄을 만들지 않는다(0 은 결측 — §4c)
    expect(Comp.methods.valueMultiplesLine({ valuePer: null, valuePbr: 0.5 })).toBe('PER - · PBR 0.50배')
    expect(Comp.methods.valueMultiplesLine({ valuePer: 0, valuePbr: null })).toBeNull()
    expect(Comp.methods.valueMultiplesLine({})).toBeNull()
  })

  it('목록이 무엇을 재는지·성과 검증 전이라는 문구를 보인다', () => {
    const src = readFileSync(join(process.cwd(), 'src/views/StockTradingDashboardV2.vue'), 'utf8')
    expect(src).toContain('{{ VALUE_LIST_BASIS }}')
    expect(VALUE_LIST_BASIS).toContain('검증 전')
    expect(VALUE_LIST_BASIS).toContain('PER 낮은 순')
    expect(src).toContain('저평가 목록을 불러오지 못했습니다')
  })
})

/**
 * 발굴 탭 갱신 표시가 그 화면과 맞는다(2026-10-19, 리뷰 에이전트 지적).
 * 재현: ① 💎저평가 목록·백테스트는 60초마다 다시 읽지 않는데(갱신 막대도 숨김) 국면 띠는 "실시간 추적 중 · 60초 자동 갱신"이었다.
 * ② 💎저평가에서 🧭종합판단으로 돌아오면 서브탭이 그대로 'board' 라 시각을 지우는 감시가 안 돌아, 새 보드가 읽는 동안·실패해도
 * 예전 보드의 '20분 전'이 남았다.
 */
describe('발굴 탭 갱신 표시', () => {
  it('재현: 60초마다 다시 읽지 않는 화면이면 국면 띠가 "60초 자동 갱신"이라 하지 않는다', () => {
    const banner = Comp.computed.phaseBanner.call({ currentPhaseKey: 'during', showFreshness: false })
    expect(banner.hint).not.toContain('60초 자동 갱신')
    expect(banner.label).toBe('장 진행 중')
  })

  it('다시 읽는 화면(시장·종합판단)이면 종전대로', () => {
    expect(Comp.computed.phaseBanner.call({ currentPhaseKey: 'during', showFreshness: true }).hint).toContain('60초 자동 갱신')
  })

  it('재현: 목록 ↔ 심화를 오가면 이전 화면의 갱신 시각을 지운다', () => {
    const ctx = { lastUpdated: new Date(2026, 9, 19, 10, 0) }
    Comp.watch.discoverGroup.call(ctx, 'deep', 'list')
    expect(ctx.lastUpdated).toBeNull()
  })
})

/**
 * 💎저평가 시가총액 크기 선택(2026-11-19, 사용자 "원하는 건 저평가 종목·국내 주도주").
 * 재현(11/18 운영 자료): 만점 조건을 채우는 약 165종목 중 146개가 시가총액 3천억 원 미만이고 동점은 PER 낮은 순이라 상위 10 이 전부
 * 작은 회사였다 — 큰 회사 가운데 싼 종목을 볼 길이 없었다. 크기를 고르면 점수·순서는 그대로 두고 대상만 줄인다.
 */
describe('💎저평가 시가총액 크기 선택', () => {
  const src = readFileSync(join(process.cwd(), 'src/views/StockTradingDashboardV2.vue'), 'utf8')
  const newCtx = (over = {}) => ({
    valueTopLoading: false, valueTop10: [], valueTopUnavailable: false, valueTopDataTime: '', valueMinCapEok: 0,
    refreshValueTop10: Comp.methods.refreshValueTop10, ...over
  })

  it('선택지는 전체 · 3천억 이상 · 1조 이상 — 기본은 전체', () => {
    const data = Comp.data.call(Object.assign({ $route: { query: {} } }, Comp.methods))
    expect(data.VALUE_SIZE_CHOICES.map(c => c.minCapEok)).toEqual([0, 3000, 10000])
    expect(data.valueMinCapEok).toBe(0)
    expect(src).toContain('v-for="c in VALUE_SIZE_CHOICES"')
    expect(src).toContain('@click="selectValueSize(c.minCapEok)"')
  })

  it('크기를 고르면 그 하한으로 다시 읽고, 다른 크기의 목록을 먼저 비운다', async () => {
    recommendationAPI.getValueTop10.mockReset()
    recommendationAPI.getValueTop10.mockResolvedValueOnce({ data: { dataAvailable: true, data: [{ stockCode: '005930' }], dataTime: '11/19 10:00 기준' } })
    const ctx = newCtx({ valueTop10: [{ stockCode: '900001' }], valueTopDataTime: '옛 시각' })

    Comp.methods.selectValueSize.call(ctx, 10000)
    expect(ctx.valueMinCapEok).toBe(10000)
    expect(ctx.valueTop10).toEqual([])               // 작은 회사 목록을 '1조 이상'이라며 보이지 않는다
    expect(ctx.valueTopDataTime).toBe('')
    expect(recommendationAPI.getValueTop10).toHaveBeenCalledWith(10000)
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(ctx.valueTop10).toEqual([{ stockCode: '005930' }])
    expect(ctx.valueTopLoading).toBe(false)
  })

  it('같은 크기를 다시 누르면 다시 읽지 않는다 — 선택지 밖의 값은 전체로 본다', () => {
    recommendationAPI.getValueTop10.mockReset()
    const ctx = newCtx({ valueMinCapEok: 3000 })
    Comp.methods.selectValueSize.call(ctx, 3000)
    expect(recommendationAPI.getValueTop10).not.toHaveBeenCalled()

    recommendationAPI.getValueTop10.mockResolvedValue({ data: { dataAvailable: true, data: [] } })
    Comp.methods.selectValueSize.call(ctx, 5000)      // 선택지에 없다 → 전체(0)
    expect(ctx.valueMinCapEok).toBe(0)
    expect(recommendationAPI.getValueTop10).toHaveBeenCalledWith(0)
  })

  it('재현 방지: 크기를 빠르게 바꾸면 늦게 온 이전 크기의 응답은 버린다', async () => {
    recommendationAPI.getValueTop10.mockReset()
    let resolveSmall
    recommendationAPI.getValueTop10
      .mockReturnValueOnce(new Promise((r) => { resolveSmall = r }))                                   // 3천억 이상 — 늦게 온다
      .mockResolvedValueOnce({ data: { dataAvailable: true, data: [{ stockCode: 'LARGE' }] } })         // 1조 이상
    const ctx = newCtx()

    Comp.methods.selectValueSize.call(ctx, 3000)
    Comp.methods.selectValueSize.call(ctx, 10000)
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(ctx.valueTop10).toEqual([{ stockCode: 'LARGE' }])

    resolveSmall({ data: { dataAvailable: true, data: [{ stockCode: 'MID' }] } })
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(ctx.valueTop10).toEqual([{ stockCode: 'LARGE' }])    // 3천억 목록이 1조 화면을 덮지 않는다
    expect(ctx.valueTopLoading).toBe(false)
  })

  it('고른 크기는 이 브라우저에 기억한다 — 저장값이 선택지에 없으면 전체', () => {
    recommendationAPI.getValueTop10.mockReset()
    recommendationAPI.getValueTop10.mockResolvedValue({ data: { dataAvailable: true, data: [] } })
    localStorage.removeItem('discover.valueMinCapEok')
    expect(Comp.methods.resolveInitialValueSize.call({})).toBe(0)

    Comp.methods.selectValueSize.call(newCtx(), 3000)
    expect(localStorage.getItem('discover.valueMinCapEok')).toBe('3000')
    expect(Comp.methods.resolveInitialValueSize.call({})).toBe(3000)

    localStorage.setItem('discover.valueMinCapEok', '777')
    expect(Comp.methods.resolveInitialValueSize.call({})).toBe(0)
    localStorage.removeItem('discover.valueMinCapEok')
  })

  it('크기를 고른 목록이 비면 "수집 중"이 아니라 그 크기에 종목이 없다고 말한다', () => {
    expect(src).toContain('이 크기에서 점수를 받은 종목이 없습니다')
    expect(src.indexOf('v-else-if="valueMinCapEok > 0"')).toBeLessThan(src.indexOf('저평가 종목 데이터 수집 중'))
  })

  it('카드에 시가총액을 보인다 — 작은 회사인지 큰 회사인지', () => {
    expect(Comp.methods.valueMultiplesLine({ valuePer: 6, valuePbr: 0.65, valueMarketCapEok: 150000 })).toBe('PER 6.0배 · PBR 0.65배 · 시총 15.0조')
    expect(Comp.methods.valueMultiplesLine({ valuePer: 0.5, valuePbr: 0.2, valueMarketCapEok: 682 })).toBe('PER 0.5배 · PBR 0.20배 · 시총 682억')
    expect(Comp.methods.valueMultiplesLine({ valuePer: 0.5, valuePbr: 0.2, valueMarketCapEok: null })).toBe('PER 0.5배 · PBR 0.20배')
  })
})

/** 👑주도주 목록은 발굴 탭 목록 줄에서 연다(2026-11-19) — 스스로 읽는 컴포넌트라 허브가 따로 부르지 않는다. */
describe('발굴 탭 👑주도주 목록', () => {
  const src = readFileSync(join(process.cwd(), 'src/views/StockTradingDashboardV2.vue'), 'utf8')

  it('목록 줄에서 주도주를 고르면 그 화면만 그린다 — 누른 종목은 상세로', () => {
    expect(src).toContain(`<SectionLeaders v-if="activeGnbTab === 'discover' && discoverGroup === 'list' && discoverListTab === 'leaders'"`)
    expect(src).toContain('@open-stock="goToStock"')
    const ctx = { discoverGroup: 'deep', discoverListTab: 'value', ensureDiscoverListLoaded: vi.fn() }
    Comp.methods.selectDiscoverList.call(ctx, 'leaders')
    expect(ctx.discoverGroup).toBe('list')
    expect(ctx.discoverListTab).toBe('leaders')
  })

  it('주도주를 골라도 다른 목록 API 를 부르지 않는다(보는 목록만 읽는다)', () => {
    const ctx = {
      valueTop10: [], growthTop10: [], oversoldTop10: [], earningsTop10: [], smartMoneyTop10: [],
      refreshValueTop10: vi.fn(), refreshGrowthTop10: vi.fn(), refreshOversoldTop10: vi.fn(),
      refreshEarningsTop10: vi.fn(), refreshSmartMoneyTop10: vi.fn()
    }
    Comp.methods.ensureDiscoverListLoaded.call(ctx, 'leaders')
    for (const fn of [ctx.refreshValueTop10, ctx.refreshGrowthTop10, ctx.refreshOversoldTop10, ctx.refreshEarningsTop10, ctx.refreshSmartMoneyTop10]) {
      expect(fn).not.toHaveBeenCalled()
    }
  })

  it('목록 화면엔 갱신 시각 막대를 띄우지 않는다 — 하루 한 번 바뀌는 목록이다', () => {
    const show = Comp.computed.showFreshness.call({ activeGnbTab: 'discover', discoverSubTab: 'board', discoverGroup: 'list' })
    expect(show).toBe(false)
  })
})

/**
 * 발굴 첫 화면이 목록이 되면서(2026-11-19) 허브를 열 때 도는 요약 로더가 '오늘' 탭에서도 저평가 목록을 미리 읽게 됐다 —
 * 보는 화면만 읽는다(목록 계산은 첫 호출에 수 초 + 공시 검사 30건).
 */
describe('발굴 목록은 발굴 탭을 볼 때만 읽는다', () => {
  const ctxOn = (tab) => ({
    activeGnbTab: tab, discoverGroup: 'list', discoverListTab: 'value', watchlistItems: [], watchlistRisks: {}, phaseLoading: false,
    extractData: (res) => res?.data?.data ?? res?.data ?? res,
    loadChartSignals: vi.fn(), loadStrongSectors: vi.fn(), loadSupplyPanel: vi.fn(), loadPhaseData: vi.fn(),
    ensureDiscoverListLoaded: vi.fn()
  })

  it("재현: '오늘' 탭으로 열면 저평가 목록을 미리 읽지 않는다", async () => {
    const ctx = ctxOn('today')
    await Comp.methods.loadTodaySummary.call(ctx)
    expect(ctx.ensureDiscoverListLoaded).not.toHaveBeenCalled()
    expect(ctx.loadSupplyPanel).toHaveBeenCalled()      // 나머지 요약은 종전대로
  })

  it('발굴 탭으로 바로 열면(?tab=discover) 보고 있는 목록을 읽는다', async () => {
    const ctx = ctxOn('discover')
    await Comp.methods.loadTodaySummary.call(ctx)
    expect(ctx.ensureDiscoverListLoaded).toHaveBeenCalledWith('value')
  })
})
