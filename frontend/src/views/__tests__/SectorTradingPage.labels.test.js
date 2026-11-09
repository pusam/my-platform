import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

// 2026-11-10 재현: 시장 탭 거래대금 지도 안내가 '데이터는 5분마다 자동 갱신됩니다'였다 — 서버 수집은 장중 3분마다
// (SectorTradingService cron 0 */3 9-15), 화면은 1분마다(useAutoRefresh 60초) 다시 읽는다. 둘 다 5분이 아니었다.
describe('SectorTradingPage — 갱신 주기 안내', () => {
  const src = readFileSync(join(process.cwd(), 'src/views/SectorTradingPage.vue'), 'utf8')

  it('재현: "5분마다 자동 갱신"이라 하지 않는다 — 수집 3분·화면 1분', () => {
    expect(src).not.toContain('5분마다 자동 갱신')
    expect(src).toContain('장중 3분마다')
    expect(src).toContain('1분마다 다시 읽습니다')
  })

  it('화면 갱신 주기는 안내와 같은 60초다', () => {
    expect(src).toMatch(/interval:\s*60\s*\*\s*1000/)
  })
})
