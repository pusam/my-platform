import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CrewPanel from './CrewPanel.vue'

/**
 * CREW 패널.
 *
 * 이 화면이 절대 하면 안 되는 것 3가지를 회귀로 묶는다.
 *  ① 크루를 못 쓰는데 이유를 안 보여주기 — 버튼만 죽어 있으면 운영자가 원인을 못 찾는다.
 *  ② 세션 실패를 조용히 끝내기 — failureReason 은 반드시 화면에 나와야 한다.
 *  ③ 액션 버튼이 실행처럼 보이기 — 버튼은 새 지시를 보낼 뿐이라는 문구가 있어야 한다.
 */
const ENABLED_CREW = {
  enabled: true,
  disabledReason: null,
  model: 'claude-opus-5',
  dailyLimit: 30,
  usedToday: 3,
  running: false
}

function completedSession(overrides = {}) {
  return {
    id: 1,
    status: 'COMPLETED',
    instruction: '밀린 판정 정리해',
    totalTurns: 5,
    omittedFlags: 0,
    failureReason: null,
    actions: ['SCHEDULE_DECISIONS.md 초안 만들어', 'V52 등록 프롬프트 뽑아줘'],
    messages: [
      { turnNo: 0, agent: 'OPERATOR', displayName: 'OPERATOR', phase: 'ORDER', content: '밀린 판정 정리해', truncated: false },
      { turnNo: 5, agent: 'EREN', displayName: '에렌', phase: 'CLOSING', content: '결론: 5건은 9/16 한 세션으로.', truncated: false }
    ],
    ...overrides
  }
}

describe('CrewPanel — 비활성 사유 노출', () => {
  it('크루가 꺼져 있으면 사유를 화면에 적는다', () => {
    const w = mount(CrewPanel, {
      props: {
        crew: {
          enabled: false,
          disabledReason: 'ANTHROPIC_API_KEY 미설정 — 크루 비활성 (앱 나머지는 정상)',
          model: 'claude-opus-5',
          dailyLimit: 30,
          usedToday: 0,
          running: false
        },
        session: null
      }
    })

    expect(w.text()).toContain('크루 비활성')
    expect(w.text()).toContain('ANTHROPIC_API_KEY 미설정')
    expect(w.find('.mode').text()).toContain('DISABLED')
    expect(w.find('.in button').attributes('disabled')).toBeDefined()
  })

  it('비활성 박스에 "모델 재확인" 버튼이 있고 누르면 verify 를 emit 한다', async () => {
    const w = mount(CrewPanel, {
      props: {
        crew: { enabled: false, disabledReason: 'API key is invalid', dailyLimit: 30, usedToday: 0 },
        session: null
      }
    })

    const btn = w.find('.verify')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toContain('모델 재확인')

    await btn.trigger('click')
    expect(w.emitted('verify')).toBeTruthy()
  })

  it('재확인 중에는 버튼이 잠기고 진행 표시로 바뀐다', () => {
    const w = mount(CrewPanel, {
      props: {
        crew: { enabled: false, disabledReason: 'API key is invalid', dailyLimit: 30, usedToday: 0 },
        session: null,
        verifying: true
      }
    })

    const btn = w.find('.verify')
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.text()).toContain('확인 중')
  })

  it('크루가 정상이면 비활성 박스도 재확인 버튼도 없다', () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null } })

    expect(w.find('.verify').exists()).toBe(false)
    expect(w.find('.state').classes()).not.toContain('warn')
    expect(w.find('.live').text()).toBe('ONLINE')
  })

  it('비활성이면 퀵칩도 눌리지 않는다', () => {
    const w = mount(CrewPanel, {
      props: { crew: { enabled: false, disabledReason: '모델 확인 실패', dailyLimit: 30, usedToday: 0 }, session: null }
    })
    const chips = w.findAll('.chips button')
    expect(chips.length).toBeGreaterThan(0)
    chips.forEach((c) => expect(c.attributes('disabled')).toBeDefined())
  })
})

describe('CrewPanel — 실패/잘림 상태', () => {
  it('FAILED 세션은 사유와 "자동 재시도 안 함"을 보여준다', () => {
    const w = mount(CrewPanel, {
      props: {
        crew: ENABLED_CREW,
        session: completedSession({
          status: 'FAILED',
          failureReason: '턴 3 실패 — RateLimitException: 429',
          actions: []
        })
      }
    })

    expect(w.find('.state').classes()).toContain('err')
    expect(w.find('.live').text()).toBe('FAILED')
    expect(w.text()).toContain('턴 3 실패')
    expect(w.text()).toContain('자동 재시도하지 않는다')
  })

  it('stop_reason=max_tokens 인 턴에 "응답 잘림" 배지를 붙인다', () => {
    const session = completedSession()
    session.messages[1].truncated = true
    session.messages[1].maxTokens = 1000

    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session } })
    expect(w.find('.trunc').exists()).toBe(true)
    expect(w.find('.trunc').text()).toContain('응답 잘림')
  })

  it('컨텍스트 상한으로 생략된 FLAGGED 가 있으면 그 사실을 알린다', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: completedSession({ omittedFlags: 4 }) }
    })
    expect(w.find('.omitted').text()).toContain('4건')
    expect(w.find('.omitted').text()).toContain('불완전')
  })

  it('상한/동시실행 거부 메시지를 그대로 노출한다', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: null, errorMessage: '일일 상한 도달 — 오늘은 더 실행할 수 없다' }
    })
    expect(w.find('.err').text()).toContain('일일 상한 도달')
  })
})

describe('CrewPanel — 액션 버튼은 지시 전송일 뿐', () => {
  it('결론의 액션 2개를 버튼으로 그리고, 실행이 아님을 명시한다', () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: completedSession() } })

    const buttons = w.findAll('.acts button')
    expect(buttons).toHaveLength(2)
    expect(buttons[0].text()).toBe('SCHEDULE_DECISIONS.md 초안 만들어')
    expect(w.find('.acts-note').text()).toContain('실행하지 않는다')
  })

  it('액션 버튼을 누르면 그 문구로 ask 를 emit 한다 (실행 호출 없음)', async () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: completedSession() } })

    await w.findAll('.acts button')[1].trigger('click')

    expect(w.emitted('ask')).toBeTruthy()
    expect(w.emitted('ask')[0]).toEqual(['V52 등록 프롬프트 뽑아줘'])
  })

  it('입력 후 SEND 는 지시문을 emit 하고 입력창을 비운다', async () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null } })

    await w.find('.in input').setValue('  NXT 준비 상태 봐줘  ')
    await w.find('.in button').trigger('click')

    expect(w.emitted('ask')[0]).toEqual(['NXT 준비 상태 봐줘'])
    expect(w.find('.in input').element.value).toBe('')
  })
})

describe('CrewPanel — 진행 표시', () => {
  it('RUNNING 이면 다음 차례 에이전트가 busy 로 표시된다', () => {
    const running = completedSession({
      status: 'RUNNING',
      actions: [],
      messages: [
        { turnNo: 0, agent: 'OPERATOR', displayName: 'OPERATOR', phase: 'ORDER', content: '지시', truncated: false },
        { turnNo: 1, agent: 'EREN', displayName: '에렌', phase: 'ROUTING', content: '분배', truncated: false }
      ]
    })

    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: running } })

    // 에렌(ROUTING) 이 끝났으니 다음은 SCOUT(DRAFT)
    expect(w.find('.card.scout').classes()).toContain('busy')
    expect(w.find('.state').text()).toContain('SCOUT')
    expect(w.find('.state').text()).toContain('1/5')
    // 5턴 막대: 1칸 완료, 2번째가 진행 중
    const steps = w.findAll('.steps i')
    expect(steps).toHaveLength(5)
    expect(steps[0].classes()).toContain('done')
    expect(steps[1].classes()).toContain('now')
  })

  it('RUNNING 중에는 새 지시를 보낼 수 없다 (동시 1건)', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: completedSession({ status: 'RUNNING', actions: [] }) }
    })
    expect(w.find('.in button').attributes('disabled')).toBeDefined()
  })
})

describe('CrewPanel — 상태 줄은 어떤 상황에서도 한 줄 찍힌다', () => {
  it('세션이 없어도 대기 상태를 설명한다 (빈 화면 금지)', () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null } })

    expect(w.find('.state').exists()).toBe(true)
    expect(w.find('.state').text()).toContain('대기')
    expect(w.find('.state').text()).toContain('5턴')
  })

  it('완료 세션도 상태 줄을 남긴다', () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: completedSession() } })

    expect(w.find('.state').classes()).toContain('done')
    expect(w.find('.state').text()).toContain('완료')
  })

  it('일일 사용량이 상한에 가까우면 배지 색이 올라간다', () => {
    const near = mount(CrewPanel, {
      props: { crew: { ...ENABLED_CREW, usedToday: 25, dailyLimit: 30 }, session: null }
    })
    expect(near.find('.daily').classes()).toContain('near')

    const hot = mount(CrewPanel, {
      props: { crew: { ...ENABLED_CREW, usedToday: 30, dailyLimit: 30 }, session: null }
    })
    expect(hot.find('.daily').classes()).toContain('hot')
  })

  it('상한이 0 이하면 무제한이라 배지를 숨긴다', () => {
    const w = mount(CrewPanel, {
      props: { crew: { ...ENABLED_CREW, dailyLimit: 0 }, session: null }
    })
    expect(w.find('.daily').exists()).toBe(false)
  })
})

describe('CrewPanel — FIREWALL 판정 배지', () => {
  function reviewSession(content) {
    return completedSession({
      actions: [],
      messages: [
        { turnNo: 3, agent: 'FIREWALL', displayName: 'FIREWALL', phase: 'REVIEW', content, truncated: false }
      ]
    })
  }

  it('대괄호 형식 [조건부] 를 배지로 바꾼다', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: reviewSession(`[조건부]
1. 보완사항`) }
    })
    expect(w.find('.tag.cond').text()).toBe('조건부')
  })

  it('대괄호 없는 "조건부 — ..." 도 배지로 바꾼다 (모델이 자주 흘린다)', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: reviewSession('조건부 — 초안은 불변식 경계를 지켰고') }
    })
    expect(w.find('.tag.cond').text()).toBe('조건부')
    expect(w.find('.tx').text()).toContain('초안은 불변식 경계를 지켰고')
  })

  it('승인/반려도 각각 다른 색 배지가 된다', () => {
    const ok = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: reviewSession('승인. 문제 없음') }
    })
    expect(ok.find('.tag.ok').text()).toBe('승인')

    const no = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: reviewSession(`[반려]
불변식 위반`) }
    })
    expect(no.find('.tag.no').text()).toBe('반려')
  })

  it('REVIEW 가 아닌 턴에서는 줄머리 "승인" 이 배지로 바뀌지 않는다 (오탐 방지)', () => {
    const w = mount(CrewPanel, {
      props: {
        crew: ENABLED_CREW,
        session: completedSession({
          actions: [],
          messages: [
            { turnNo: 2, agent: 'SCOUT', displayName: 'SCOUT', phase: 'DRAFT',
              content: '승인 절차를 초안에 넣는다', truncated: false }
          ]
        })
      }
    })
    expect(w.find('.tag').exists()).toBe(false)
    expect(w.find('.tx').text()).toContain('승인 절차를 초안에 넣는다')
  })

  it('판정 뒤 본문이 통째로 삼켜지지 않는다', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: reviewSession(`[승인]
1. 첫째
2. 둘째`) }
    })
    // .html() 은 vue-test-utils 가 예쁘게 포매팅하므로 원문 개행으로 단언하지 않는다.
    expect(w.find('.tag.ok').text()).toBe('승인')
    expect(w.find('.tx').text()).toContain('1. 첫째')
    expect(w.find('.tx').text()).toContain('2. 둘째')
  })
})

describe('CrewPanel — 모델 출력을 HTML 로 신뢰하지 않는다', () => {
  it('본문의 태그는 이스케이프되고 판정 배지만 마크업이 된다', () => {
    const session = completedSession({
      actions: [],
      messages: [
        {
          turnNo: 3,
          agent: 'FIREWALL',
          displayName: 'FIREWALL',
          phase: 'REVIEW',
          content: '[조건부]\n<img src=x onerror=alert(1)>',
          truncated: false
        }
      ]
    })

    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session } })
    const html = w.find('.tx').html()

    expect(html).toContain('&lt;img')
    expect(html).not.toContain('<img')
    expect(w.find('.tag.cond').text()).toBe('조건부')
  })
})

describe('CrewPanel — 세션 이력', () => {
  const history = [
    { id: 12, status: 'COMPLETED', instruction: '밀린 판정 정리해', usage: { outputTokens: 4200, complete: true } },
    { id: 11, status: 'FAILED', instruction: 'NXT 준비 상태', usage: { outputTokens: 900, complete: false } }
  ]

  it('이력이 없으면 섹션 자체가 안 뜬다', () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null, sessions: [] } })
    expect(w.find('.hist').exists()).toBe(false)
  })

  it('건수와 누적 출력 토큰을 보여준다 (비용 감각용)', () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null, sessions: history } })

    expect(w.find('.hist-h').text()).toContain('2건')
    expect(w.find('.hist-h').text()).toContain('5,100')   // 4200 + 900
  })

  it('펼치면 세션 목록이 나오고 클릭하면 select 를 emit 한다', async () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null, sessions: history } })

    await w.find('.hist-h').trigger('click')
    const items = w.findAll('.hist-item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('밀린 판정 정리해')

    await items[1].trigger('click')
    expect(w.emitted('select')[0]).toEqual([11])
  })

  it('상태별로 다른 색 클래스를 쓴다 — 실패한 세션이 눈에 띄어야 한다', async () => {
    const w = mount(CrewPanel, { props: { crew: ENABLED_CREW, session: null, sessions: history } })
    await w.find('.hist-h').trigger('click')

    expect(w.findAll('.hist-item .st')[0].classes()).toContain('COMPLETED')
    expect(w.findAll('.hist-item .st')[1].classes()).toContain('FAILED')
  })

  it('현재 보고 있는 세션이 표시된다', async () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: { ...completedSession(), id: 11 }, sessions: history }
    })
    await w.find('.hist-h').trigger('click')

    expect(w.findAll('.hist-item')[1].classes()).toContain('on')
  })
})
