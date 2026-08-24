import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ControlRoomKpis from './ControlRoomKpis.vue'

/**
 * KPI 카드 5종.
 *
 * 회귀로 묶는 핵심: **0 을 조용히 0 으로 두지 않는다.**
 * 2026-08-24 실측에서 "종합판단 후보 0종목"만 뜨고 이유가 없었다(서버 4일 다운 → 입력 노후 →
 * 노후 가드가 채점 거부). 백엔드가 note 를 만들어 보냈는데 카드가 그걸 렌더하지 않은 게 원인이었다.
 */
function kpis(overrides = {}) {
  return {
    candidates: {
      dataAvailable: true,
      total: 3,
      strongBuy: 1,
      buy: 0,
      watch: 2,
      latestSnapshotAt: '2026-08-24T11:30:00',
      snapshotStale: false,
      note: null
    },
    gates: {
      dataAvailable: true,
      open: 3,
      total: 5,
      items: [
        { key: 'kill-switch', label: '킬스위치', state: 'OPEN', detail: '정상' },
        { key: 'nxt-routing', label: 'NXT 주문 라우팅', state: 'CLOSED', detail: 'flag OFF' }
      ]
    },
    lossBreaker: {
      dataAvailable: true,
      realizedPnlKrw: 0,
      limitKrw: 300000,
      enabled: true,
      trippedToday: false,
      mode: 'VIRTUAL',
      note: null
    },
    volRegime: { dataAvailable: true, regime: 'NORMAL', gateMode: 'OFF', note: null },
    undecided: { dataAvailable: true, count: 8, rosterSize: 8 },
    ...overrides
  }
}

describe('ControlRoomKpis — 후보 0건의 사유를 반드시 보여준다', () => {
  it('0건 + 스냅샷 노후 → 사유 문구와 스냅샷 시각을 표시하고 카드를 경고색으로', () => {
    const w = mount(ControlRoomKpis, {
      props: {
        kpis: kpis({
          candidates: {
            dataAvailable: true,
            total: 0,
            strongBuy: 0,
            buy: 0,
            watch: 0,
            latestSnapshotAt: '2026-08-20T11:30:00',
            snapshotStale: true,
            note: '후보 0건 — 추천 스냅샷이 2026-08-20 로 노후. 입력이 노후하면 노후 가드가 채점을 거부하므로 0 으로 보인다(§4c 정상 동작).'
          }
        })
      }
    })

    const card = w.findAll('.kpi')[0]
    expect(card.text()).toContain('노후 가드가 채점을 거부')
    expect(card.text()).toContain('추천 스냅샷 2026-08-20 11:30')
    expect(card.text()).toContain('노후')
    expect(card.classes()).toContain('alert')
  })

  it('스냅샷이 아예 없으면 "추천 스냅샷 없음" 으로 적고 경고한다', () => {
    const w = mount(ControlRoomKpis, {
      props: {
        kpis: kpis({
          candidates: {
            dataAvailable: true,
            total: 0,
            strongBuy: 0,
            buy: 0,
            watch: 0,
            latestSnapshotAt: null,
            snapshotStale: null,
            note: '후보 0건 — 추천 스냅샷이 아예 없다.'
          }
        })
      }
    })

    const card = w.findAll('.kpi')[0]
    expect(card.text()).toContain('추천 스냅샷 없음')
    expect(card.classes()).toContain('alert')
  })

  it('후보가 정상적으로 있으면 경고하지 않고 사유 문구도 없다', () => {
    const w = mount(ControlRoomKpis, { props: { kpis: kpis() } })

    const card = w.findAll('.kpi')[0]
    expect(card.text()).toContain('3')
    expect(card.classes()).not.toContain('alert')
    expect(card.find('.note').exists()).toBe(false)
    expect(card.text()).toContain('추천 스냅샷 2026-08-24 11:30')
  })

  it('보드 조회 실패는 0 이 아니라 "데이터 없음" 으로 렌더된다', () => {
    const w = mount(ControlRoomKpis, {
      props: {
        kpis: kpis({
          candidates: {
            dataAvailable: false,
            total: 0,
            strongBuy: 0,
            buy: 0,
            watch: 0,
            latestSnapshotAt: null,
            snapshotStale: null,
            note: '보드 조회 실패'
          }
        })
      }
    })

    const card = w.findAll('.kpi')[0]
    expect(card.text()).toContain('데이터 없음')
    expect(card.text()).toContain('보드 조회 실패')
  })
})

describe('ControlRoomKpis — 나머지 카드', () => {
  it('게이트는 열림/전체와 막힌 게이트 이름을 보여준다', () => {
    const w = mount(ControlRoomKpis, { props: { kpis: kpis() } })
    const card = w.findAll('.kpi')[1]

    expect(card.text()).toContain('3')
    expect(card.text()).toContain('NXT 주문 라우팅 CLOSED')
    expect(card.classes()).toContain('alert')   // CLOSED 가 있으면 경고
  })

  it('일일손실 서킷은 원 단위이고 여유를 계산해 보여준다', () => {
    const w = mount(ControlRoomKpis, { props: { kpis: kpis() } })
    const card = w.findAll('.kpi')[2]

    expect(card.text()).toContain('0원')
    expect(card.text()).toContain('한도 -300,000원')
    expect(card.text()).toContain('여유 300,000원')
    expect(card.text()).not.toContain('%')   // % 는 별개의 자산 킬스위치다
  })

  it('실현손익 조회 실패는 0원이 아니라 "조회 실패"다', () => {
    const w = mount(ControlRoomKpis, {
      props: {
        kpis: kpis({
          lossBreaker: {
            dataAvailable: true,
            realizedPnlKrw: null,
            limitKrw: 300000,
            enabled: true,
            trippedToday: false,
            mode: 'VIRTUAL',
            note: '당일 실현손익 조회 실패'
          }
        })
      }
    })

    const card = w.findAll('.kpi')[2]
    // 값 영역만 본다 — 카드 전체 텍스트엔 "한도 -300,000원" 이 있어 부분문자열 단언이 무의미하다.
    expect(card.find('.v').text()).toBe('조회 실패')
    expect(card.find('.v').text()).not.toContain('0원')
  })

  it('VKOSPI 미수집은 NORMAL 로 위장하지 않는다', () => {
    const w = mount(ControlRoomKpis, {
      props: {
        kpis: kpis({
          volRegime: { dataAvailable: false, regime: null, gateMode: null, note: 'VKOSPI 미수집' }
        })
      }
    })

    const card = w.findAll('.kpi')[3]
    expect(card.text()).toContain('데이터 없음')
    expect(card.text()).not.toContain('NORMAL')
  })
})
