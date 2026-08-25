<template>
  <section class="crew">
    <div class="crew-h">
      <b>CREW</b>
      <!--
        상태 점은 좌측(제목 옆)에 둔다. App.vue 가 NotificationBell 을 전역 fixed 로 우상단에
        띄우기 때문에 오른쪽 끝에 두면 벨이 덮어 ONLINE/OFFLINE 을 못 읽는다.
      -->
      <span class="live" :class="liveClass">{{ liveText }}</span>
      <span v-if="dailyText" class="daily" :class="dailyClass">{{ dailyText }}</span>
    </div>

    <div class="cards">
      <div
        v-for="agent in AGENTS"
        :key="agent.key"
        class="card"
        :class="[agent.key, { busy: busyAgent === agent.key, off: !crewEnabled }]"
      >
        <span class="st">{{ busyAgent === agent.key ? busyPhase : 'IDLE' }}</span>
        <b>{{ agent.name }}</b>
        <small>{{ agent.role }}</small>
      </div>
    </div>

    <!-- 5턴 진행 막대 — 지금 몇 번째 턴인지 항상 보인다 -->
    <div class="steps" :title="`5턴 중 ${doneTurns}턴 완료`">
      <i v-for="(step, i) in STEPS" :key="step.phase" :class="stepClass(i)"></i>
    </div>

    <!--
      상태 줄 — 어떤 상황에서도 반드시 한 줄이 찍힌다(빈 화면 금지).
      비활성 사유·실행 중 턴·실패 사유가 전부 여기로 모인다.
    -->
    <div class="state" :class="stateClass">
      <b>{{ stateText }}</b>
      <span v-if="stateSub" class="sub">{{ stateSub }}</span>
      <button v-if="!crewEnabled" type="button" class="verify" :disabled="verifying" @click="$emit('verify')">
        {{ verifying ? '확인 중…' : '모델 재확인' }}
      </button>
    </div>

    <div ref="threadEl" class="thread">
      <p v-if="!session" class="thread-empty">
        지시를 적거나 캘린더 날짜를 누르면 크루가 5턴으로 검토한다.<br />
        결론은 <b>제안</b>이고 실행은 사람이 한다 — 크루는 아무것도 바꾸지 못한다.
      </p>

      <template v-else>
        <div v-for="msg in session.messages" :key="msg.turnNo" class="msg" :class="agentClass(msg.agent)">
          <div v-if="msg.agent !== 'OPERATOR'" class="av" aria-hidden="true">{{ initial(msg.agent) }}</div>
          <div class="bd">
            <div class="nm">
              {{ msg.displayName }}
              <span v-if="msg.addressedTo" class="to">{{ msg.addressedTo }}</span>
              <span v-if="msg.truncated" class="trunc" :title="`max_tokens=${msg.maxTokens} 도달`">
                응답 잘림
              </span>
            </div>
            <div class="tx" v-html="renderContent(msg.content, msg.phase)"></div>

            <div v-if="msg.outputTokens != null" class="meta">
              {{ msg.model }} · effort {{ msg.effort }} · out {{ msg.outputTokens }}/{{ msg.maxTokens }}
            </div>

            <!-- 액션 버튼 = 새 지시를 보내는 것뿐. 아무것도 실행하지 않는다. -->
            <div v-if="isClosing(msg) && session.actions.length" class="acts">
              <button
                v-for="action in session.actions"
                :key="action"
                type="button"
                :disabled="!canSend"
                @click="$emit('ask', action)"
              >
                {{ action }}
              </button>
              <span class="acts-note">버튼은 새 지시를 보낼 뿐 실행하지 않는다</span>
            </div>
          </div>
        </div>

        <div v-if="session.omittedFlags > 0" class="omitted">
          컨텍스트 상한으로 FLAGGED {{ session.omittedFlags }}건이 크루에게 전달되지 않았다
          (중요도 낮은 순). 크루 판단은 그만큼 불완전하다.
        </div>
      </template>
    </div>

    <div class="crew-f">
      <div v-if="errorMessage" class="err">{{ errorMessage }}</div>

      <div class="chips">
        <button
          v-for="chip in QUICK_CHIPS"
          :key="chip"
          type="button"
          :disabled="!canSend"
          @click="$emit('ask', chip)"
        >
          {{ chip }}
        </button>
      </div>

      <div class="in">
        <input
          v-model="draft"
          type="text"
          placeholder="에렌에게 지시"
          autocomplete="off"
          :disabled="!canSend"
          @keydown.enter="submit"
        />
        <button type="button" :disabled="!canSend || !draft.trim()" @click="submit">SEND</button>
      </div>

      <div class="mode">{{ modeText }}</div>

      <!--
        세션 이력 — API 는 처음부터 있었는데 화면이 없어 지난 결론을 되찾을 수 없었다.
        비용 확인도 겸한다(일 상한 30세션이면 무시 못 할 금액이 된다).
      -->
      <div v-if="sessions.length" class="hist">
        <button type="button" class="hist-h" @click="histOpen = !histOpen">
          <span>{{ histOpen ? '▾' : '▸' }} 지난 세션 {{ sessions.length }}건</span>
          <em v-if="totalOutputTokens">누적 출력 {{ totalOutputTokens.toLocaleString('ko-KR') }} 토큰</em>
        </button>

        <div v-if="histOpen" class="hist-list">
          <button
            v-for="s in sessions"
            :key="s.id"
            type="button"
            class="hist-item"
            :class="{ on: session && session.id === s.id }"
            :title="s.instruction"
            @click="$emit('select', s.id)"
          >
            <span class="id">#{{ s.id }}</span>
            <span class="ins">{{ s.instruction }}</span>
            <span class="st" :class="s.status">{{ statusLabel(s.status) }}</span>
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
/**
 * CREW 패널 — 에렌 / SCOUT / FIREWALL 이 5턴으로 초안→검토→반영→결론을 만든다.
 *
 * 화면이 지켜야 할 것:
 *  ① 크루가 비활성이면 버튼만 죽이지 말고 **사유를 보여준다**(키 없음/모델 없음/상한 도달).
 *  ② 세션 실패는 조용히 끝내지 않고 failureReason 을 그대로 노출한다. 자동 재시도는 없다.
 *  ③ 액션 버튼은 **새 지시를 보내는 것뿐**이고 무엇도 실행하지 않는다 — 문구로도 못박는다.
 *  ④ **어떤 상태에서도 상태 줄 한 줄은 반드시 찍힌다** — 빈 화면은 "고장"으로 읽힌다.
 */
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({
  crew: { type: Object, default: null },
  session: { type: Object, default: null },
  sessions: { type: Array, default: () => [] },
  errorMessage: { type: String, default: null },
  sending: { type: Boolean, default: false },
  verifying: { type: Boolean, default: false }
})

const emit = defineEmits(['ask', 'verify', 'select'])

const draft = ref('')
const threadEl = ref(null)
const histOpen = ref(false)

const AGENTS = [
  { key: 'eren', name: '에렌', role: '총괄 · 분배/결론' },
  { key: 'scout', name: 'SCOUT', role: '분석 · 초안' },
  { key: 'firewall', name: 'FIREWALL', role: '검증 · 불변식' }
]

/** 백엔드 CrewPrompts.Step 과 같은 순서 — 진행 표시에만 쓴다. */
const STEPS = [
  { key: 'eren', name: '에렌', phase: 'ROUTING' },
  { key: 'scout', name: 'SCOUT', phase: 'DRAFT' },
  { key: 'firewall', name: 'FIREWALL', phase: 'REVIEW' },
  { key: 'scout', name: 'SCOUT', phase: 'REVISE' },
  { key: 'eren', name: '에렌', phase: 'CLOSING' }
]

const QUICK_CHIPS = [
  '밀린 판정 정리해',
  'FLAGGED critical 부터 처리 순서 잡아',
  '다음 판정일 준비 상태 점검해',
  '지금 봇 게이트 상태 해석해줘'
]

const crewEnabled = computed(() => !!props.crew?.enabled)
const isRunning = computed(() => props.session?.status === 'RUNNING')
const isFailed = computed(() => props.session?.status === 'FAILED')

const doneTurns = computed(
  () => (props.session?.messages ?? []).filter((m) => m.agent !== 'OPERATOR').length
)

const currentStep = computed(() => (isRunning.value ? STEPS[doneTurns.value] ?? null : null))
const busyAgent = computed(() => currentStep.value?.key ?? null)
const busyPhase = computed(() => currentStep.value?.phase ?? 'IDLE')

function stepClass(index) {
  if (index < doneTurns.value) return 'done'
  if (index === doneTurns.value && isRunning.value) return 'now'
  if (index === doneTurns.value && isFailed.value) return 'fail'
  return ''
}

const liveClass = computed(() => {
  if (!crewEnabled.value) return 'off'
  if (isRunning.value) return 'run'
  if (isFailed.value) return 'err'
  return ''
})

const liveText = computed(() => {
  if (!crewEnabled.value) return 'OFFLINE'
  if (isRunning.value) return 'RUNNING'
  if (isFailed.value) return 'FAILED'
  return 'ONLINE'
})

/** 오늘 사용량 — 상한에 가까워지면 색이 올라간다. 상한 0 이하면 무제한이라 표시하지 않는다. */
const dailyText = computed(() => {
  const c = props.crew
  if (!c || !c.dailyLimit || c.dailyLimit <= 0) return null
  return `오늘 ${c.usedToday}/${c.dailyLimit}`
})

const dailyClass = computed(() => {
  const c = props.crew
  if (!c || !c.dailyLimit || c.dailyLimit <= 0) return ''
  const ratio = c.usedToday / c.dailyLimit
  if (ratio >= 1) return 'hot'
  if (ratio >= 0.8) return 'near'
  return ''
})

const stateClass = computed(() => {
  if (!crewEnabled.value) return 'warn'
  if (isRunning.value) return 'run'
  if (isFailed.value) return 'err'
  if (props.session) return 'done'
  return ''
})

const stateText = computed(() => {
  if (!crewEnabled.value) return '크루 비활성'
  if (isRunning.value) {
    const step = currentStep.value
    return step ? `${step.name} ${step.phase} 작성 중` : '실행 중'
  }
  if (isFailed.value) return '세션 실패'
  if (props.session) return '완료'
  return '대기'
})

const stateSub = computed(() => {
  if (!crewEnabled.value) return props.crew?.disabledReason || '사유 미상'
  if (isRunning.value) return `${doneTurns.value}/${props.session?.totalTurns ?? 5}턴`
  if (isFailed.value) {
    return `${props.session?.failureReason || '사유 미상'} — 자동 재시도하지 않는다. 필요하면 지시를 다시 보내라.`
  }
  if (props.session) return `5턴 완료 · 발언 ${props.session.messages.length - 1}개`
  return '지시를 입력하면 5턴(분배→초안→검토→반영→결론)이 돈다'
})

const canSend = computed(() => crewEnabled.value && !props.sending && !isRunning.value)

const modeText = computed(() => {
  if (!props.crew) return 'MODE · —'
  if (!crewEnabled.value) return 'MODE · DISABLED'
  return `MODE · ${props.crew.model} · 5턴 고정`
})

/** 누적 출력 토큰 — 비용 감각용. usage 없는 턴은 더하지 않는다(§4c, 0 으로 메우지 않음). */
const totalOutputTokens = computed(() =>
  props.sessions.reduce((sum, s) => sum + (s.usage?.outputTokens || 0), 0)
)

function statusLabel(status) {
  if (status === 'RUNNING') return '진행'
  if (status === 'COMPLETED') return '완료'
  return '실패'
}

function agentClass(agent) {
  if (agent === 'OPERATOR') return 'user'
  if (agent === 'EREN') return 'eren'
  if (agent === 'SCOUT') return 'scout'
  return 'firewall'
}

/** 아바타는 이니셜 한 글자 — SVG 3종을 들고 있을 이유가 없다. */
function initial(agent) {
  if (agent === 'EREN') return '에'
  if (agent === 'SCOUT') return 'S'
  return 'F'
}

function isClosing(msg) {
  return msg.phase === 'CLOSING'
}

/**
 * FIREWALL 검토 턴의 판정(승인/조건부/반려)만 배지로 바꾼다.
 * 그 외 텍스트는 이스케이프해서 그대로 둔다 — 모델 출력을 HTML 로 신뢰하지 않는다.
 *
 * ⚠ 대괄호는 **선택**이다. 프롬프트는 "[조건부]" 형식을 요구하지만 모델이 실제로는
 * "조건부 — ..." 처럼 대괄호를 빼고 쓰는 일이 잦다(2026-08-25 실측). 프롬프트를 조여도
 * 또 흘리므로 파서를 너그럽게 만드는 쪽이 맞다.
 *
 * 오탐 방지를 위해 **REVIEW 턴의 맨 앞**에서만 매칭한다 — 다른 턴 본문에 "승인" 같은 단어가
 * 줄머리에 오더라도 배지로 바뀌지 않는다.
 */
const VERDICT_PATTERN = /^[ \t]*\[?(승인|조건부|반려)\]?[ \t]*(?:[—–:-][ \t]*)?/

function renderContent(text, phase) {
  const escaped = escapeHtml(text || '')
  if (phase !== 'REVIEW') return escaped
  return escaped.replace(
    VERDICT_PATTERN,
    (_, verdict) => `<span class="tag ${verdictClass(verdict)}">${verdict}</span>`
  )
}

function verdictClass(verdict) {
  if (verdict === '승인') return 'ok'
  if (verdict === '반려') return 'no'
  return 'cond'
}

function escapeHtml(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function submit() {
  const value = draft.value.trim()
  if (!value || !canSend.value) return
  draft.value = ''
  emit('ask', value)
}

// 새 턴이 도착하면 스레드를 아래로 붙인다.
watch(
  () => props.session?.messages?.length,
  async () => {
    await nextTick()
    if (threadEl.value) threadEl.value.scrollTop = threadEl.value.scrollHeight
  }
)
</script>

<style scoped>
.crew {
  background: linear-gradient(180deg, #130b25, #0d0818);
  border-left: 1px solid var(--cr-line);
  display: grid;
  grid-template-rows: auto auto auto auto 1fr auto;
  height: 100%;
  min-height: 0;
}

/* 우측 여백 56px = 전역 NotificationBell 자리를 비워두는 것 */
.crew-h {
  display: flex;
  justify-content: flex-start;
  gap: 10px;
  align-items: center;
  padding: 16px 56px 10px 16px;
}
.crew-h b {
  font-family: var(--cr-mono); font-size: 12px; letter-spacing: 0.24em; }

/* 상태 점 — 색이 곧 상태다. 회색 대기 / 초록 실행 / 빨강 실패 / 흐린 비활성 */
.live {
  font-family: var(--cr-mono);
  font-size: 9px;
  letter-spacing: 0.16em;
  color: var(--cr-mut);
}
.live::before { content: '● '; }
.live.run { color: var(--cr-grn); }
.live.run::before { animation: cr-blink 1.6s infinite; }
.live.err { color: var(--cr-red); }
.live.off { color: var(--cr-dim); }
@keyframes cr-blink { 50% { opacity: 0.25; } }

.daily {
  font-family: var(--cr-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  padding: 2px 6px;
  border: 1px solid var(--cr-line);
  color: var(--cr-mut);
}
.daily.near { border-color: var(--cr-amb); color: var(--cr-amb); }
.daily.hot { border-color: var(--cr-red); color: var(--cr-red); }

.cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 0 16px 10px; }
.card {
  border: 1px solid var(--cr-line);
  padding: 11px 6px 9px;
  text-align: center;
  position: relative;
  min-height: 74px;
  background: rgba(0, 0, 0, 0.25);
  transition: border-color 0.25s, box-shadow 0.25s, opacity 0.25s;
}
.card b {
  font-family: var(--cr-mono); display: block; font-size: 11px; letter-spacing: 0.14em; margin-bottom: 3px; }
.card small { font-size: 10px; color: var(--cr-mut); line-height: 1.35; display: block; }
.card.eren b { color: var(--cr-vio); }
.card.scout b { color: var(--cr-cyn); }
.card.firewall b { color: var(--cr-grn); }
.card .st {
  font-family: var(--cr-mono);
  position: absolute;
  top: 5px;
  right: 6px;
  font-size: 8px;
  letter-spacing: 0.1em;
  color: var(--cr-dim);
}
.card.busy { border-color: currentColor; }
.card.busy .st { color: inherit; }
.card.eren.busy { color: var(--cr-vio); box-shadow: 0 0 16px rgba(155, 77, 255, 0.4); }
.card.scout.busy { color: var(--cr-cyn); box-shadow: 0 0 16px rgba(56, 220, 255, 0.35); }
.card.firewall.busy { color: var(--cr-grn); box-shadow: 0 0 16px rgba(61, 255, 138, 0.35); }
.card.off { opacity: 0.4; }

/* 5턴 진행 막대 */
.steps { display: flex; gap: 3px; padding: 0 16px 8px; }
.steps i { flex: 1; height: 4px; background: var(--cr-line); }
.steps i.done { background: var(--cr-vio); }
.steps i.now { background: var(--cr-grn); animation: cr-blink 1.2s infinite; }
.steps i.fail { background: var(--cr-red); }

/* 상태 줄 — 어떤 상태에서도 반드시 한 줄 */
.state {
  margin: 0 16px 10px;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.5;
  border: 1px solid var(--cr-line);
  background: rgba(255, 255, 255, 0.02);
  color: var(--cr-mut);
}
.state b {
  font-family: var(--cr-mono); color: inherit; font-size: 11.5px; letter-spacing: 0.06em; }
.state .sub { display: block; margin-top: 3px; color: var(--cr-mut); font-size: 10.5px; }
.state.run { border-color: rgba(61, 255, 138, 0.45); color: var(--cr-grn); }
.state.err { border-color: rgba(255, 77, 109, 0.5); color: var(--cr-red); background: rgba(255, 77, 109, 0.07); }
.state.warn { border-color: rgba(255, 180, 58, 0.5); color: var(--cr-amb); background: rgba(255, 180, 58, 0.07); }
.state.done { border-color: rgba(155, 77, 255, 0.45); color: var(--cr-tx); }

.verify {
  margin-top: 7px;
  background: transparent;
  border: 1px solid currentColor;
  color: inherit;
  font-size: 11px;
  padding: 4px 9px;
  cursor: pointer;
}
.verify:hover:not(:disabled) { background: rgba(255, 180, 58, 0.15); }
.verify:disabled { opacity: 0.5; cursor: wait; }

.thread {
  overflow: auto;
  padding: 2px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}
.thread-empty { font-size: 12px; color: var(--cr-mut); line-height: 1.7; }
.thread-empty b { color: var(--cr-tx); }

.msg { display: grid; grid-template-columns: 30px 1fr; gap: 8px; align-items: start; }
.msg.user { grid-template-columns: 1fr; }
.msg .av {
  width: 30px;
  height: 30px;
  border: 1px solid var(--cr-line);
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.3);
  font-size: 11px;
  font-weight: 700;
}
.msg.eren .av { color: var(--cr-vio); }
.msg.scout .av { color: var(--cr-cyn); }
.msg.firewall .av { color: var(--cr-grn); }

.msg .bd {
  border-left: 2px solid var(--cr-vio);
  padding: 5px 10px 7px;
  background: rgba(255, 255, 255, 0.025);
  min-width: 0;
}
.msg.scout .bd { border-left-color: var(--cr-cyn); }
.msg.firewall .bd { border-left-color: var(--cr-grn); }
.msg.user .bd { border-left-color: var(--cr-mag); background: rgba(255, 47, 166, 0.06); margin-left: 38px; }

.msg .nm {
  font-family: var(--cr-mono);
  font-size: 10px;
  letter-spacing: 0.16em;
  margin-bottom: 4px;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.msg.eren .nm { color: var(--cr-vio); }
.msg.scout .nm { color: var(--cr-cyn); }
.msg.firewall .nm { color: var(--cr-grn); }
.msg.user .nm { color: var(--cr-mag); }
.msg .nm .to { color: var(--cr-mut); letter-spacing: 0.06em; font-size: 9px; }
.msg .nm .to::before { content: '→ '; }
.msg .nm .trunc {
  font-family: var(--cr-mono);
  color: var(--cr-red);
  border: 1px solid var(--cr-red);
  padding: 0 4px;
  letter-spacing: 0.06em;
  font-size: 9px;
}

.msg .tx { font-size: 12.5px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }
.msg .meta {
  font-family: var(--cr-mono); margin-top: 5px; font-size: 9px; color: var(--cr-dim); letter-spacing: 0.04em; }

.msg .tx :deep(.tag) {
  display: inline-block;
  font-size: 9px;
  letter-spacing: 0.12em;
  padding: 1px 6px;
  border: 1px solid;
  margin-right: 4px;
}
.msg .tx :deep(.tag.ok) { color: var(--cr-grn); border-color: var(--cr-grn); }
.msg .tx :deep(.tag.no) { color: var(--cr-red); border-color: var(--cr-red); }
.msg .tx :deep(.tag.cond) { color: var(--cr-amb); border-color: var(--cr-amb); }

.acts { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; align-items: center; }
.acts button {
  background: transparent;
  border: 1px solid var(--cr-vio);
  color: var(--cr-tx);
  font-size: 11px;
  padding: 4px 9px;
  cursor: pointer;
}
.acts button:hover:not(:disabled) { background: rgba(155, 77, 255, 0.2); }
.acts button:disabled { opacity: 0.4; cursor: not-allowed; }
.acts-note {
  font-family: var(--cr-mono); font-size: 9.5px; color: var(--cr-dim); }

.omitted {
  border: 1px dashed var(--cr-amb);
  color: var(--cr-amb);
  padding: 7px 10px;
  font-size: 10.5px;
  line-height: 1.5;
}

.crew-f { padding: 10px 16px 16px; border-top: 1px solid var(--cr-line); display: grid; gap: 8px; }

.err {
  border: 1px solid var(--cr-red);
  background: rgba(255, 77, 109, 0.1);
  color: var(--cr-tx);
  font-size: 11px;
  padding: 7px 10px;
  line-height: 1.5;
}

.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chips button {
  background: transparent;
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  font-size: 11px;
  padding: 4px 9px;
  cursor: pointer;
}
.chips button:hover:not(:disabled) { border-color: var(--cr-vio); }
.chips button:disabled { opacity: 0.35; cursor: not-allowed; }

.in { display: grid; grid-template-columns: 1fr 62px; gap: 8px; }
.in input {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  padding: 9px 11px;
  font-size: 16px;   /* iOS 자동 줌 방지 */
  min-width: 0;
}
.in input:focus { outline: 1px solid var(--cr-vio); }
.in input:disabled { opacity: 0.5; }
.in button {
  font-family: var(--cr-mono);
  background: var(--cr-grn);
  border: 0;
  color: #05130a;
  font-weight: 700;
  letter-spacing: 0.14em;
  font-size: 12px;
  cursor: pointer;
}
.in button:disabled { background: var(--cr-dim); color: rgba(255, 255, 255, 0.6); cursor: not-allowed; }

.mode {
  font-family: var(--cr-mono); font-size: 9px; letter-spacing: 0.14em; color: var(--cr-dim); text-align: right; }

@media (max-width: 1100px) {
  .crew { border-left: 0; border-top: 1px solid var(--cr-line); height: auto; }
  .thread { max-height: min(560px, 55dvh); }
}

@media (max-width: 720px) {
  /* 카드를 가로 줄로 — 세로로 쌓으면 스크롤만 길어진다 */
  .cards { grid-template-columns: 1fr; gap: 6px; }
  .card {
    min-height: 0;
    display: flex;
    align-items: baseline;
    gap: 8px;
    text-align: left;
    padding: 9px 34px 9px 10px;
  }
  .card b { margin-bottom: 0; flex: none; }
  .card small { flex: 1; }
  .card .st { top: 50%; transform: translateY(-50%); }
  .thread { max-height: none; }
}
</style>
