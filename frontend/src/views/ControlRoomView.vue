<template>
  <div class="control-room">
    <main>
      <div class="head">
        <div class="head-l">
          <!-- 목업의 좌측 사이드바는 대응 화면이 없어 제외했다. GNB 복귀 링크만 둔다. -->
          <button type="button" class="back" @click="goHub">← 주식 허브</button>
          <div>
            <div class="cycle">CYCLE {{ (snapshot?.month || '').replace('-', '.') }} // 판정 사이클</div>
            <h1>판정 대시보드</h1>
          </div>
        </div>
        <div class="head-r">
          <select v-model="month" class="sel" aria-label="판정 월 선택">
            <option v-for="opt in monthOptions" :key="opt" :value="opt">{{ opt.replace('-', '.') }} 판정분</option>
          </select>
          <div v-if="ddayText" class="dday">
            <b>{{ ddayText }}</b>
            <span>{{ snapshot.calendar.nextDue }} 예정 · 기한 초과 {{ overdueCount }}건</span>
          </div>
          <button type="button" class="refresh" :disabled="loading" @click="loadSnapshot">
            {{ loading ? '불러오는 중' : '새로고침' }}
          </button>
        </div>
      </div>

      <div v-if="snapshotError" class="load-err">
        스냅샷을 불러오지 못했다 — {{ snapshotError }}
      </div>

      <ControlRoomKpis :kpis="snapshot?.kpis" />

      <div class="row">
        <DecisionCalendar
          :calendar="snapshot?.calendar"
          :month="snapshot?.month || month"
          :today="snapshot?.today"
          @ask="ask"
        />
        <FlaggedPanel :flagged="snapshot?.flagged" @ask="ask" />
      </div>

      <p class="foot-note">
        관제실은 <b>읽기 전용</b> 레이어다. 여기서 봇·게이트·주문·설정을 바꾸는 경로는 없고,
        크루의 결론도 액션 <b>제안</b>일 뿐이다.
        <span v-if="snapshot?.generatedAt" class="gen">스냅샷 {{ snapshot.generatedAt.replace('T', ' ').slice(0, 19) }}</span>
      </p>
    </main>

    <CrewPanel
      :crew="snapshot?.crew"
      :session="session"
      :error-message="crewError"
      :sending="sending"
      :verifying="verifying"
      @ask="ask"
      @verify="verifyCrew"
    />
  </div>
</template>

<script setup>
/**
 * 판정 관제실 — 좌: KPI·판정 캘린더·FLAGGED / 우: AI 크루.
 *
 * ⚠ 팔레트는 `.control-room` 스코프에만 정의한다. 목업은 `:root` 에 토큰을 깔았는데 그대로 넣으면
 * 전역 `common.css` 의 다크 토큰(--bg-page 등)을 덮어써서 다른 화면이 깨진다.
 *
 * 크루 진행은 **폴링**이다. SSE 는 EventSource 가 Authorization 헤더를 못 실어 토큰을 쿼리
 * 파라미터로 넘기는 우회가 필요한데, 5턴짜리 단발 작업엔 그 위험을 감수할 이유가 없다.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ControlRoomKpis from '../components/controlroom/ControlRoomKpis.vue'
import DecisionCalendar from '../components/controlroom/DecisionCalendar.vue'
import FlaggedPanel from '../components/controlroom/FlaggedPanel.vue'
import CrewPanel from '../components/controlroom/CrewPanel.vue'
import { controlRoomAPI } from '../utils/api'
import { ddayLabel } from '../utils/controlRoomFormat'

const POLL_INTERVAL_MS = 1500

const router = useRouter()

const snapshot = ref(null)
const snapshotError = ref(null)
const loading = ref(false)

const session = ref(null)
const crewError = ref(null)
const sending = ref(false)
const verifying = ref(false)
let pollTimer = null

const month = ref(currentMonth())

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 이번 달 기준 앞뒤 2개월 — 판정은 몇 달 앞을 보는 일이라 미래 쪽을 조금 더 준다. */
const monthOptions = computed(() => {
  const now = new Date()
  const options = []
  for (let offset = -2; offset <= 3; offset++) {
    const d = new Date(now.getFullYear(), now.getMonth() + offset, 1)
    options.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }
  return options
})

const overdueCount = computed(() => snapshot.value?.calendar?.overdue?.length ?? 0)
const ddayText = computed(() => {
  if (!snapshot.value?.calendar?.nextDue) return null
  return ddayLabel(snapshot.value.calendar.dDay)
})

async function loadSnapshot() {
  loading.value = true
  snapshotError.value = null
  try {
    const res = await controlRoomAPI.getSnapshot(month.value)
    snapshot.value = res.data?.data ?? null
  } catch (e) {
    // §4c: 실패를 빈 화면으로 두지 않는다 — 왜 비었는지 화면에 남긴다.
    snapshotError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/** 새로고침 후에도 진행 중이던 세션을 이어 보여준다. */
async function restoreLatestSession() {
  try {
    const res = await controlRoomAPI.getCrewSessions()
    const list = res.data?.data ?? []
    if (!list.length) return
    session.value = list[0]
    if (list[0].status === 'RUNNING') startPolling(list[0].id)
  } catch {
    // 이력 복원 실패는 치명적이지 않다 — 새 지시는 그대로 보낼 수 있다.
  }
}

async function ask(instruction) {
  const text = (instruction || '').trim()
  if (!text || sending.value) return
  if (session.value?.status === 'RUNNING') return

  sending.value = true
  crewError.value = null
  try {
    const res = await controlRoomAPI.startCrewSession(text)
    session.value = res.data?.data ?? null
    if (session.value?.id) startPolling(session.value.id)
  } catch (e) {
    crewError.value = crewErrorText(e)
  } finally {
    sending.value = false
  }
}

/**
 * 모델 재확인 — 키/모델 설정을 고친 뒤 컨테이너 재시작 없이 크루 가용 상태를 갱신한다.
 * 실패해도 200 + disabledReason 으로 사유가 오므로, 그 사유를 스냅샷에 반영해 화면에 그대로 띄운다.
 */
async function verifyCrew() {
  if (verifying.value) return
  verifying.value = true
  crewError.value = null
  try {
    const res = await controlRoomAPI.verifyCrew()
    const data = res.data?.data
    if (data && !data.enabled) {
      // 여전히 비활성 — 사유가 바뀌었을 수 있으니 그대로 보여준다.
      crewError.value = `재확인 결과 여전히 비활성 — ${data.disabledReason || '사유 미상'}`
    }
    await loadSnapshot()
  } catch (e) {
    crewError.value = `재확인 실패 — ${errorText(e)}`
  } finally {
    verifying.value = false
  }
}

/** 폴링 연속 실패 허용치 — 블립 1번에 화면만 멈추는 것 방지(세션은 서버에서 계속 돈다). */
const POLL_MAX_FAILURES = 3

function startPolling(id) {
  stopPolling()
  let failures = 0
  pollTimer = setInterval(async () => {
    try {
      const res = await controlRoomAPI.getCrewSession(id)
      failures = 0
      const next = res.data?.data ?? null
      if (next) session.value = next
      if (!next || next.status !== 'RUNNING') {
        stopPolling()
        // 완료 시 스냅샷을 다시 읽어 오늘 사용량·상태를 갱신한다.
        loadSnapshot()
      }
    } catch (e) {
      // 일시 오류는 다음 주기에 재시도 — 연속 한계 도달 때만 멈추고 사유를 남긴다.
      failures += 1
      if (failures >= POLL_MAX_FAILURES) {
        stopPolling()
        crewError.value = `진행 상황을 ${POLL_MAX_FAILURES}회 연속 읽지 못했다 — ${errorText(e)}. `
          + '세션은 서버에서 계속 진행 중일 수 있다. 새로고침하면 이어서 보인다.'
      }
    }
  }, POLL_INTERVAL_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 상한/동시실행 거부를 일반 오류와 섞지 않는다 — 운영자가 대응을 달리해야 한다. */
function crewErrorText(e) {
  const status = e?.response?.status
  const message = e?.response?.data?.message
  if (status === 429) return `일일 상한 도달 — ${message || '오늘은 더 실행할 수 없다'}`
  if (status === 409) return message || '이미 실행 중인 크루 세션이 있다 (동시 1건)'
  if (status === 403) return '권한 없음 — 관제실은 ADMIN 전용이다'
  return `크루 세션을 시작하지 못했다 — ${errorText(e)}`
}

function errorText(e) {
  return e?.response?.data?.message || e?.message || '알 수 없는 오류'
}

function goHub() {
  router.push('/stock-dashboard')
}

watch(month, loadSnapshot)

onMounted(async () => {
  await loadSnapshot()
  await restoreLatestSession()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
/*
 * 목업 팔레트 — `.control-room` 스코프 한정(`:root` 금지).
 * 전역 common.css 가 이미 :root 에 --bg-page/--text-primary 등을 정의하고 있어,
 * 여기서 :root 를 쓰면 관제실을 연 순간 다른 화면 토큰까지 덮어쓴다.
 */
.control-room {
  --cr-bg: #0a0614;
  --cr-bg2: #120b22;
  --cr-panel: #160e2b;
  --cr-panel2: #1c1236;
  --cr-line: #2c1d52;
  --cr-mag: #ff2fa6;
  --cr-vio: #9b4dff;
  --cr-grn: #3dff8a;
  --cr-cyn: #38dcff;
  --cr-amb: #ffb43a;
  --cr-red: #ff4d6d;
  --cr-tx: #ebe4ff;
  --cr-mut: #8f81be;   /* 목업 #7e71ad 에서 올림 — 가독 */
  --cr-dim: #5b4f88;

  display: grid;
  grid-template-columns: 1fr 400px;
  height: 100vh;
  overflow: hidden;
  background: var(--cr-bg);
  color: var(--cr-tx);
  font-size: 14px;
}

/*
 * 폰트는 앱 기본을 그대로 상속한다. 목업은 JetBrains Mono 를 쓰지만 설치돼 있지 않은 기기에선
 * 폴백 폰트마다 자간이 달라져 오히려 들쭉날쭉해진다. "계기판" 인상은 폰트가 아니라
 * 대문자 + 자간(.eyebrow) 과 tabular-nums 로 낸다.
 *
 * 숫자는 자리폭 고정 — 폴링으로 값이 바뀌어도 칸이 밀리지 않는다.
 */
.control-room :is(.v, .s, .dday, .cal, .legend, .mode, .meta, .snap) {
  font-variant-numeric: tabular-nums;
}

main {
  overflow: auto;
  padding: 22px 26px;
  background:
    radial-gradient(1200px 400px at 70% -10%, rgba(155, 77, 255, 0.18), transparent 60%),
    linear-gradient(var(--cr-bg), var(--cr-bg));
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 18px;
  gap: 14px;
  flex-wrap: wrap;
}
.head-l { display: flex; align-items: flex-end; gap: 14px; }
.head h1 { font-size: 22px; font-weight: 700; letter-spacing: -0.01em; margin: 0; }
.cycle {
  color: var(--cr-grn);
  font-size: 11px;
  letter-spacing: 0.2em;
  margin-bottom: 6px;
}
.cycle::before { content: '— '; color: var(--cr-mag); }

.back {
  background: transparent;
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  font-size: 12px;
  padding: 8px 12px;
  cursor: pointer;
  white-space: nowrap;
}
.back:hover { border-color: var(--cr-vio); }

.head-r { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.sel {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  padding: 8px 12px;
  font-size: 12px;
}
.refresh {
  background: transparent;
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  font-size: 12px;
  padding: 8px 12px;
  cursor: pointer;
}
.refresh:hover:not(:disabled) { border-color: var(--cr-vio); }
.refresh:disabled { opacity: 0.5; cursor: wait; }

.dday {
  border: 1px solid var(--cr-red);
  color: var(--cr-red);
  padding: 8px 14px;
}
.dday b { font-size: 16px; margin-right: 8px; }
.dday span { font-size: 11px; color: #ff9db0; }

.load-err {
  border: 1px solid var(--cr-red);
  background: rgba(255, 77, 109, 0.1);
  padding: 9px 12px;
  margin-bottom: 14px;
  font-size: 12px;
}

.row { display: grid; grid-template-columns: 1fr 340px; gap: 14px; }

.foot-note {
  margin-top: 16px;
  font-size: 10.5px;
  color: var(--cr-dim);
  line-height: 1.6;
}
.foot-note b { color: var(--cr-mut); }
.foot-note .gen { margin-left: 10px; }

/*
 * 반응형 4단계 — 1400 크루 폭↓ · 1100 크루 아래로 · 900 캘린더/FLAGGED 세로 · 720 모바일.
 * 단계를 촘촘히 두는 이유는 중간 폭에서 크루 패널이 먼저 찌그러지며 스레드가 못 읽게 되기 때문이다.
 */
@media (max-width: 1400px) {
  .control-room { grid-template-columns: 1fr 350px; }
  .row { grid-template-columns: 1fr 300px; }
}

@media (max-width: 1100px) {
  .control-room { grid-template-columns: 1fr; height: auto; overflow: auto; }
  .row { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .control-room { font-size: 13px; }
  main { padding: 14px 14px 18px; }
  .head h1 { font-size: 20px; }
  .head-l { flex-wrap: wrap; gap: 10px; }
  .head-r { width: 100%; }
}

/* 움직임 최소화 설정 존중 — 깜빡이는 상태 표시가 접근성 문제가 되지 않게 */
@media (prefers-reduced-motion: reduce) {
  .control-room *,
  .control-room *::before,
  .control-room *::after {
    animation: none !important;
    transition: none !important;
  }
}
</style>
