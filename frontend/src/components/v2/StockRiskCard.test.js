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
