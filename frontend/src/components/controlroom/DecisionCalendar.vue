<template>
  <section class="panel">
    <div class="ph">
      <b>DECISION MAP · {{ monthLabel }}</b>
      <span>날짜를 누르면 에렌에게 그 판정 요약을 요청한다</span>
    </div>

    <NoData v-if="!calendar || !calendar.dataAvailable" reason="판정 캘린더 YAML 블록을 읽지 못함" />

    <template v-else>
      <!-- OVERDUE = due 가 지났는데 pending/deferred -->
      <div v-if="overdue.length" class="overdue">
        <b>OVERDUE {{ overdue.length }}</b>
        판정 기한이 지난 안건
        <div class="chips">
          <button
            v-for="e in overdue.slice(0, 4)"
            :key="e.id"
            type="button"
            :title="dueLabel(e)"
            @click="$emit('ask', `${e.title} 판정이 ${e.due} 기한을 넘겼다. 지금 상태로 갈 수 있는지 정리해`)"
          >
            {{ e.title }}
          </button>
          <span v-if="overdue.length > 4" class="more">+{{ overdue.length - 4 }}</span>
        </div>
      </div>

      <!-- 조건 트리거 = 날짜가 판정 근거가 아닌 안건. 판정일과 시각적으로 분리한다 -->
      <div v-if="conditionWaiting.length" class="cond-row">
        <b>조건 대기 {{ conditionWaiting.length }}</b>
        <span
          v-for="e in conditionWaiting"
          :key="e.id"
          class="cond-item"
          :title="`확인일 ${e.due} — 실제 트리거는 조건`"
        >
          {{ e.title }} <em>({{ e.trigger }})</em>
        </span>
      </div>

      <div class="cal">
        <div v-for="d in DOW_LABELS" :key="d" class="dow">{{ d }}</div>
        <template v-for="(cell, idx) in grid" :key="idx">
          <div v-if="cell.pad" class="d pad"></div>
          <button
            v-else
            type="button"
            class="d"
            :class="cellClass(cell)"
            :title="cellTitle(cell)"
            @click="$emit('ask', instructionForDate(cell))"
          >
            {{ String(cell.day).padStart(2, '0') }}
            <span v-if="cellLabel(cell)" class="t">{{ cellLabel(cell) }}</span>
          </button>
        </template>
      </div>

      <div class="legend">
        <span><i class="lg-vio"></i>판정일</span>
        <span><i class="lg-red"></i>기한 초과 · 마일스톤</span>
        <span><i class="lg-amb"></i>조건 대기</span>
        <span><i class="lg-grn"></i>주간 피드백</span>
        <span><i class="lg-mag"></i>오늘 {{ today }}</span>
        <span v-if="nextLabel" class="next">NEXT · {{ nextLabel }}</span>
      </div>

      <p class="foot">
        주간 피드백은 판정이 아니라 크론(일 18:00)에서 유도한다.
        지난 주차는 실제 리포트 존재 여부로 표시하며, 조회 창 밖이라 알 수 없으면
        <em>실행 여부 불명</em>으로 둔다(미실행으로 단정하지 않는다).
      </p>
    </template>
  </section>
</template>

<script setup>
/**
 * 판정 캘린더.
 *
 * 표시 규칙 3가지가 §4c 와 직결된다.
 *  ① 조건 트리거 안건은 날짜가 판정 근거가 아니므로 "확인일"로 따로 묶는다.
 *  ② milestone 은 판정이 아니므로 라벨에 명시한다(미판정 집계에도 안 들어간다).
 *  ③ 주간 피드백의 MISSED 와 UNKNOWN 을 같은 말로 쓰지 않는다.
 */
import { computed } from 'vue'
import NoData from './NoData.vue'
import {
  DOW_LABELS,
  buildCalendarGrid,
  cellClass,
  cellLabel,
  dueLabel,
  instructionForDate,
  ddayLabel,
  WEEKLY_LABELS
} from '../../utils/controlRoomFormat'

const props = defineProps({
  calendar: { type: Object, default: null },
  month: { type: String, default: '' },
  today: { type: String, default: '' }
})

defineEmits(['ask'])

const monthLabel = computed(() => (props.month || '').replace('-', '.'))
const overdue = computed(() => props.calendar?.overdue ?? [])
const conditionWaiting = computed(() => props.calendar?.conditionWaiting ?? [])

const grid = computed(() =>
  buildCalendarGrid(
    props.month,
    props.calendar?.entries ?? [],
    props.calendar?.weeklyFeedback ?? [],
    props.today
  )
)

const nextLabel = computed(() => {
  const next = props.calendar?.nextDue
  if (!next) return null
  const dday = ddayLabel(props.calendar?.dDay)
  return dday ? `${next} (${dday})` : next
})

function cellTitle(cell) {
  if (cell.entries.length) {
    return cell.entries
      .map((e) => `${dueLabel(e)} · ${e.title} [${e.status}]${e.result ? ` — ${e.result}` : ''}`)
      .join('\n')
  }
  if (cell.weekly) return WEEKLY_LABELS[cell.weekly] || cell.weekly
  return cell.date
}
</script>

<style scoped>
.panel {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  padding: 17px;
  min-width: 0;
}

.ph {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 12px;
  gap: 10px;
}
.ph b { font-size: 12px; letter-spacing: 0.2em; text-transform: uppercase; }
.ph span { font-size: 11px; color: var(--cr-mut); }

.overdue {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  background: rgba(255, 77, 109, 0.08);
  border: 1px solid rgba(255, 77, 109, 0.4);
  padding: 8px 12px;
  margin-bottom: 10px;
  font-size: 12px;
}
.overdue b { color: var(--cr-red); }
.overdue .chips { margin-left: auto; display: flex; gap: 6px; flex-wrap: wrap; }
.overdue .chips button {
    font-size: 10px;
  border: 1px solid var(--cr-red);
  color: var(--cr-red);
  background: transparent;
  padding: 2px 6px;
  cursor: pointer;
}
.overdue .chips button:hover { background: rgba(255, 77, 109, 0.18); }
.overdue .more { font-size: 10px; color: var(--cr-red); }

.cond-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  border: 1px solid rgba(255, 180, 58, 0.4);
  background: rgba(255, 180, 58, 0.06);
  padding: 7px 12px;
  margin-bottom: 10px;
  font-size: 11.5px;
}
.cond-row b { color: var(--cr-amb); }
.cond-item { color: var(--cr-tx); }
.cond-item em { color: var(--cr-mut); font-style: normal; }

.cal { display: grid; grid-template-columns: repeat(7, 1fr); gap: 3px; }
.dow {
    font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--cr-mut);
  text-align: center;
  padding: 6px 0;
}
.dow:first-child { color: var(--cr-mag); }

.d {
  aspect-ratio: 1.25;
  background: var(--cr-panel2);
  border: 1px solid transparent;
  position: relative;
  padding: 6px;
    font-size: 11px;
  color: var(--cr-dim);
  cursor: pointer;
  text-align: left;
  width: 100%;
}
.d.pad { background: transparent; cursor: default; }
.d.ev {
  color: var(--cr-vio);
  border-color: var(--cr-vio);
  background: rgba(155, 77, 255, 0.12);
}
.d.ev.blk { color: var(--cr-red); border-color: var(--cr-red); background: rgba(255, 77, 109, 0.1); }
.d.ev.cond { color: var(--cr-amb); border-color: var(--cr-amb); background: rgba(255, 180, 58, 0.08); }
.d.ev.grn { color: var(--cr-grn); border-color: var(--cr-grn); background: rgba(61, 255, 138, 0.08); }
.d.today { box-shadow: 0 0 0 1px var(--cr-mag); border-color: var(--cr-mag); }
.d:hover:not(.pad) { filter: brightness(1.3); }

.d .t {
  position: absolute;
  left: 6px;
  bottom: 4px;
  right: 6px;
  font-size: 10px;
  line-height: 1.3;
  color: inherit;
  opacity: 0.9;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.legend {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 10px;
    font-size: 10px;
  color: var(--cr-mut);
}
.legend i {
  display: inline-block;
  width: 7px;
  height: 7px;
  transform: rotate(45deg);
  margin-right: 5px;
}
.lg-vio { background: var(--cr-vio); }
.lg-red { background: var(--cr-red); }
.lg-amb { background: var(--cr-amb); }
.lg-grn { background: var(--cr-grn); }
.lg-mag { background: var(--cr-mag); }
.legend .next { margin-left: auto; }

.foot {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.55;
  color: var(--cr-dim);
}
.foot em { color: var(--cr-amb); font-style: normal; }
</style>
