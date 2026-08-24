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

    expect(w.find('.failed').exists()).toBe(true)
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
    expect(w.find('.typing').text()).toContain('SCOUT')
    expect(w.find('.typing').text()).toContain('1/5')
  })

  it('RUNNING 중에는 새 지시를 보낼 수 없다 (동시 1건)', () => {
    const w = mount(CrewPanel, {
      props: { crew: ENABLED_CREW, session: completedSession({ status: 'RUNNING', actions: [] }) }
    })
    expect(w.find('.in button').attributes('disabled')).toBeDefined()
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
