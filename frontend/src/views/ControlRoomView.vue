<template>
  <div class="control-room">
    <main>
      <!--
        관제실은 GNB 없는 단독 라우트다(§7 — 종목 화면이 아니라 운영 콘솔이라 4탭에 자리가 없다).
        그래서 나가는 길을 화면이 직접 줘야 한다. 예전엔 '주식 허브' 버튼 하나뿐이라
        관제실에서 뭔가 이상한 걸 봤을 때 그 근거 화면으로 바로 못 갔다.
        목적지는 KPI 가 가리키는 곳으로 고른다 — 후보/게이트/재무입력층의 원본 화면.
      -->
      <nav class="topnav" aria-label="화면 이동">
        <button type="button" class="back" @click="goHub()">← 주식 허브</button>
        <span class="topnav-sep" aria-hidden="true"></span>
        <button
          v-for="l in navLinks"
          :key="l.key"
          type="button"
          class="navlink"
          :title="l.why"
          @click="l.path ? goPath(l.path) : goHub(l.tab)"
        >{{ l.label }}</button>
      </nav>

      <div class="head">
        <div class="head-l">
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

      <!--
        데이터 이상 점검 — FLAGGED 아래에 둔다. 둘 다 "문제 목록"이지만 출처가 다르다:
        FLAGGED 는 사람이 적고 사람이 지우고, 이쪽은 코드가 매 스냅샷마다 다시 판정한다.
        섞으면 "왜 안 지워지지 / 왜 사라졌지"가 헷갈려 따로 둔다.
        정상이면 '이상 없음' 한 줄이라 자리를 거의 안 먹는다.
      -->
      <AnomalyPanel :anomalies="snapshot?.anomalies" @ask="ask" />

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
      :sessions="sessions"
      :verifying="verifying"
      @ask="ask"
      @verify="verifyCrew"
      @select="selectSession"
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
import AnomalyPanel from '../components/controlroom/AnomalyPanel.vue'
import CrewPanel from '../components/controlroom/CrewPanel.vue'
import { controlRoomAPI } from '../utils/api'
import { ddayLabel, CONTROL_ROOM_NAV } from '../utils/controlRoomFormat'

const POLL_INTERVAL_MS = 1500

const router = useRouter()

const snapshot = ref(null)
const snapshotError = ref(null)
const loading = ref(false)

const session = ref(null)
const sessions = ref([])
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

/** 새로고침 후에도 진행 중이던 세션을 이어 보여준다. 이력 목록도 같은 응답에서 채운다. */
async function loadSessions({ selectLatest = false } = {}) {
  try {
    const res = await controlRoomAPI.getCrewSessions()
    const list = res.data?.data ?? []
    sessions.value = list
    if (!selectLatest || !list.length) return
    session.value = list[0]
    if (list[0].status === 'RUNNING') startPolling(list[0].id)
  } catch {
    // 이력 조회 실패는 치명적이지 않다 — 새 지시는 그대로 보낼 수 있다.
  }
}

/** 지난 세션 선택 — 이미 받아둔 목록에서 꺼낸다(추가 호출 없음). */
function selectSession(id) {
  const found = sessions.value.find((s) => s.id === id)
  if (!found) return
  stopPolling()
  session.value = found
  if (found.status === 'RUNNING') startPolling(found.id)
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
        // 완료 시 스냅샷(오늘 사용량)과 이력 목록을 함께 갱신한다.
        loadSnapshot()
        loadSessions()
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

/** 목적지 목록은 순수 모듈(CONTROL_ROOM_NAV)에 있다 — 허브 매핑과 대조하는 테스트 대상. */
const navLinks = CONTROL_ROOM_NAV

/** 탭 키는 허브의 mapLegacyTab 기준(today/market/discover/trade). 없으면 '오늘'로 간다. */
function goHub(tab) {
  router.push(tab ? { path: '/stock-dashboard', query: { tab } } : '/stock-dashboard')
}

function goPath(path) {
  router.push(path)
}

watch(month, loadSnapshot)

onMounted(async () => {
  await loadSnapshot()
  await loadSessions({ selectLatest: true })
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

  /*
   * 라벨·숫자용 모노. 앱 전역 폰트 스택(-apple-system, Segoe UI …)엔 한글 폰트가 없어
   * 윈도우에선 라틴(Segoe UI)과 한글(맑은 고딕)이 글자마다 갈라진다. 거기에 계기판용
   * 자간(0.14~0.24em)을 얹으면 글자가 벌어져 깨져 보인다(2026-08-25 실측).
   * 그래서 **라벨·숫자·배지에는 모노를 쓰고, 본문(대화·설명)만 앱 폰트를 상속**한다.
   */
  --cr-mono: 'JetBrains Mono', 'Cascadia Code', Consolas, 'D2Coding', 'Malgun Gothic', monospace;

  display: grid;
  /*
   * ⚠ minmax(0, 1fr) 이어야 한다. 맨 1fr 은 minmax(auto, 1fr) 이라 그리드 항목이
   * min-content 아래로 줄지 않는다 — SCHEDULE_DECISIONS 같은 안 끊기는 토큰이 한 칸을
   * 밀어내면 다른 칸이 글자 단위로 쪼개지고 내용이 밖으로 넘친다(2026-08-26 실측).
   */
  grid-template-columns: minmax(0, 1fr) 400px;
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
  font-family: var(--cr-mono);
  color: var(--cr-grn);
  font-size: 11px;
  letter-spacing: 0.2em;
  margin-bottom: 6px;
}
.cycle::before { content: '— '; color: var(--cr-mag); }

/* 화면 이동 바 — 관제실은 GNB 가 없어 여기가 유일한 출구다.
   본문보다 앞서 읽히면 안 되므로 톤을 낮추되, 못 찾을 만큼 죽이지는 않는다. */
.topnav {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.topnav-sep {
  width: 1px;
  height: 16px;
  background: var(--cr-line);
  margin: 0 4px;
}
.navlink {
  font-family: var(--cr-mono);
  background: transparent;
  border: 1px solid transparent;
  color: var(--cr-mut);
  font-size: 11.5px;
  letter-spacing: 0.04em;
  padding: 7px 11px;
  cursor: pointer;
  white-space: nowrap;
}
.navlink:hover { color: var(--cr-tx); border-color: var(--cr-line); }
.navlink:focus-visible { outline: 1px solid var(--cr-vio); outline-offset: 1px; }

.back {
  font-family: var(--cr-mono);
  background: transparent;
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  font-size: 12px;
  padding: 8px 12px;
  cursor: pointer;
  white-space: nowrap;
}
.back:hover { border-color: var(--cr-vio); }
.back:focus-visible { outline: 1px solid var(--cr-vio); outline-offset: 1px; }

.head-r { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.sel {
  font-family: var(--cr-mono);
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  padding: 8px 12px;
  font-size: 12px;
}
.refresh {
  font-family: var(--cr-mono);
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
  font-family: var(--cr-mono);
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

.row { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 14px; }

.foot-note {
  margin-top: 16px;
  font-size: 10.5px;
  color: var(--cr-dim);
  line-height: 1.6;
}
.foot-note b { color: var(--cr-mut); }
.foot-note .gen {
  font-family: var(--cr-mono); margin-left: 10px; }

/*
 * 반응형 4단계 — 1400 크루 폭↓ · 1100 크루 아래로 · 900 캘린더/FLAGGED 세로 · 720 모바일.
 * 단계를 촘촘히 두는 이유는 중간 폭에서 크루 패널이 먼저 찌그러지며 스레드가 못 읽게 되기 때문이다.
 */
@media (max-width: 1400px) {
  .control-room { grid-template-columns: minmax(0, 1fr) 350px; }
  .row { grid-template-columns: minmax(0, 1fr) 300px; }
}

@media (max-width: 1100px) {
  .control-room { grid-template-columns: minmax(0, 1fr); height: auto; overflow: auto; }
  .row { grid-template-columns: minmax(0, 1fr); }
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
