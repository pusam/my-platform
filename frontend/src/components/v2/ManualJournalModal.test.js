import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ManualJournalModal from './ManualJournalModal.vue'
import apiClient, { manualJournalAPI } from '../../utils/api'

vi.mock('../../utils/api', () => ({
  default: { get: vi.fn() },
  manualJournalAPI: { recordBuy: vi.fn(), sectorExposure: vi.fn() }
}))

const exposureResp = (mapped, sectors = []) =>
  ({ data: { success: true, data: { mapped, sectors } } })

async function mountModal(props = {}) {
  const w = mount(ManualJournalModal, { props: { stockCode: '005930', ...props } })
  await flushPromises()
  return w
}

describe('ManualJournalModal — 📔 수동 매수 기록 폼', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiClient.get.mockResolvedValue({ data: { data: { currentPrice: 65000, stockName: '삼성전자' } } })
    manualJournalAPI.sectorExposure.mockResolvedValue(exposureResp(false))
  })

  it('현재가 프리필(/stock/{code}) + 종목명 해석', async () => {
    const w = await mountModal()
    expect(apiClient.get).toHaveBeenCalledWith('/stock/005930')
    expect(w.find('input[type="number"]').element.value).toBe('65000')
    expect(w.find('.stock-line').text()).toContain('삼성전자')
  })

  it('부모가 currentPrice 넘기면 그 값 프리필', async () => {
    const w = await mountModal({ currentPrice: 70000, stockName: '삼성전자' })
    expect(w.find('input[type="number"]').element.value).toBe('70000')
    expect(apiClient.get).not.toHaveBeenCalled()
  })

  it('섹터 집중 경고 — 동일 섹터 보유 있으면 표시(경고만), 매핑 밖(mapped:false)이면 미표시', async () => {
    manualJournalAPI.sectorExposure.mockResolvedValue(exposureResp(true, [
      { sectorCode: 'SEMI', sectorName: '반도체', count: 2,
        holdings: [
          { stockCode: '000660', stockName: 'SK하이닉스', source: 'JOURNAL' },
          { stockCode: '042700', stockName: '한미반도체', source: 'BOT' }
        ] }
    ]))
    const w = await mountModal()
    const warn = w.find('.sector-warn')
    expect(warn.exists()).toBe(true)
    expect(warn.text()).toContain('반도체')
    expect(warn.text()).toContain('2종목')
    expect(warn.text()).toContain('한미반도체(봇)')

    manualJournalAPI.sectorExposure.mockResolvedValue(exposureResp(false))
    const w2 = await mountModal({ stockCode: '999999' })
    expect(w2.find('.sector-warn').exists()).toBe(false)
  })

  it('저장 — recordBuy 호출 + saved/close emit', async () => {
    manualJournalAPI.recordBuy.mockResolvedValue({ data: { success: true, data: { id: 1 } } })
    const w = await mountModal()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(manualJournalAPI.recordBuy).toHaveBeenCalledWith({
      stockCode: '005930', stockName: '삼성전자',
      buyPrice: 65000, quantity: null, memo: null
    })
    expect(w.emitted('saved')).toBeTruthy()
    expect(w.emitted('close')).toBeTruthy()
  })

  it('저장 실패 — 에러 메시지 표시, close 안 함', async () => {
    manualJournalAPI.recordBuy.mockResolvedValue({ data: { success: false, message: '실패' } })
    const w = await mountModal()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(w.find('.error-line').text()).toContain('실패')
    expect(w.emitted('close')).toBeFalsy()
  })
})
