import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { investorFlowNote } from './investorFlowNote'

/**
 * 종목 상세 '투자자별 수급(당일)' — 2026-10-07 화면 점검.
 * 재현: 장중(09:50) 삼성SDI 상세에서 배지는 '실시간'인데 외국인·기관·프로그램이 전부 '-'였다. 외국인·기관 당일 집계는 KIS 가
 * 장 마감(15:40) 뒤에만 주므로 장중엔 비는 게 정상인데, 아무 설명이 없어 고장처럼 보였다(프로그램은 같은 날 KIS 경로를 고쳐 장중 실시간).
 */
describe('investorFlowNote', () => {
  it('재현: 장중에 외국인·기관이 비면 장 마감 뒤 제공이라고 말한다', () => {
    const note = investorFlowNote({ dataSource: '실시간', foreignNetBuy: null, instNetBuy: null, programNetBuy: 12.3 })
    expect(note).toContain('장 마감')
    expect(note).toContain('외국인·기관')
  })

  it('재현: 수능일(2026-11-19)은 서버가 준 그날 마감(16:40)을 말한다 — 15:40 이 아니다(2026-11-10)', () => {
    const note = investorFlowNote({ dataSource: '실시간', foreignNetBuy: null, instNetBuy: null, investorDataExpected: false, investorDataReadyAt: '16:40' })
    expect(note).toContain('16:40')
    expect(note).not.toContain('15:40')
    expect(investorFlowNote({ dataSource: '실시간', foreignNetBuy: null, instNetBuy: null, investorDataReadyAt: '15:40' })).toContain('장 마감(15:40)')
  })

  it('장 시작 전이면 아직 없다고 말한다', () => {
    expect(investorFlowNote({ dataSource: '장전(초기화)', foreignNetBuy: null, instNetBuy: null })).toContain('장 시작 전')
  })

  it('값이 있으면 아무 말도 하지 않는다', () => {
    expect(investorFlowNote({ dataSource: '일별(DB)', foreignNetBuy: 28.27, instNetBuy: -7.27 })).toBe('')
    expect(investorFlowNote({ dataSource: '실시간', foreignNetBuy: 0, instNetBuy: null })).toBe('')
  })

  it('장후인데 비면 이유를 지어내지 않는다 — 기존 "-" 그대로', () => {
    expect(investorFlowNote({ dataSource: '일별(DB)', foreignNetBuy: null, instNetBuy: null })).toBe('')
    expect(investorFlowNote(null)).toBe('')
  })

  it('패널이 이 안내를 보인다', () => {
    const src = readFileSync(join(process.cwd(), 'src/views/StockDetailDashboard.vue'), 'utf8')
    expect(src).toMatch(/investorFlowNote\(supplyDemand\.value\)/)
    expect(src).toMatch(/class="supply-note"/)
  })
})

// 2026-10-19: 백엔드가 요일·달력과 무관하게 08~20시를 '실시간'이라 해, 휴장일에도·장 마감 뒤 조회 실패에도 "15:40 뒤 제공"이라 했다.
// 이제 장이 닫힌 날은 출처가 '휴장'이고, 거래일 15:40 이후면 investorDataExpected=true 가 온다(StockDetailClosedDayTest).
describe('investorFlowNote — 휴장일·장 마감 뒤', () => {
  it('재현: 휴장일이면 오늘은 장이 열리지 않았다고 말한다', () => {
    const note = investorFlowNote({ dataSource: '휴장', foreignNetBuy: null, instNetBuy: null })
    expect(note).toContain('장이 열리지 않았')
    expect(note).not.toContain('15:40')
  })

  it('재현: 거래일 15:40 뒤인데 비었으면 "마감 뒤 제공"이라 하지 않는다 — 이유를 지어내지 않는다', () => {
    expect(investorFlowNote({ dataSource: '실시간', investorDataExpected: true, foreignNetBuy: null, instNetBuy: null })).toBe('')
  })

  it('장중(15:40 전)은 종전대로 마감 뒤 제공이라고 말한다', () => {
    expect(investorFlowNote({ dataSource: '실시간', investorDataExpected: false, foreignNetBuy: null, instNetBuy: null }))
      .toContain('장 마감')
  })
})
