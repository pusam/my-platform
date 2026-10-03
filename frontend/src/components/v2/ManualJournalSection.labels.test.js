import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 내 매수 기록 '3일 적중' — 정의를 말한다(2026-10-03).
 * 재현: 평가 배치(평일 19:40)가 그 시각의 시세(NXT 시간대 가격일 수 있음, 배치가 밀리면 더 늦은 날)를 '3일 뒤 가격'으로
 * 저장하는데(시그널의 옛 3일 평가와 같은 정의 — V59 교정 D+3 종가가 아니다) 화면은 그냥 '3일 적중률'이라 했다.
 */
const src = readFileSync(join(process.cwd(), 'src/components/v2/ManualJournalSection.vue'), 'utf8')

describe('ManualJournalSection 적중 이름표', () => {
  it('재현: 평가 시점 시세 기준이라고 밝힌다 — D+3 종가 기준이 아니다', () => {
    expect(src).not.toMatch(/<span class="stat-label">3일 적중률<\/span>/)
    expect(src).toMatch(/평가 시점 시세/)
  })
})
