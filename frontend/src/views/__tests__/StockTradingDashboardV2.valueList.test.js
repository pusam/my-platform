import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import Comp from '../StockTradingDashboardV2.vue'
import { VALUE_LIST_BASIS } from '../../utils/marketDataLabels'

vi.mock('../../utils/api', async (orig) => {
  const real = await orig()
  return { ...real, recommendationAPI: { ...real.recommendationAPI, getValueTop10: vi.fn() } }
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

  it('재현: 목록 줄이 보이고, 보이는 트랙은 💎저평가 하나뿐이다', () => {
    expect(data.discoverListVisible).toBe(true)
    const shown = Comp.computed.shownDiscoverListTabs.call(data)
    expect(shown.map(t => t.key)).toEqual(['value'])
    expect(shown[0].label).toContain('저평가')
  })

  it('딥링크 ?sub=value 는 목록으로, 그 밖은 종합판단(deep)', () => {
    expect(Comp.methods.resolveInitialDiscoverGroup.call({ $route: { query: { sub: 'value' } } })).toBe('list')
    expect(Comp.methods.resolveInitialDiscoverGroup.call({ $route: { query: {} } })).toBe('deep')
    expect(Comp.methods.resolveInitialDiscoverGroup.call({ $route: { query: { sub: 'board' } } })).toBe('deep')
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

  it('목록이 무엇을 재는지·성과 검증 전이라는 문구를 보인다', () => {
    const src = readFileSync(join(process.cwd(), 'src/views/StockTradingDashboardV2.vue'), 'utf8')
    expect(src).toContain('{{ VALUE_LIST_BASIS }}')
    expect(VALUE_LIST_BASIS).toContain('검증 전')
    expect(VALUE_LIST_BASIS).toContain('PER 낮은 순')
    expect(src).toContain('저평가 목록을 불러오지 못했습니다')
  })
})
