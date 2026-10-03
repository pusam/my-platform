import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 매매 동향 화면은 수집을 부르지 않는다(2026-10-03) — 예전엔 조회가 비거나 실패하면 POST /investor/collect 를 자동으로 불러
 * 장중 잠정치·휴장일 유령 행이 화면을 연 것만으로 일별 기록(종합추천 수급 입력)이 될 수 있었다.
 */
const src = readFileSync(join(process.cwd(), 'src', 'views/InvestorAnalysisPage.vue'), 'utf8')

describe('InvestorAnalysisPage — 조회 화면은 쓰지 않는다', () => {
  it('재현: investorAPI.collect() 호출이 없다', () => {
    expect(src).not.toMatch(/investorAPI\.collect\(/)
  })

  it('조회 실패는 "데이터가 없습니다"와 다른 문구', () => {
    expect(src).toMatch(/v-if="tradesError"/)
    expect(src).toMatch(/조회 실패/)
  })
})
