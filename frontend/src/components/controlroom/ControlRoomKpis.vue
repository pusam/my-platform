<template>
  <div class="kpis">
    <!-- ① 종합판단 후보 -->
    <div class="kpi">
      <div class="eyebrow">종합판단 후보</div>
      <template v-if="candidates && candidates.dataAvailable">
        <div class="v">{{ candidates.total }}<small>종목</small></div>
        <div class="s">
          STRONG_BUY {{ candidates.strongBuy }} · BUY {{ candidates.buy }} · 관망·미채점 {{ candidates.watch }}
        </div>
      </template>
      <NoData v-else :reason="candidates && candidates.note" />
    </div>

    <!-- ② 봇 게이트 -->
    <div class="kpi" :class="{ alert: gatesHasClosed, ok: gatesAllOpen }">
      <div class="eyebrow">봇 게이트</div>
      <template v-if="gates && gates.dataAvailable">
        <div class="v">{{ gates.open }}<small>/{{ gates.total }}</small></div>
        <div class="bar">
          <i
            v-for="item in gates.items"
            :key="item.key"
            :class="gateStateClass(item.state)"
            :title="`${item.label}: ${item.state} — ${item.detail}`"
          ></i>
        </div>
        <div class="s gate-list">
          <span v-for="item in closedGates" :key="item.key" class="gate-closed">
            {{ item.label }} {{ item.state }}
          </span>
          <span v-if="closedGates.length === 0">전부 열림</span>
        </div>
      </template>
      <NoData v-else />
    </div>

    <!-- ③ 일일손실 서킷 (원 단위 — 자산 % 킬스위치와 별개 장치) -->
    <div class="kpi" :class="{ alert: breaker && breaker.trippedToday }">
      <div class="eyebrow">일일손실 서킷</div>
      <template v-if="breaker && breaker.dataAvailable">
        <div class="v" :class="{ small: pnlText === null }">
          <template v-if="pnlText !== null">{{ pnlText }}</template>
          <template v-else>조회 실패</template>
        </div>
        <div class="s">
          <template v-if="breaker.limitKrw !== null && breaker.limitKrw !== undefined">
            한도 -{{ breaker.limitKrw.toLocaleString('ko-KR') }}원
            <template v-if="headroomText"> · 여유 {{ headroomText }}</template>
          </template>
          <template v-else>한도 미설정</template>
          · {{ breaker.trippedToday ? '오늘 발동' : '미발동' }}
          <template v-if="breaker.mode"> · {{ breaker.mode }}</template>
        </div>
        <div v-if="breaker.note" class="s note">{{ breaker.note }}</div>
      </template>
      <NoData v-else :reason="breaker && breaker.note" />
    </div>

    <!-- ④ VKOSPI 레짐 -->
    <div class="kpi">
      <div class="eyebrow">VKOSPI 레짐</div>
      <template v-if="volRegime && volRegime.dataAvailable">
        <div class="v small">{{ volRegime.regime }}</div>
        <div class="s">게이트 mode={{ volRegime.gateMode }}</div>
      </template>
      <NoData v-else :reason="volRegime && volRegime.note" />
    </div>

    <!-- ⑤ 미판정 -->
    <div class="kpi" :class="{ alert: undecided && undecided.dataAvailable && undecided.count > 0 }">
      <div class="eyebrow">미판정</div>
      <template v-if="undecided && undecided.dataAvailable">
        <div class="v">{{ undecided.count }}<small>건</small></div>
        <div class="s">전체 안건 {{ undecided.rosterSize }}건 중 판정 기록 없음</div>
      </template>
      <NoData v-else reason="판정 기록 표를 읽지 못함" />
    </div>
  </div>
</template>

<script setup>
/**
 * 관제실 KPI 5종.
 *
 * ⚠ 여기서 수치를 다시 계산하지 않는다 — 전부 백엔드 스냅샷이 확정한 값이다.
 * dataAvailable=false 는 "0"이 아니라 NoData 로 렌더된다(§4c). 특히 일일손실 서킷은
 * 목업의 % 가 아니라 **원 단위**다(자산 대비 -3% 킬스위치는 별개 장치라 섞지 않는다).
 */
import { computed } from 'vue'
import NoData from './NoData.vue'
import { formatKrw, breakerHeadroom, gateStateClass } from '../../utils/controlRoomFormat'

const props = defineProps({
  kpis: { type: Object, default: null }
})

const candidates = computed(() => props.kpis?.candidates ?? null)
const gates = computed(() => props.kpis?.gates ?? null)
const breaker = computed(() => props.kpis?.lossBreaker ?? null)
const volRegime = computed(() => props.kpis?.volRegime ?? null)
const undecided = computed(() => props.kpis?.undecided ?? null)

const closedGates = computed(() =>
  (gates.value?.items ?? []).filter((i) => i.state !== 'OPEN')
)
const gatesHasClosed = computed(
  () => !!gates.value?.dataAvailable && closedGates.value.some((i) => i.state === 'CLOSED')
)
const gatesAllOpen = computed(
  () => !!gates.value?.dataAvailable && closedGates.value.length === 0
)

const pnlText = computed(() => formatKrw(breaker.value?.realizedPnlKrw))
const headroomText = computed(() => {
  const h = breakerHeadroom(breaker.value?.realizedPnlKrw, breaker.value?.limitKrw)
  return h === null ? null : formatKrw(h)
})
</script>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.kpi {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  padding: 14px 16px;
  position: relative;
  min-height: 108px;
}

/* 목업의 코너 마커 — 장식이지만 패널 경계를 읽기 쉽게 만든다 */
.kpi::before,
.kpi::after {
  content: '';
  position: absolute;
  width: 10px;
  height: 10px;
  border-color: var(--cr-vio);
  border-style: solid;
}
.kpi::before { top: -1px; left: -1px; border-width: 2px 0 0 2px; }
.kpi::after { bottom: -1px; right: -1px; border-width: 0 2px 2px 0; }

.kpi.alert { border-color: var(--cr-red); }
.kpi.alert::before,
.kpi.alert::after { border-color: var(--cr-red); }
.kpi.ok::before,
.kpi.ok::after { border-color: var(--cr-grn); }

.eyebrow {
  font-family: var(--cr-mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--cr-mut);
}

.v {
  font-family: var(--cr-mono);
  font-size: 26px;
  margin: 6px 0 4px;
  color: #fff;
  word-break: keep-all;
}
.v.small { font-size: 17px; padding-top: 5px; }
.v small { font-size: 12px; color: var(--cr-mut); margin-left: 4px; }
.kpi.alert .v { color: var(--cr-red); }

.s {
  font-size: 11px;
  color: var(--cr-mut);
  line-height: 1.5;
}
.s.note { color: var(--cr-amb); margin-top: 2px; }

.bar { display: flex; gap: 2px; margin-top: 8px; }
.bar i { flex: 1; height: 5px; background: var(--cr-dim); }
.bar i.g-open { background: var(--cr-grn); }
.bar i.g-closed { background: var(--cr-red); }
.bar i.g-unknown { background: var(--cr-amb); }

.gate-list { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; }
.gate-closed { color: var(--cr-red); }

@media (max-width: 1100px) {
  .kpis { grid-template-columns: repeat(2, 1fr); }
}
</style>
