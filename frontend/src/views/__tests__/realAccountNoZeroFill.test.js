import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 실전 계좌 카드 — 모르는 값을 0 으로 채우지 않는다(2026-10-03). 예전엔 mapRealAccount 가 ?? 0 이라
 * 수익률 미계산·잔고 조회 실패가 '+0.00%'·'0원'으로, 보유 목록 조회 실패가 '보유 종목 없음'으로 보였다.
 */
const src = readFileSync(join(process.cwd(), 'src', 'views/PaperTradingPage.vue'), 'utf8')
const mapper = src.slice(src.indexOf('const mapRealAccount'), src.indexOf('const realLoadError'))

describe('PaperTradingPage — 실전 계좌', () => {
  it('재현: mapRealAccount 에 ?? 0 이 없다', () => {
    expect(mapper).not.toMatch(/\?\? 0\b/)
    expect(mapper).toMatch(/profitRate:\s+data\.profitRate\s+\?\? data\.totalProfitRate \?\? null/)
  })

  it('보유 목록 조회 실패는 경고로 말한다', () => {
    expect(src).toMatch(/v-if="realLoadError"/)
    expect(src).toMatch(/realLoadError\.value = portfolioRes\.data\.message/)
  })
})
