import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 종목 상세 자동 갱신 머리말 — 2026-10-04.
 * 재현: 토글은 "10초 자동 갱신"인데 실제 주기는 15초였고, 상태는 '실시간 감시 중', 옆 시각은 응답을 받은 브라우저 시각이었다
 * (시세 캐시 행은 그보다 오래됐을 수 있다).
 */
const src = readFileSync(join(process.cwd(), 'src/views/StockDetailDashboard.vue'), 'utf8')

describe('StockDetailDashboard 자동 갱신 머리말', () => {
  it('재현: 주기 문구와 실제 주기가 한 상수 — 10초라고 하지 않는다', () => {
    expect(src).not.toMatch(/10초 자동 갱신/)
    expect(src).toMatch(/const AUTO_REFRESH_SECONDS = 15/)
    expect(src).toMatch(/AUTO_REFRESH_SECONDS \* 1000/)
  })

  it('재현: 실시간이라고 하지 않고, 시세 시각은 데이터의 asOf 로', () => {
    expect(src).not.toMatch(/'실시간 감시 중'/)
    expect(src).toMatch(/priceInfo\?\.asOf/)
  })
})
