<template>
  <section class="composer">
    <button type="button" class="toggle" @click="open = !open">
      <span>{{ open ? '▾' : '▸' }} 판정 기록 행 만들기</span>
      <em v-if="!open">붙여넣을 표 행을 만든다 — 파일은 사람이 고친다</em>
    </button>

    <div v-if="open" class="body">
      <div class="row">
        <label>
          <span>안건</span>
          <select v-model="title">
            <option value="">— 고르기 —</option>
            <option v-for="e in entries" :key="e.id" :value="e.title">
              {{ e.title }}{{ e.overdue ? ' (기한 초과)' : '' }}
            </option>
          </select>
        </label>
        <label class="narrow">
          <span>판정일</span>
          <input v-model="decidedOn" type="date" />
        </label>
      </div>

      <div class="row">
        <label>
          <span>결정</span>
          <select v-model="decision">
            <option value="">— 고르기 —</option>
            <option v-for="d in DECISIONS" :key="d" :value="d">{{ d }}</option>
          </select>
        </label>
        <label class="narrow">
          <span>재판정일 / 다음 액션</span>
          <input v-model="nextAction" type="text" placeholder="보류·불가면 필수" />
        </label>
      </div>

      <label class="full">
        <span>근거 (수치·n 포함)</span>
        <input v-model="evidence" type="text" placeholder="예: HIGH_VOL 41%(distinctDays=12) vs NORMAL 44%(n=31)" />
      </label>

      <!-- 경고는 막지 않고 알려준다 — 규칙을 알면서 넘길 상황도 있다 -->
      <ul v-if="result.warnings.length" class="warn">
        <li v-for="w in result.warnings" :key="w">{{ w }}</li>
      </ul>

      <div v-if="result.valid" class="out">
        <code>{{ result.row }}</code>
        <button type="button" class="copy" @click="copy">
          {{ copied ? '복사됨' : '복사' }}
        </button>
      </div>

      <p class="hint">
        복사한 뒤 <code>docs/SCHEDULE_DECISIONS.md</code> 의 "판정 기록" 표에 붙여넣고 커밋한다.
        관제실은 파일을 고치지 않는다 — 기록은 사람의 결정이다.
      </p>
    </div>
  </section>
</template>

<script setup>
/**
 * 판정 기록 행 작성기.
 *
 * 이 화면이 생긴 이유가 "판정 8건인데 기록 0건"이었다. 보여주기만 하고 기록을 쉽게 만들어주지
 * 않으면 여전히 0건이다 — 이 작성기가 그 마지막 한 걸음이다.
 *
 * ⚠ **파일을 고치지 않는다.** 붙여넣을 텍스트만 만든다(CLAUDE.md §7 읽기 전용).
 * 대신 문서에 적힌 규칙(결정 5종 · 근거에 n 필수 · 보류면 재판정일 필수)을 폼이 상기시킨다.
 */
import { computed, ref, watch } from 'vue'
import { DECISIONS, buildDecisionRow } from '../../utils/controlRoomFormat'

const props = defineProps({
  entries: { type: Array, default: () => [] },
  today: { type: String, default: '' },
  /** 밖에서 안건을 지정해 열 때(캘린더·OVERDUE 칩에서 진입) */
  presetTitle: { type: String, default: '' }
})

const open = ref(false)
const title = ref('')
const decision = ref('')
const evidence = ref('')
const nextAction = ref('')
const decidedOn = ref('')
const copied = ref(false)

watch(
  () => props.today,
  (v) => {
    if (v && !decidedOn.value) decidedOn.value = v
  },
  { immediate: true }
)

watch(
  () => props.presetTitle,
  (v) => {
    if (!v) return
    title.value = v
    open.value = true
  }
)

const result = computed(() =>
  buildDecisionRow({
    decidedOn: decidedOn.value,
    title: title.value,
    decision: decision.value,
    evidence: evidence.value,
    nextAction: nextAction.value
  })
)

async function copy() {
  try {
    await navigator.clipboard.writeText(result.value.row)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // 클립보드 권한이 없으면 사용자가 직접 긁어 복사하면 된다 — 행은 화면에 그대로 있다.
    copied.value = false
  }
}
</script>

<style scoped>
.composer { border-top: 1px solid var(--cr-line); margin-top: 12px; padding-top: 10px; }

.toggle {
  display: flex;
  align-items: baseline;
  gap: 10px;
  width: 100%;
  background: none;
  border: 0;
  color: var(--cr-mut);
  font-family: var(--cr-mono);
  font-size: 11px;
  letter-spacing: 0.12em;
  text-align: left;
  cursor: pointer;
  padding: 2px 0;
}
.toggle:hover { color: var(--cr-tx); }
.toggle em { font-style: normal; font-family: inherit; font-size: 10px; color: var(--cr-dim); letter-spacing: 0; }

.body { margin-top: 10px; display: grid; gap: 8px; }

.row { display: grid; grid-template-columns: 1fr 200px; gap: 8px; }
label { display: grid; gap: 3px; min-width: 0; }
label.full { display: grid; }
label span {
  font-family: var(--cr-mono);
  font-size: 9.5px;
  letter-spacing: 0.1em;
  color: var(--cr-dim);
}
select,
input {
  background: var(--cr-panel2);
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  padding: 6px 8px;
  font-size: 12px;
  min-width: 0;
}
select:focus,
input:focus { outline: 1px solid var(--cr-vio); }

.warn {
  margin: 0;
  padding: 7px 10px 7px 24px;
  border: 1px solid rgba(255, 180, 58, 0.5);
  background: rgba(255, 180, 58, 0.07);
  color: var(--cr-amb);
  font-size: 11px;
  line-height: 1.5;
}

.out { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: start; }
.out code {
  font-family: var(--cr-mono);
  font-size: 11px;
  line-height: 1.5;
  color: var(--cr-tx);
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid var(--cr-line);
  padding: 7px 9px;
  word-break: break-all;
}
.copy {
  background: transparent;
  border: 1px solid var(--cr-grn);
  color: var(--cr-grn);
  font-family: var(--cr-mono);
  font-size: 11px;
  padding: 6px 12px;
  cursor: pointer;
  white-space: nowrap;
}
.copy:hover { background: rgba(61, 255, 138, 0.15); }

.hint { font-size: 10px; color: var(--cr-dim); line-height: 1.55; margin: 0; }
.hint code { font-family: var(--cr-mono); color: var(--cr-mut); }

@media (max-width: 720px) {
  .row { grid-template-columns: 1fr; }
}
</style>
