import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 매수 체크리스트 — 공매도는 2026-10-02 부터 거래 비중 '참고' 항목이다(판정 안 함, 사용자 결정 "표시만").
 * 참고 항목은 ✅/❌/➖ 가 아니라 ℹ️ 로 보이고, "기준 …" 대신 "참고(판정 안 함)" 로 읽힌다 — 통과·미충족처럼 보이면
 * 판정에 쓰는 것으로 오해한다.
 */
vi.mock('../../utils/api', () => ({ default: { get: vi.fn() } }))

import apiClient from '../../utils/api'
import BuyChecklistModal from './BuyChecklistModal.vue'

const CHECKLIST = {
  stockCode: '005930', stockName: '삼성전자', passedCount: 3, totalCount: 4,
  recommendation: 'STRONG', summary: '3/4 충족 (판정 불가 1개 제외)',
  items: [
    { key: 'tradable', label: '거래 가능 상태', passed: false, dataMissing: true, informational: false,
      value: '확인 전', threshold: '정상 거래', note: '', dimension: 'META' },
    { key: 'shortSelling', label: '공매도 거래 비중', passed: false, dataMissing: false, informational: true,
      value: '8.00%', threshold: '참고(판정 안 함)', asOf: '2026-10-01 기준',
      note: '그날 거래량 중 공매도 몫(KIS) — 잔고가 아니다.', dimension: 'SHORT' },
    { key: 'consecutiveBuy', label: '외국인/기관 연속매수', passed: true, dataMissing: false, informational: false,
      value: '외국인', threshold: '≥ 3일', note: '', dimension: 'SHORT' }
  ]
}

describe('BuyChecklistModal — 참고 항목', () => {
  beforeEach(() => {
    apiClient.get.mockResolvedValue({ data: { success: true, data: CHECKLIST } })
  })

  const mountModal = async () => {
    const w = mount(BuyChecklistModal, {
      props: { stockCode: '005930' },
      global: { stubs: { ManualJournalModal: true } }
    })
    await flushPromises()
    return w
  }

  it('참고 항목은 ℹ️ — ✅·❌·➖ 가 아니다', async () => {
    const w = await mountModal()
    const items = w.findAll('li.item')
    expect(items[1].find('.check-icon').text()).toBe('ℹ️')
    expect(items[1].classes()).toContain('info')
    expect(items[0].find('.check-icon').text()).toBe('➖')
    expect(items[2].find('.check-icon').text()).toBe('✅')
  })

  it('참고 항목은 "기준 …" 이 아니라 그대로 "참고(판정 안 함)" 로 읽힌다', async () => {
    const w = await mountModal()
    const threshold = w.findAll('li.item')[1].find('.item-threshold').text()
    expect(threshold).toBe('참고(판정 안 함)')
    expect(w.findAll('li.item')[2].find('.item-threshold').text()).toBe('기준 ≥ 3일')
  })

  it('하단 안내가 참고 항목은 판정에 쓰지 않는다고 밝힌다', async () => {
    const w = await mountModal()
    expect(w.find('.modal-footer .hint').text()).toContain('참고')
  })
})
