import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import RecentDisclosuresSection from './RecentDisclosuresSection.vue'
import apiClient from '../../utils/api'

vi.mock('../../utils/api', () => ({ default: { get: vi.fn() } }))

function resp(data) {
  return { data: { success: true, data } }
}

const fullList = {
  stockCode: '005930',
  dataAvailable: true,
  totalCount: 2,
  items: [
    { reportNm: '단일판매ㆍ공급계약체결', rceptDt: '2026-08-19', flrNm: '삼성전자', viewerUrl: 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260819000002', dangerous: false, matchedKeyword: null },
    { reportNm: '상장폐지 사유 발생', rceptDt: '2026-08-10', flrNm: '삼성전자', viewerUrl: null, dangerous: true, matchedKeyword: '상장폐지' }
  ]
}

async function mountSection(data, props = {}) {
  apiClient.get.mockResolvedValue(resp(data))
  const w = mount(RecentDisclosuresSection, { props: { stockCode: '005930', ...props } })
  await flushPromises()
  return w
}

describe('RecentDisclosuresSection — 📄 최근 공시 (DART 3개월, 표시 전용)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('제목에 건수 + 위험 수 병기, 목록에 DART 원문 링크 렌더', async () => {
    const w = await mountSection(fullList)
    expect(w.find('.ds-title').text()).toContain('3개월 2건')
    expect(w.find('.ds-title').text()).toContain('⚠ 위험 1')

    const rows = w.findAll('.rd-row')
    expect(rows).toHaveLength(2)
    const link = rows[0].find('a.rd-title')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260819000002')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('위험 공시 행 — 붉은 강조 + 매칭 키워드 배지, viewerUrl null 이면 링크 대신 텍스트(§4c)', async () => {
    const w = await mountSection(fullList)
    const dangerRow = w.findAll('.rd-row')[1]
    expect(dangerRow.classes()).toContain('rd-danger')
    expect(dangerRow.find('.rd-danger-badge').text()).toContain('상장폐지')
    expect(dangerRow.find('a.rd-title').exists()).toBe(false)   // 링크 미확보 → 텍스트만
  })

  it('dataAvailable=false → "확인 불가" 명시 (공시 없음으로 위장 금지, §4c)', async () => {
    const w = await mountSection({ stockCode: '005930', dataAvailable: false, totalCount: 0, items: [] })
    expect(w.find('.rd-unavailable').exists()).toBe(true)
    expect(w.find('.rd-unavailable').text()).toContain('확인 불가')
    expect(w.find('.rd-empty').exists()).toBe(false)
  })

  it('조회 성공 + 0건 → "최근 3개월 공시 없음" (확인 불가와 구분)', async () => {
    const w = await mountSection({ stockCode: '005930', dataAvailable: true, totalCount: 0, items: [] })
    expect(w.find('.rd-empty').text()).toContain('공시 없음')
    expect(w.find('.rd-unavailable').exists()).toBe(false)
  })

  it('상한 컷 시 "외 N건" 표기 (조용한 절단 금지)', async () => {
    const w = await mountSection({ ...fullList, totalCount: 20 })
    expect(w.find('.rd-more').text()).toContain('외 18건')
  })

  it('HTTP 실패 시 섹션 미렌더 + 초기 조회는 stockName 없이(코드만)', async () => {
    apiClient.get.mockRejectedValue(new Error('down'))
    const w = mount(RecentDisclosuresSection, { props: { stockCode: '005930', stockName: '삼성전자' } })
    await flushPromises()
    expect(w.find('.detail-section').exists()).toBe(false)
    // 종목 전환 직후 부모 stockName 은 이전 종목 값일 수 있어(C-1) 코드 변경 시엔 이름을 안 넘긴다
    expect(apiClient.get).toHaveBeenCalledWith('/stock/005930/disclosures', { params: {} })
  })

  it('C-1 가드 — 코드 전환 시 이전 종목 이름을 넘기지 않고, 새 이름 도착 시 "확인 불가"만 재조회', async () => {
    // 종목 A: 정상 조회
    apiClient.get.mockResolvedValueOnce(resp(fullList))
    const w = mount(RecentDisclosuresSection, { props: { stockCode: '005930', stockName: '삼성전자' } })
    await flushPromises()

    // 종목 B 로 전환 — 부모의 stockName 은 아직 '삼성전자'(이전 종목). 코드 매핑도 실패해 확인 불가.
    apiClient.get.mockResolvedValueOnce(resp({ stockCode: '999999', dataAvailable: false, totalCount: 0, items: [] }))
    await w.setProps({ stockCode: '999999' })   // stockName 은 아직 옛값
    await flushPromises()
    // 전환 조회에 이전 종목 이름이 섞이지 않아야 한다(남의 회사 공시 방지)
    expect(apiClient.get).toHaveBeenLastCalledWith('/stock/999999/disclosures', { params: {} })
    expect(w.find('.rd-unavailable').exists()).toBe(true)

    // 새 종목 이름이 도착 → 확인 불가였으므로 이름 폴백으로 재조회
    apiClient.get.mockResolvedValueOnce(resp({ ...fullList, stockCode: '999999' }))
    await w.setProps({ stockName: '신규상장사' })
    await flushPromises()
    expect(apiClient.get).toHaveBeenLastCalledWith('/stock/999999/disclosures', { params: { stockName: '신규상장사' } })
    expect(w.findAll('.rd-row')).toHaveLength(2)
  })

  it('C-1 가드 — 정상 조회된 상태면 이름 도착에도 재조회하지 않는다(불필요 호출 방지)', async () => {
    apiClient.get.mockResolvedValue(resp(fullList))
    const w = mount(RecentDisclosuresSection, { props: { stockCode: '005930', stockName: null } })
    await flushPromises()
    const calls = apiClient.get.mock.calls.length
    await w.setProps({ stockName: '삼성전자' })
    await flushPromises()
    expect(apiClient.get.mock.calls.length).toBe(calls)   // dataAvailable=true 라 재조회 없음
  })
})
