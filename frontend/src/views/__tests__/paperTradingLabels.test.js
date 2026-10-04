import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 매매 탭 이름표 — 2026-10-04.
 * 재현: 실전 '예수금' 칸은 KIS nxdy_excc_amt(D+1 익일정산 금액)인데 그냥 '예수금'이라 했고, 실전 주문은 접수만 했는데
 * 서버 문구가 없으면 '거래가 체결되었습니다'라고 했다.
 */
const src = readFileSync(join(process.cwd(), 'src/views/PaperTradingPage.vue'), 'utf8')

describe('PaperTradingPage 이름표', () => {
  it('재현: 실전 현금 칸은 D+1 정산 기준이라고 밝힌다', () => {
    expect(src).not.toMatch(/<span class="label">예수금<\/span>/)
    expect(src).toMatch(/예수금\(D\+1 정산\)/)
  })

  it('재현: 실전 주문 기본 문구는 접수 — 체결이라 하지 않는다', () => {
    expect(src).toMatch(/tradeMode\.value === 'real' \? '주문이 접수되었습니다'/)
    expect(src).toMatch(/sellMode\.value === 'real' \? '매도 주문이 접수되었습니다'/)
  })
})

describe('갱신 시각 — 실패한 갱신을 "방금"으로 말하지 않는다(2026-10-04)', () => {
  it('재현: 매매 탭 loadData 는 하나라도 받았을 때만 lastUpdated 를 바꾼다(예전엔 finally 에서 늘 new Date())', () => {
    expect(src).toMatch(/if \(results\.some\(Boolean\)\) lastUpdated\.value = new Date\(\);/)
    expect(src).not.toMatch(/isRefreshing\.value = false;\s*lastUpdated\.value = new Date\(\);/)
  })
})
