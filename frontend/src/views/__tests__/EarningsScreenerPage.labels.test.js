import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 실적 스크리너 수집 일정 이름표 — 실제 크론과 같게(2026-10-07 화면 점검).
 * 재현: 화면은 '자동 수집: 매일 08:30, 15:40'인데 실제는 평일(휴장일 제외) 08:30·15:38(FinancialDataScheduler).
 */
const page = readFileSync(join(process.cwd(), 'src/views/EarningsScreenerPage.vue'), 'utf8')
const scheduler = readFileSync(join(process.cwd(), '../backend/src/main/java/com/myplatform/backend/scheduler/FinancialDataScheduler.java'), 'utf8')

describe('EarningsScreenerPage 수집 일정', () => {
  it('재현: 매일·15:40 이 아니다', () => {
    expect(page).not.toMatch(/매일 08:30, 15:40/)
  })

  it('거래일 08:30·15:38 — 크론과 같은 시각', () => {
    expect(scheduler).toMatch(/cron = "0 30 8 \* \* MON-FRI"/)
    expect(scheduler).toMatch(/cron = "0 38 15 \* \* MON-FRI"/)
    expect(page).toMatch(/거래일 08:30·15:38/)
  })
})

// 2026-10-07: '흑자전환' 카드가 순이익(이미 흑자)만 보였다 — 판정은 영업이익 기준이다
describe('EarningsScreenerPage 턴어라운드 카드', () => {
  it('재현: 숫자가 순이익임을 밝히고 판정 근거를 보인다', () => {
    expect(page).toMatch(/순이익 이전 \(/)
    expect(page).toMatch(/순이익 현재 \(/)
    expect(page).toMatch(/stock\.judgeSummary/)
  })
  it('적자 기준 변화율을 %로 보이지 않는다', () => {
    expect(page).toMatch(/netIncomeChangeLabel\(stock\.previousNetIncome, stock\.netIncomeChangeRate\)/)
    expect(page).not.toMatch(/\+\{\{ formatPercent\(stock\.netIncomeChangeRate\) \}\}/)
  })
})
