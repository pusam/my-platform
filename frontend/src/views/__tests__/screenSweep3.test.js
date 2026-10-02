import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 화면 전수 재점검(2026-10-02 오후)에서 나온 것 — 값이 틀렸거나, 모르는 값을 그럴듯하게 채웠거나, 검증 안 된 판정을 사실처럼 말한 곳.
 */
const read = (p) => readFileSync(join(process.cwd(), 'src', p), 'utf8')

describe('시장 탭 수급 패널 — 순매수 상위 10종목 합을 시장 순매수처럼 말하지 않는다', () => {
  const src = read('views/StockTradingDashboardV2.vue')

  it('재현: "외국인 +2,773억"은 순매수 순위 상위 10종목의 합(늘 +)이었다 — 라벨과 안내가 그걸 밝힌다', () => {
    expect(src).toMatch(/label: '외국인 상위 10'/)
    expect(src).toMatch(/label: '기관 상위 10'/)
    expect(src).toMatch(/순매수 상위 10종목의 합 — 시장 전체 순매수 아님/)
  })

  it('재현: 그 합계로 가르던 "진짜 반등 가능성"/"진짜반등" 판정을 지웠다 — 사실(연속 종목 수)만', () => {
    expect(src).not.toMatch(/'외국인 3일\+ 연속 순매수 — 진짜 반등 가능성'/)
    expect(src).not.toMatch(/'진짜반등'/)
    expect(src).not.toMatch(/isRealRally/)
    expect(src).toMatch(/외국인 3일\+ 연속 순매수 \$\{foreign3Day\}종목/)
  })
})

describe('매매 탭 승률 — 매매가 없으면 "-", 승률에는 부호를 붙이지 않는다', () => {
  const src = read('views/PaperTradingPage.vue')

  it('재현: 매매 0건 주간 리포트가 "승률 +0.00%"였다', () => {
    expect(src).toMatch(/if \(total === 0\) return null;/)
    expect(src).toMatch(/const winRateOrNull = \(rate, tradeCount\) => \(Number\(tradeCount\) > 0 \? rate : null\)/)
  })

  it('승률 칸은 전부 부호 없는 formatWinRate', () => {
    expect(src).not.toMatch(/formatPercent\([^)]*[wW]inRate/)
    expect(src).toMatch(/const formatWinRate = \(value\) =>/)
  })
})

describe('원유·섹터 거래대금 — 모르는 값을 채우지 않는다', () => {
  it('원유 시가·고가·저가는 값이 있을 때만 줄을 그린다(예전엔 백엔드가 현재가로 채워 "시가 = 현재가")', () => {
    const src = read('views/OilPricePage.vue')
    for (const f of ['openPrice', 'highPrice', 'lowPrice']) {
      expect(src).toMatch(new RegExp(`v-if="oilPrice\\.${f} != null"`))
    }
    expect(src).toMatch(/signedPercentOrDash\(oilPrice\.changeRate\)/)
  })

  it('섹터 총 거래대금은 종목을 한 번만 센 값, 비율이 없으면 "-"(0% 아님)', () => {
    const src = read('views/SectorTradingPage.vue')
    expect(src).toMatch(/marketTotalOf\(sectors\.value\)/)
    expect(src).not.toMatch(/sector\.percentage\?\.toFixed\(1\) \|\| 0/)
  })
})
