<template>
  <section class="crew">
    <div class="crew-h">
      <b>CREW</b>
      <span v-if="crew && crew.enabled" class="on">ONLINE</span>
      <span v-else class="off">OFFLINE</span>
    </div>

    <!-- 크루를 못 쓰는 상태는 버튼만 죽이지 않고 이유를 반드시 보여준다 -->
    <div v-if="crew && !crew.enabled" class="crew-disabled">
      <b>크루 비활성</b>
      <p>{{ crew.disabledReason || '사유 미상' }}</p>
      <!--
        키/모델을 고친 뒤 컨테이너 재시작 없이 재검증한다. 기동 시 1회만 확인하던 구조에선
        키 오타 한 번에 재시작 왕복이 필요했다.
      -->
      <button type="button" class="verify" :disabled="verifying" @click="$emit('verify')">
        {{ verifying ? '확인 중…' : '모델 재확인' }}
      </button>
    </div>

    <div class="cards">
      <div v-for="agent in AGENTS" :key="agent.key" class="card" :class="[agent.key, { busy: busyAgent === agent.key }]">
        <span class="st">{{ busyAgent === agent.key ? busyPhase : 'IDLE' }}</span>
        <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
          <template v-if="agent.key === 'eren'">
            <circle cx="24" cy="18" r="9" />
            <path d="M8 42c2-9 8-13 16-13s14 4 16 13" />
            <path d="M18 16h12M20 20h8" class="accent" />
          </template>
          <template v-else-if="agent.key === 'scout'">
            <circle cx="21" cy="21" r="11" />
            <path d="M29 29l10 10" />
            <path d="M15 21h12M21 15v12" class="accent" />
          </template>
          <template v-else>
            <path d="M24 6l14 5v12c0 9-6 15-14 19-8-4-14-10-14-19V11z" />
            <path d="M17 24l5 5 9-10" />
          </template>
        </svg>
        <b>{{ agent.name }}</b>
        <small>{{ agent.role }}</small>
      </div>
    </div>

    <div ref="threadEl" class="thread">
      <p v-if="!session" class="thread-empty">
        아래에 지시를 적거나 캘린더 날짜를 누르면 크루가 5턴으로 검토한다.<br />
        결론은 <b>제안</b>이고 실행은 사람이 한다 — 크루는 아무것도 바꾸지 못한다.
      </p>

      <template v-else>
        <div v-for="msg in session.messages" :key="msg.turnNo" class="msg" :class="agentClass(msg.agent)">
          <div v-if="msg.agent !== 'OPERATOR'" class="av" aria-hidden="true">
            <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2">
              <template v-if="msg.agent === 'EREN'">
                <circle cx="24" cy="18" r="9" /><path d="M8 42c2-9 8-13 16-13s14 4 16 13" />
              </template>
              <template v-else-if="msg.agent === 'SCOUT'">
                <circle cx="21" cy="21" r="11" /><path d="M29 29l10 10" />
              </template>
              <template v-else>
                <path d="M24 6l14 5v12c0 9-6 15-14 19-8-4-14-10-14-19V11z" />
              </template>
            </svg>
          </div>
          <div class="bd">
            <div class="nm">
              {{ msg.displayName }}
              <span v-if="msg.addressedTo" class="to">{{ msg.addressedTo }}</span>
              <span v-if="msg.truncated" class="trunc" :title="`max_tokens=${msg.maxTokens} 도달`">
                응답 잘림
              </span>
            </div>
            <div class="tx" v-html="renderContent(msg.content)"></div>

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

        <div v-if="session.status === 'RUNNING'" class="typing">
          {{ busyName }} 작성 중 ({{ doneTurns }}/{{ session.totalTurns }})
        </div>

        <!-- 실패는 조용히 멈추지 않고 사유를 그대로 노출한다. 자동 재시도 없음. -->
        <div v-if="session.status === 'FAILED'" class="failed">
          <b>크루 세션 실패</b>
          <p>{{ session.failureReason || '사유 미상' }}</p>
          <p class="hint">자동 재시도하지 않는다 — 필요하면 지시를 다시 보내라.</p>
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

      <div class="mode" :class="modeClass">{{ modeText }}</div>
    </div>
  </section>
</template>

<script setup>
/**
 * CREW 패널 — 에렌 / SCOUT / FIREWALL 이 5턴으로 초안→검토→반영→결론을 만든다.
 *
 * 화면이 지켜야 할 것 3가지:
 *  ① 크루가 비활성이면 버튼만 죽이지 말고 **사유를 보여준다**(키 없음/모델 없음/상한 도달).
 *  ② 세션 실패는 조용히 끝내지 않고 failureReason 을 그대로 노출한다. 자동 재시도는 없다.
 *  ③ 액션 버튼은 **새 지시를 보내는 것뿐**이고 무엇도 실행하지 않는다 — 문구로도 못박는다.
 */
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({
  crew: { type: Object, default: null },
  session: { type: Object, default: null },
  errorMessage: { type: String, default: null },
  sending: { type: Boolean, default: false },
  verifying: { type: Boolean, default: false }
})

const emit = defineEmits(['ask', 'verify'])

const draft = ref('')
const threadEl = ref(null)

const AGENTS = [
  { key: 'eren', name: '에렌', role: '총괄 · 분배/결론', agent: 'EREN' },
  { key: 'scout', name: 'SCOUT', role: '분석 · 초안', agent: 'SCOUT' },
  { key: 'firewall', name: 'FIREWALL', role: '검증 · 불변식', agent: 'FIREWALL' }
]

/** 백엔드 CrewPrompts.Step 과 같은 순서 — 진행 표시에만 쓴다. */
const STEPS = [
  { agent: 'EREN', key: 'eren', name: '에렌', phase: 'ROUTING' },
  { agent: 'SCOUT', key: 'scout', name: 'SCOUT', phase: 'DRAFT' },
  { agent: 'FIREWALL', key: 'firewall', name: 'FIREWALL', phase: 'REVIEW' },
  { agent: 'SCOUT', key: 'scout', name: 'SCOUT', phase: 'REVISE' },
  { agent: 'EREN', key: 'eren', name: '에렌', phase: 'CLOSING' }
]

const QUICK_CHIPS = [
  '밀린 판정 정리해',
  'FLAGGED critical 부터 처리 순서 잡아',
  '다음 판정일 준비 상태 점검해',
  '지금 봇 게이트 상태 해석해줘'
]

const doneTurns = computed(
  () => (props.session?.messages ?? []).filter((m) => m.agent !== 'OPERATOR').length
)

const currentStep = computed(() => {
  if (props.session?.status !== 'RUNNING') return null
  return STEPS[doneTurns.value] ?? null
})

const busyAgent = computed(() => currentStep.value?.key ?? null)
const busyPhase = computed(() => currentStep.value?.phase ?? 'IDLE')
const busyName = computed(() => currentStep.value?.name ?? '크루')

const canSend = computed(
  () => !!props.crew?.enabled && !props.sending && props.session?.status !== 'RUNNING'
)

const modeClass = computed(() => {
  if (!props.crew?.enabled) return 'off'
  return props.session?.status === 'RUNNING' ? 'busy' : 'live'
})

const modeText = computed(() => {
  if (!props.crew) return 'MODE · —'
  if (!props.crew.enabled) return 'MODE · DISABLED'
  const used = `${props.crew.usedToday}/${props.crew.dailyLimit || '∞'}`
  if (props.session?.status === 'RUNNING') return `MODE · RUNNING · 오늘 ${used}`
  return `MODE · ${props.crew.model} · 5턴 · 오늘 ${used}`
})

function agentClass(agent) {
  if (agent === 'OPERATOR') return 'user'
  if (agent === 'EREN') return 'eren'
  if (agent === 'SCOUT') return 'scout'
  return 'firewall'
}

function isClosing(msg) {
  return msg.phase === 'CLOSING'
}

/**
 * FIREWALL 의 [승인]/[조건부]/[반려] 첫 줄만 배지로 바꾼다.
 * 그 외 텍스트는 이스케이프해서 그대로 둔다 — 모델 출력을 HTML 로 신뢰하지 않는다.
 */
function renderContent(text) {
  const escaped = escapeHtml(text || '')
  return escaped.replace(
    /^\[(승인|조건부|반려)\]/m,
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
  grid-template-rows: auto auto auto 1fr auto;
  height: 100%;
  min-height: 0;
}

.crew-h {
  display: flex;
  justify-content: space-between;
  padding: 16px 16px 10px;
  align-items: center;
}
.crew-h b { font-family: var(--cr-mono); letter-spacing: 0.24em; font-size: 12px; }
.crew-h .on { font-family: var(--cr-mono); font-size: 9px; color: var(--cr-grn); letter-spacing: 0.16em; }
.crew-h .on::before { content: '● '; }
.crew-h .off { font-family: var(--cr-mono); font-size: 9px; color: var(--cr-amb); letter-spacing: 0.16em; }
.crew-h .off::before { content: '○ '; }

.crew-disabled {
  margin: 0 16px 10px;
  border: 1px dashed var(--cr-amb);
  color: var(--cr-amb);
  padding: 8px 10px;
}
.crew-disabled b { font-family: var(--cr-mono); font-size: 11px; letter-spacing: 0.12em; }
.crew-disabled p { font-size: 10.5px; line-height: 1.5; margin-top: 3px; }
.crew-disabled .verify {
  margin-top: 7px;
  background: transparent;
  border: 1px solid var(--cr-amb);
  color: var(--cr-amb);
  font-size: 11px;
  padding: 4px 9px;
  cursor: pointer;
}
.crew-disabled .verify:hover:not(:disabled) { background: rgba(255, 180, 58, 0.15); }
.crew-disabled .verify:disabled { opacity: 0.5; cursor: wait; }

.cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 0 16px 12px; }
.card {
  border: 1px solid var(--cr-line);
  padding: 12px 6px 8px;
  text-align: center;
  position: relative;
  min-height: 112px;
  background: rgba(0, 0, 0, 0.25);
  transition: border-color 0.25s, box-shadow 0.25s;
}
.card svg { width: 44px; height: 44px; display: block; margin: 0 auto 6px; }
.card b { display: block; font-family: var(--cr-mono); font-size: 11px; letter-spacing: 0.14em; }
.card small { font-size: 10px; color: var(--cr-mut); }
.card.eren { color: var(--cr-vio); }
.card.scout { color: var(--cr-cyn); }
.card.firewall { color: var(--cr-grn); }
.card.eren b { color: var(--cr-vio); }
.card.scout b { color: var(--cr-cyn); }
.card.firewall b { color: var(--cr-grn); }
.card .accent { stroke: var(--cr-mag); }
.card.busy { border-color: currentColor; }
.card.eren.busy { box-shadow: 0 0 16px rgba(155, 77, 255, 0.4); }
.card.scout.busy { box-shadow: 0 0 16px rgba(56, 220, 255, 0.35); }
.card.firewall.busy { box-shadow: 0 0 16px rgba(61, 255, 138, 0.35); }
.card .st {
  position: absolute;
  top: 6px;
  right: 6px;
  font-family: var(--cr-mono);
  font-size: 8px;
  color: var(--cr-mut);
  letter-spacing: 0.1em;
}
.card.busy .st { color: inherit; }

.thread {
  overflow: auto;
  padding: 4px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}
.thread-empty { font-size: 11.5px; color: var(--cr-mut); line-height: 1.7; }
.thread-empty b { color: var(--cr-tx); }

.msg { display: grid; grid-template-columns: 34px 1fr; gap: 8px; align-items: start; }
.msg.user { grid-template-columns: 1fr; }
.msg .av {
  width: 34px;
  height: 34px;
  border: 1px solid var(--cr-line);
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.3);
}
.msg .av svg { width: 22px; height: 22px; }
.msg.eren .av { color: var(--cr-vio); }
.msg.scout .av { color: var(--cr-cyn); }
.msg.firewall .av { color: var(--cr-grn); }

.msg .bd {
  border-left: 2px solid var(--cr-vio);
  padding: 6px 10px 8px;
  background: rgba(255, 255, 255, 0.025);
  min-width: 0;
}
.msg.scout .bd { border-left-color: var(--cr-cyn); }
.msg.firewall .bd { border-left-color: var(--cr-grn); }
.msg.user .bd { border-left-color: var(--cr-mag); background: rgba(255, 47, 166, 0.06); }

.msg .nm {
  font-family: var(--cr-mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  margin-bottom: 4px;
  display: flex;
  gap: 8px;
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
  color: var(--cr-amb);
  border: 1px solid var(--cr-amb);
  padding: 0 4px;
  letter-spacing: 0.06em;
  font-size: 9px;
}

.msg .tx { font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.msg .tx :deep(.tag) {
  display: inline-block;
  font-family: var(--cr-mono);
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
  padding: 5px 9px;
  cursor: pointer;
}
.acts button:hover:not(:disabled) { background: rgba(155, 77, 255, 0.2); }
.acts button:disabled { opacity: 0.4; cursor: not-allowed; }
.acts-note { font-size: 9.5px; color: var(--cr-dim); }

.typing {
  font-family: var(--cr-mono);
  font-size: 10px;
  color: var(--cr-mut);
  letter-spacing: 0.14em;
  padding: 2px 0 0 42px;
}

.failed {
  border: 1px solid var(--cr-red);
  background: rgba(255, 77, 109, 0.08);
  padding: 8px 10px;
}
.failed b { font-family: var(--cr-mono); font-size: 11px; color: var(--cr-red); letter-spacing: 0.1em; }
.failed p { font-size: 11px; color: var(--cr-tx); line-height: 1.5; margin-top: 3px; }
.failed .hint { color: var(--cr-mut); }

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
  padding: 5px 9px;
  cursor: pointer;
}
.chips button:hover:not(:disabled) { border-color: var(--cr-vio); }
.chips button:disabled { opacity: 0.35; cursor: not-allowed; }

.in { display: grid; grid-template-columns: 1fr 64px; gap: 8px; }
.in input {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  padding: 10px 12px;
  font-size: 12.5px;
  min-width: 0;
}
.in input:focus { outline: 1px solid var(--cr-vio); }
.in input:disabled { opacity: 0.5; }
.in button {
  background: var(--cr-grn);
  border: 0;
  color: #05130a;
  font-family: var(--cr-mono);
  font-weight: 700;
  letter-spacing: 0.14em;
  cursor: pointer;
}
.in button:disabled { opacity: 0.4; cursor: not-allowed; }

.mode { font-family: var(--cr-mono); font-size: 9px; letter-spacing: 0.14em; color: var(--cr-mut); text-align: right; }
.mode.live { color: var(--cr-grn); }
.mode.busy { color: var(--cr-cyn); }
.mode.off { color: var(--cr-amb); }
</style>
