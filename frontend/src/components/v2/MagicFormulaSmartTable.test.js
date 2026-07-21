import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MagicFormulaSmartTable, { getBadges } from './MagicFormulaSmartTable.vue'

const STOCKS = [
  { stockCode: '005930', stockName: '삼성전자', magicFormulaRank: 1, per: 8, pbr: 0.9, roe: 18, operatingMargin: 25, marketCap: 4000000 },
  { stockCode: '000660', stockName: 'SK하이닉스', magicFormulaRank: 2, per: 12, pbr: 1.5, roe: 10, operatingMargin: 12, marketCap: 1200000 },
  { stockCode: '035420', stockName: 'NAVER', magicFormulaRank: 3, per: null, pbr: null, roe: null, operatingMargin: null, marketCap: 300000 }
]

describe('MagicFormulaSmartTable — getBadges (순수 export)', () => {
  it('저평가: PER<10 AND PBR<1 둘 다 충족해야 부여', () => {
    const labels = getBadges({ per: 8, pbr: 0.9 }).map(b => b.label)
    expect(labels).toContain('저평가')
    expect(getBadges({ per: 8, pbr: 1.2 }).map(b => b.label)).not.toContain('저평가')
    expect(getBadges({ per: 12, pbr: 0.9 }).map(b => b.label)).not.toContain('저평가')
  })

  it('결측 필드는 뱃지 미부여 — null 을 0/기본값으로 위장하지 않음(§4c)', () => {
    expect(getBadges({ per: null, pbr: null, roe: null, operatingMargin: null })).toEqual([])
  })

  it('저PEG 는 PEG≤1.0 + 시총 500억 이상일 때만 (소형주 노이즈 제외)', () => {
    expect(getBadges({ peg: 0.8, marketCap: 600 }).map(b => b.label)).toContain('저PEG')
    expect(getBadges({ peg: 0.8, marketCap: 300 }).map(b => b.label)).not.toContain('저PEG')
    expect(getBadges({ peg: -0.5, marketCap: 600 }).map(b => b.label)).not.toContain('저PEG')   // 음수 PEG(적자) 제외
  })

  it('고마진(>20%)·성장주(ROE>15)·고배당(>3%)·턴어라운드(+50%) 경계', () => {
    const labels = getBadges({ operatingMargin: 21, roe: 16, dividendYield: 3.5, profitGrowth: 60 }).map(b => b.label)
    expect(labels).toEqual(expect.arrayContaining(['고마진', '성장주', '고배당', '턴어라운드']))
    expect(getBadges({ operatingMargin: 20, roe: 15, dividendYield: 3, profitGrowth: 50 })).toEqual([])   // 경계값 미포함(초과 조건)
  })
})

describe('MagicFormulaSmartTable — 정렬/필터', () => {
  it('smartScore = 순위 기반 환산 (1위=100, 꼴찌=0)', () => {
    const w = mount(MagicFormulaSmartTable, { props: { stocks: STOCKS } })
    const scores = w.vm.enriched.map(s => s.smartScore)
    expect(scores[0]).toBe(100)   // rank 1
    expect(scores[2]).toBe(0)     // rank 3 (총 3개)
  })

  it('기본 정렬 = 순위 asc, ROE 헤더 클릭 시 desc 우선 + 결측은 하단', async () => {
    const w = mount(MagicFormulaSmartTable, { props: { stocks: STOCKS } })
    expect(w.vm.sortedStocks.map(s => s.stockCode)).toEqual(['005930', '000660', '035420'])
    w.vm.toggleSort('roe')
    expect(w.vm.sortDir).toBe('desc')   // 높을수록 좋은 지표는 첫 클릭에 내림차순
    expect(w.vm.sortedStocks.map(s => s.stockCode)).toEqual(['005930', '000660', '035420'])   // null ROE(-9999)는 맨 뒤
  })

  it('검색어는 종목명/코드 모두 매칭', async () => {
    const w = mount(MagicFormulaSmartTable, { props: { stocks: STOCKS } })
    w.vm.query = '하이닉스'
    expect(w.vm.sortedStocks.map(s => s.stockCode)).toEqual(['000660'])
    w.vm.query = '0359'   // 코드 부분 매칭
    expect(w.vm.sortedStocks.map(s => s.stockCode)).toEqual([])
    w.vm.query = '035420'
    expect(w.vm.sortedStocks.map(s => s.stockCode)).toEqual(['035420'])
  })
})
