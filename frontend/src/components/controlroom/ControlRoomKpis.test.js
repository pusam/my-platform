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
      asOf: '11:30 기준',
      realtime: true,
      note: null,
      noteDetail: null
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
    trustGate: trustGate(),
    ...overrides
  }
}

/** ⑦ 믿고 사도 되나 — 기본은 운영 실측(2026-09-16)에 가까운 '표본 수집 중'. */
function trustGate(overrides = {}) {
  return {
    dataAvailable: true,
    state: 'COLLECTING',
    rows: 20,
    distinctDays: 4,
    controlRows: 8,
    costAdjustedReturn: -2.03,
    edgeVsControl: -2.64,
    edgeMarginOfError: 1.9,
    edgeExceedsUncertainty: false,
    avgWin: 5.12,
    avgLoss: -6.5,
    worst: -12.41,
    avgMaePct: -5.42,
    excludedDays: 0,
    blockers: ['시그널 표본 20/30건', '대조군 표본 8/30건', '고유 거래일 4/10일'],
    note: '표본 수집 중 — 시그널 표본 20/30건, 대조군 표본 8/30건, 고유 거래일 4/10일',
    noteDetail: '유효 표본은 행 수가 아니라 고유 거래일 수다.',
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
            note: '입력 노후 — 가드가 채점 거부',
            noteDetail: '후보 0건 — 추천 스냅샷이 2026-08-20 로 노후. 입력이 노후하면 노후 가드가 채점을 거부하므로 0 으로 보인다(§4c 정상 동작).'
          }
        })
      }
    })

    const card = w.findAll('.kpi')[0]
    // 표면은 한 줄, 전체 근거는 툴팁(title) — 카드가 글자 벽이 되지 않게
    expect(card.find('.note').text()).toBe('입력 노후 — 가드가 채점 거부')
    expect(card.find('.note').attributes('title')).toContain('노후 가드가 채점을 거부')
    expect(card.find('.basis').text()).toContain('08-20 11:30')
    expect(card.find('.basis').text()).toContain('노후')
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
            note: '스냅샷 없음 — 미계산과 구분 불가',
            noteDetail: '후보 0건 — 추천 스냅샷이 아예 없다.'
          }
        })
      }
    })

    const card = w.findAll('.kpi')[0]
    expect(card.text()).toContain('스냅샷 없음')
    expect(card.classes()).toContain('alert')
  })

  it('후보가 정상적으로 있으면 경고하지 않고 사유 문구도 없다', () => {
    const w = mount(ControlRoomKpis, { props: { kpis: kpis() } })

    const card = w.findAll('.kpi')[0]
    expect(card.text()).toContain('3')
    expect(card.classes()).not.toContain('alert')
    expect(card.find('.note').exists()).toBe(false)
    // basis 는 이제 스냅샷 시각이 아니라 **숫자의 출처**를 밝힌다(실시간 vs 폴백)
    expect(card.find('.basis').text()).toContain('11:30 기준')
    expect(card.find('.basis').text()).toContain('실시간')
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
            note: '보드 조회 실패',
            noteDetail: '보드 조회가 예외로 실패했다'
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
    expect(card.text()).toContain('NXT 주문 라우팅')
    expect(card.classes()).toContain('alert')   // CLOSED 가 있으면 경고
  })

  it('일일손실 서킷은 원 단위이고 여유를 계산해 보여준다', () => {
    const w = mount(ControlRoomKpis, { props: { kpis: kpis() } })
    const card = w.findAll('.kpi')[2]

    expect(card.find('.v').text()).toBe('0원')
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

describe('ControlRoomKpis — 어제 스냅샷 폴백을 실시간으로 위장하지 않는다', () => {
  it('realtime=false 면 "실시간 아님"을 표시하고 경고색을 준다', () => {
    const w = mount(ControlRoomKpis, {
      props: {
        kpis: kpis({
          candidates: {
            dataAvailable: true,
            total: 1,
            strongBuy: 0,
            buy: 1,
            watch: 0,
            latestSnapshotAt: '2026-08-25T17:00:00',
            snapshotStale: false,
            asOf: '08-25 17:00 스냅샷',
            realtime: false,
            note: null,
            noteDetail: null
          }
        })
      }
    })

    const basis = w.findAll('.kpi')[0].find('.basis')
    expect(basis.text()).toContain('실시간 아님')
    expect(basis.classes()).toContain('stale')
    expect(basis.attributes('title')).toContain('오늘 계산 결과가 아니다')
  })

  it('realtime=true 면 실시간으로 표기한다', () => {
    const w = mount(ControlRoomKpis, { props: { kpis: kpis() } })
    const basis = w.findAll('.kpi')[0].find('.basis')

    expect(basis.text()).toContain('실시간')
    expect(basis.text()).not.toContain('실시간 아님')
    expect(basis.classes()).not.toContain('stale')
  })
})

describe('ControlRoomKpis ⑦ — "믿고 사도 되나" 게이트', () => {
  const trustCard = (over) =>
    mount(ControlRoomKpis, { props: { kpis: kpis({ trustGate: trustGate(over) }) } }).find('.kpi.trust')

  it('표본 수집 중이면 상태·표본·비용차감 수익을 함께 보여준다 — 고유 거래일을 강조', () => {
    const c = trustCard()
    expect(c.find('.trust-state').text()).toBe('표본 수집 중')
    expect(c.text()).toContain('고유 4일')
    expect(c.text()).toContain('비용차감 -2.03%')
    // 통과가 아니므로 ok 가 아니다. 그렇다고 경고(alert)도 아니다 — 고장이 아니기 때문.
    expect(c.classes()).not.toContain('ok')
    expect(c.classes()).not.toContain('alert')
    expect(c.classes()).toContain('collecting')
  })

  it('수익 수치의 출처를 밝힌다 — V59 교정 평가 기준(2026-09-21 전환), 실매수 승인 아님', () => {
    const basis = trustCard().find('.basis').text()
    expect(basis).toContain('교정 평가')
    expect(basis).toContain('실매수 승인 아님')
    expect(basis).not.toContain('교정 중')   // 전환 전 문구 — 되돌아오면 안 된다
  })

  it('적중률만으로 판단하지 않는다 — 이익·손실·최악·낙폭이 전부 보인다', () => {
    const t = trustCard().text()
    expect(t).toContain('이익 +5.12%')
    expect(t).toContain('손실 -6.50%')
    expect(t).toContain('최악 -12.41%')
    expect(t).toContain('낙폭 -5.42%')
  })

  it('대조군 대비 우위는 불확실성 폭과 같이 나온다 — 숫자 하나만 두면 확실해 보인다', () => {
    const c = trustCard()
    expect(c.text()).toContain('대조군比 -2.64%')
    expect(c.text()).toContain('±1.90')
  })

  it('불확실성을 모르면 ± 물음표 — 0 으로 두면 어떤 미세한 우위도 확실해진다(§4c)', () => {
    const c = trustCard({ edgeMarginOfError: null })
    expect(c.text()).toContain('±?')
    expect(c.find('[title]').exists()).toBe(true)
  })

  it('표본 시작일을 밝히고 이전 산식 성적은 참고로만 — 현재 판정 수치와 구분된다(2026-10-01 경계)', () => {
    const c = trustCard({
      sampleSince: '2026-10-02',
      legacy: { from: '2026-06-25', toExclusive: '2026-10-02', rows: 183, distinctDays: 44, controlRows: 35,
        costAdjustedReturn: -4.33, edgeVsControl: 0.17, state: 'EVALUABLE' }
    })
    const line = c.find('.trust-sample').text()
    expect(line).toContain('표본 2026-10-02~')
    expect(line).toContain('현재 산식')
    expect(line).toContain('이전 산식 183건')
    expect(line).toContain('비용차감 -4.33%')
    expect(line).toContain('참고만')
    // 현재 표본 수치(비용차감 -2.03%)는 그대로 — 이전 산식 값이 그 자리를 덮지 않는다
    expect(c.find('.trust-nums').text()).toContain('비용차감 -2.03%')
  })

  it('경계 이전 평가 행이 없으면 이전 산식 줄을 만들지 않는다 — 0건으로 위장하지 않는다', () => {
    const c = trustCard({ sampleSince: '2026-10-02', legacy: null })
    expect(c.find('.trust-sample').text()).toContain('표본 2026-10-02~')
    expect(c.find('.trust-sample').text()).not.toContain('이전 산식')
  })

  it('표본 시작일 미정이면 "검증 중"을 밝힌다 — 현재 산식 판정이 아직 없다(2026-10-01)', () => {
    const c = trustCard({ sampleSince: null, sampleStatus: 'UNSET', legacy: null })
    expect(c.find('.trust-sample').text()).toContain('표본 시작일 미정')
    expect(c.find('.trust-sample').text()).toContain('검증 중')
  })

  it('잠정 경계면 "잠정"을 붙인다 — 첫 추천 반영 확인 전', () => {
    const c = trustCard({ sampleSince: '2026-10-05', sampleStatus: 'PROVISIONAL', legacy: null })
    expect(c.find('.trust-sample').text()).toContain('2026-10-05~')
    expect(c.find('.trust-sample').text()).toContain('잠정')
  })

  it('통과해도 실매수 승인이 아니라고 적는다', () => {
    const c = trustCard({
      state: 'CONSIDER_EXPANDING', rows: 40, distinctDays: 12, controlRows: 40,
      costAdjustedReturn: 2.82, edgeVsControl: 3.0, edgeMarginOfError: 0.4,
      edgeExceedsUncertainty: true, blockers: [],
      note: '모의운용 확대 검토 가능 — 실매수 승인 아님'
    })
    expect(c.find('.trust-state').text()).toBe('모의운용 확대 검토')
    expect(c.classes()).toContain('ok')
    expect(c.text()).toContain('실매수 승인 아님')
  })

  it('표본이 차도 근거가 없으면 평가 가능에서 멈추고 막는 사유를 보여준다', () => {
    const c = trustCard({
      state: 'EVALUABLE', rows: 40, distinctDays: 12, controlRows: 40,
      note: '평가 가능 — 아직 확대 근거 없음: 비용 차감 수익 -2.03%'
    })
    expect(c.find('.trust-state').text()).toBe('평가 가능')
    expect(c.classes()).not.toContain('ok')
    expect(c.text()).toContain('아직 확대 근거 없음')
  })

  it('집계 실패는 "근거 없음"이 아니라 측정 불가로 표시한다(§4c)', () => {
    const c = trustCard({ dataAvailable: false, note: '집계 실패 (DataAccessException)' })
    expect(c.find('.trust-state').exists()).toBe(false)
    expect(c.text()).toContain('집계 실패')
  })
})

/** ⑧ 봇을 믿고 맡겨도 되나 — 기본은 운영 실측(2026-10-02)과 같은 모양: 봇 꺼짐(7/27~), 7월 모의 성적 평가 가능. */
function botGate(over = {}, lineOver = {}) {
  return {
    dataAvailable: true,
    currentMode: 'VIRTUAL',
    botActive: false,
    botStatusChangedAt: '2026-07-27T15:20:15',
    note: '봇 꺼짐 — 2026-07-27부터 · 새 표본이 쌓이지 않는다',
    noteDetail: '봇은 추천 점수를 쓰지 않는다',
    lines: [
      {
        mode: 'VIRTUAL',
        dataAvailable: true,
        state: 'EVALUABLE',
        trades: 114,
        distinctDays: 16,
        dailyMeanPct: -0.53,
        marginOfError: 0.41,
        profitExceedsUncertainty: false,
        winRatePct: 38.6,
        avgWinPct: 1.1,
        avgLossPct: -1.4,
        worstPct: -4.8,
        maxDrawdownPct: -8.3,
        realizedPnlKrw: -799916,
        firstTradeDay: '2026-07-03',
        lastTradeDay: '2026-07-27',
        strategies: [
          { strategy: 'SCALPING', trades: 86, distinctDays: 16, dailyMeanPct: -0.61, winRatePct: 36.0 },
          { strategy: 'SWING', trades: 28, distinctDays: 12, dailyMeanPct: -0.2, winRatePct: 46.4 }
        ],
        excludedTrades: 0,
        blockers: ['하루 평균 순수익 -0.53%'],
        note: '평가 가능 — 아직 근거 없음: 하루 평균 순수익 -0.53%',
        noteDetail: '유효 표본은 거래가 있었던 날 수다',
        ...lineOver
      }
    ],
    ...over
  }
}

describe('ControlRoomKpis ⑧ — "봇을 믿고 맡겨도 되나" 게이트', () => {
  const botCard = (over, lineOver) =>
    mount(ControlRoomKpis, { props: { kpis: kpis({ botGate: botGate(over, lineOver) }) } }).find('.kpi.bot')

  it('봇이 꺼져 있으면 그것부터 말한다 — 아래 성적은 꺼지기 전 기록이다', () => {
    const c = botCard()
    expect(c.find('.bot-note').text()).toBe('봇 꺼짐 — 2026-07-27부터 · 새 표본이 쌓이지 않는다')
    // 꺼짐은 고장이 아니라 상태 — 빨강(alert)이 아니라 주황(warn), 통과(ok)로도 칠하지 않는다
    expect(c.classes()).toContain('warn')
    expect(c.classes()).not.toContain('alert')
    expect(c.classes()).not.toContain('ok')
  })

  it('모드·상태·표본(거래일 강조)·기간을 백엔드 값 그대로 보여준다', () => {
    const c = botCard()
    expect(c.find('.trust-state').text()).toBe('모의 · 평가 가능')
    expect(c.text()).toContain('거래 114건')
    expect(c.find('b').text()).toBe('거래일 16일')
    expect(c.text()).toContain('2026-07-03~2026-07-27')
  })

  it('하루 평균은 불확실성 폭과 함께, 실현손익은 원 단위로', () => {
    const t = botCard().text()
    expect(t).toContain('하루 평균 -0.53%')
    expect(t).toContain('±0.41')
    expect(t).toContain('실현손익 -799,916')
  })

  it('승률만으로 판단하지 않는다 — 이익·손실·최악·최대낙폭과 전략별 성적이 보인다', () => {
    const c = botCard()
    expect(c.text()).toContain('승률 38.6%')
    expect(c.text()).toContain('최악 -4.80%')
    expect(c.text()).toContain('최대낙폭 -8.30%')
    expect(c.find('.bot-strategies').text()).toContain('스캘핑 86건 -0.61%')
    expect(c.find('.bot-strategies').text()).toContain('스윙 28건 -0.20%')
  })

  it('막는 사유가 담긴 헤드라인과, 추천 점수가 아니라 봇 매매 기록 기준이라는 출처를 밝힌다', () => {
    const c = botCard()
    expect(c.text()).toContain('평가 가능 — 아직 근거 없음: 하루 평균 순수익 -0.53%')
    expect(c.find('.basis').text()).toContain('추천 점수 아님')
    expect(c.find('.basis').text()).toContain('실전 승인 아님')
  })

  it('켜져 있고 통과면 ok — 상태 라벨은 "확대 검토 가능"(승인 아님)', () => {
    const c = botCard({ botActive: true, note: null }, { state: 'CONSIDER_EXPANDING', profitExceedsUncertainty: true })
    expect(c.find('.trust-state').text()).toBe('모의 · 확대 검토 가능')
    expect(c.classes()).toContain('ok')
    expect(c.find('.bot-note').exists()).toBe(false)
  })

  it('꺼진 봇은 통과여도 초록으로 칠하지 않는다 — 그 성적은 지금 봇이 아니다', () => {
    const c = botCard({}, { state: 'CONSIDER_EXPANDING' })
    expect(c.classes()).not.toContain('ok')
  })

  it('불확실성을 모르면 ± 물음표(§4c)', () => {
    expect(botCard({}, { marginOfError: null }).text()).toContain('±?')
  })

  it('줄 단위 집계 실패는 "측정 불가" + 사유 — 거래 0건으로 위장하지 않는다', () => {
    const c = botCard({}, { dataAvailable: false, state: null, note: '집계 실패 (QueryTimeoutException)' })
    expect(c.find('.trust-state').text()).toBe('모의 · 측정 불가')
    expect(c.text()).toContain('집계 실패 (QueryTimeoutException)')
    expect(c.text()).not.toContain('거래 114건')
  })

  it('카드 전체 집계 실패는 NoData — 숫자를 하나도 그리지 않는다', () => {
    const c = botCard({ dataAvailable: false, note: '봇 성과 서비스 미가용', lines: [] })
    expect(c.find('.trust-state').exists()).toBe(false)
    expect(c.text()).toContain('봇 성과 서비스 미가용')
  })

  it('실전 줄이 함께 오면 모드별로 쌓는다', () => {
    const real = { ...botGate().lines[0], mode: 'REAL', trades: 3, distinctDays: 2, state: 'COLLECTING' }
    const c = botCard({ lines: [botGate().lines[0], real] })
    expect(c.findAll('.bot-line')).toHaveLength(2)
    expect(c.findAll('.trust-state').map((s) => s.text())).toEqual(['모의 · 평가 가능', '실전 · 표본 수집 중'])
  })
})
