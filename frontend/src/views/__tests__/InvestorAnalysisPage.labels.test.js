import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 매매 동향 탭 이름표 — 실제 행 수와 정의대로(2026-10-07 화면 점검).
 * 재현: 버튼이 '📈 매수 TOP 50 / 📉 매도 TOP 50'인데 표는 30줄에서 끝났다 — 순위 출처(KIS 국내기관_외국인 매매종목가집계)가
 * 하루 30종목까지만 주고(운영 10/1·10/2·10/6 전부 외국인·기관 30행, 연기금 17~26행), 내용도 '매수'가 아니라 순매수·순매도 순위다.
 */
const page = readFileSync(join(process.cwd(), 'src/views/InvestorAnalysisPage.vue'), 'utf8')

describe('InvestorAnalysisPage 매매 동향 이름표', () => {
  it('재현: 없는 50 을 말하지 않는다', () => {
    expect(page).not.toMatch(/TOP 50/)
  })

  it('순매수·순매도 순위라고 말하고, 하루 최대 30종목임을 밝힌다', () => {
    expect(page).toMatch(/📈 순매수 상위/)
    expect(page).toMatch(/📉 순매도 상위/)
    expect(page).toMatch(/하루 최대 30종목/)
  })
})
