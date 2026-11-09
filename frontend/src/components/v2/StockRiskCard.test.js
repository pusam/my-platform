import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const quickCheck = vi.fn()
const checkRisk = vi.fn()
vi.mock('../../utils/api', () => ({ riskAPI: { quickCheck: (...a) => quickCheck(...a), checkRisk: (...a) => checkRisk(...a) } }))

import StockRiskCard from './StockRiskCard.vue'

/**
 * 리스크 카드 — 공시를 확인하지 못했으면 '안전'이 아니라 '확인 불가'(2026-10-03 화면 점검).
 * 예전엔 백엔드가 미확인을 false 로 내려 카드가 '🟢 리스크 안전 · 위험 공시 없음'이었고, 요청에 종목코드도 없었다.
 */
describe('StockRiskCard', () => {
  beforeEach(() => { quickCheck.mockReset(); checkRisk.mockReset() })

  it('재현: 빠른 체크가 확인 불가(checked=false)면 ⚪ 확인 불가 — 🟢 안전이 아니다', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: false, hasDangerousDisclosure: null } })
    const w = mount(StockRiskCard, { props: { stockName: '에코프로', stockCode: '086520' } })
    await flushPromises()

    expect(quickCheck).toHaveBeenCalledWith('에코프로', '086520')
    expect(w.text()).toContain('공시 조회 실패')
    expect(w.text()).not.toContain('리스크 안전')
  })

  it('확인했고 위험 공시가 없으면 종전대로 안전', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: true, hasDangerousDisclosure: false } })
    const w = mount(StockRiskCard, { props: { stockName: '삼성전자', stockCode: '005930' } })
    await flushPromises()

    expect(w.text()).toContain('리스크 안전')
  })

  it('상세 분석에서 공시를 확인하지 못했으면(disclosuresChecked=false) 확인 불가로 그린다', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: true, hasDangerousDisclosure: false } })
    checkRisk.mockResolvedValue({ data: { success: true, data: { status: 'SAFE', riskScore: 0, disclosuresChecked: false } } })
    const w = mount(StockRiskCard, { props: { stockName: '삼성전자', stockCode: '005930' } })
    await flushPromises()
    await w.find('.deep-btn').trigger('click')
    await flushPromises()

    expect(checkRisk).toHaveBeenCalledWith('삼성전자', '005930')
    expect(w.text()).toContain('공시 위험 확인 불가')
    expect(w.text()).not.toContain('리스크 안전')
  })
})

// 2026-10-19: 공시 항목의 위험 여부는 JSON 에서 dangerous 다(백엔드 원시 boolean isDangerous — BooleanJsonKeyContractTest).
// 화면이 isDangerous 로 읽어 걸러지지 않았다(지금은 백엔드가 위험한 것만 보내 드러나지 않았다).
describe('StockRiskCard — 위험 공시 목록(실제 응답 이름)', () => {
  it('재현: dangerous=false 항목은 위험 공시로 세지 않는다', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: true, hasDangerousDisclosure: true } })
    checkRisk.mockResolvedValue({ data: { success: true, data: { status: 'DANGER', riskScore: 80, disclosuresChecked: true,
      dangerousDisclosures: [
        { reportNm: '유상증자결정', rceptDt: '20261016', dangerous: true, matchedKeyword: '유상증자' },
        { reportNm: '단일판매ㆍ공급계약체결', rceptDt: '20261015', dangerous: false }
      ] } } })
    const w = mount(StockRiskCard, { props: { stockName: 'SK하이닉스', stockCode: '000660' } })
    await flushPromises()
    await w.find('.deep-btn').trigger('click')
    await flushPromises()

    expect(w.text()).toContain('위험 공시 (1건)')
    expect(w.text()).not.toContain('단일판매')
  })
})

// 2026-11-09 재현(운영 알테오젠 196170, −16.3% 날): 화면 위 요약 줄 '리스크 DANGER'·게이지 '위험 11/100 · 🚨 매수 주의' 인데
// 리스크 카드만 '🟡 주의 필요 · 위험 공시 발견 — 상세 분석 권장' 이었다. 카드는 버튼을 누르기 전엔 빠른 공시 체크만 보고,
// 화면이 이미 받은 같은 종합 리스크 분석(riskService.analyzeRisk)의 결과를 쓰지 않았다. 그 결과를 받아 더 낮게 말하지 않는다.
describe('StockRiskCard — 화면이 받은 종합 리스크 분석', () => {
  beforeEach(() => { quickCheck.mockReset(); checkRisk.mockReset() })

  it('재현(11/9): 화면 분석이 DANGER 면 빠른 체크가 위험 공시만 찾았어도 카드는 🔴 — 🟡 주의 필요가 아니다', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: true, hasDangerousDisclosure: true } })
    const w = mount(StockRiskCard, { props: { stockName: '알테오젠', stockCode: '196170',
      pageRisk: { riskStatus: 'DANGER', riskScore: 72 } } })
    await flushPromises()

    expect(w.find('.risk-card').classes()).toContain('status-danger')
    expect(w.find('.risk-label').text()).toBe('매수 금지 권장')
    expect(w.find('.risk-sublabel').text()).toBe('위험 공시 발견 · 리스크 점수 72/100 — 근거는 상세 분석')
    expect(checkRisk).not.toHaveBeenCalled()   // 버튼을 누르기 전엔 상세 분석을 부르지 않는다(종전대로)
  })

  it('화면 분석이 WARNING 이면 빠른 체크가 위험 공시 없음이어도 🟡', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: true, hasDangerousDisclosure: false } })
    const w = mount(StockRiskCard, { props: { stockName: '삼성전자', stockCode: '005930',
      pageRisk: { riskStatus: 'WARNING', riskScore: 45 } } })
    await flushPromises()

    expect(w.find('.risk-label').text()).toBe('주의 필요')
    expect(w.find('.risk-sublabel').text()).toBe('리스크 점수 45/100 — 근거는 상세 분석')
  })

  it('화면 분석이 SAFE 여도 빠른 체크가 확인 불가면 ⚪ — 안전으로 칠하지 않는다(종전 규칙)', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: false, hasDangerousDisclosure: null } })
    const w = mount(StockRiskCard, { props: { stockName: '에코프로', stockCode: '086520',
      pageRisk: { riskStatus: 'SAFE', riskScore: 5 } } })
    await flushPromises()

    expect(w.text()).not.toContain('리스크 안전')
    expect(w.text()).toContain('공시 조회 실패')
  })

  it('상세 분석을 받으면 그 결과가 우선 — 화면 분석보다', async () => {
    quickCheck.mockResolvedValue({ data: { success: true, checked: true, hasDangerousDisclosure: true } })
    checkRisk.mockResolvedValue({ data: { success: true, data: { status: 'WARNING', riskScore: 40, disclosuresChecked: true } } })
    const w = mount(StockRiskCard, { props: { stockName: '알테오젠', stockCode: '196170',
      pageRisk: { riskStatus: 'DANGER', riskScore: 72 } } })
    await flushPromises()
    await w.find('.deep-btn').trigger('click')
    await flushPromises()

    expect(w.find('.risk-label').text()).toBe('주의 필요')
  })
})
