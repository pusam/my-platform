<template>
  <div class="kpis">
    <!-- ① 종합판단 후보 -->
    <div class="kpi" :class="{ alert: candidatesSuspect }">
      <div class="eyebrow">종합판단 후보</div>
      <template v-if="candidates && candidates.dataAvailable">
        <div class="v">{{ candidates.total }}<small>종목</small></div>
        <div class="s">
          SB {{ candidates.strongBuy }} · BUY {{ candidates.buy }} · 관망 {{ candidates.watch }}
        </div>
        <!--
          0 건은 그 자체로 정보가 부족하다 — "진짜 없음"·"조회 실패"·"입력 노후로 미채점"이 전부 0 이다.
          백엔드가 만든 사유를 반드시 띄운다(예전엔 note 를 만들어놓고 화면에서 버렸다).
        -->
        <div v-if="candidates.note" class="s note" :title="candidates.noteDetail || candidates.note">
          {{ candidates.note }}
        </div>
        <span class="basis" :class="{ stale: candidates.snapshotStale }">{{ candidatesBasis }}</span>
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
          <span v-for="item in closedGates" :key="item.key" class="gate-closed" :title="item.detail">
            {{ item.label }}
          </span>
          <span v-if="closedGates.length === 0">전부 열림</span>
        </div>
        <span class="basis">설정·DB 읽기 · KIS 미호출</span>
      </template>
      <NoData v-else />
    </div>

    <!-- ③ 일일손실 서킷 (원 단위 — 자산 % 킬스위치와 별개 장치) -->
    <div class="kpi" :class="{ alert: breaker && breaker.trippedToday }">
      <div class="eyebrow">일일손실 서킷</div>
      <template v-if="breaker && breaker.dataAvailable">
        <div class="v" :class="{ none: pnlText === null }">
          <template v-if="pnlText !== null">{{ pnlText }}</template>
          <template v-else>조회 실패</template>
        </div>
        <div class="s">
          <template v-if="headroomText">여유 {{ headroomText }}</template>
          <template v-else-if="breaker.limitKrw != null">한도 -{{ breaker.limitKrw.toLocaleString('ko-KR') }}원</template>
          <template v-else>한도 미설정</template>
          · {{ breaker.trippedToday ? '발동' : '미발동' }}
        </div>
        <div v-if="breaker.note" class="s note">{{ breaker.note }}</div>
        <span class="basis" :title="breakerBasisDetail">{{ breakerBasis }}</span>
      </template>
      <NoData v-else :reason="breaker && breaker.note" />
    </div>

    <!-- ④ VKOSPI 레짐 -->
    <div class="kpi">
      <div class="eyebrow">VKOSPI 레짐</div>
      <template v-if="volRegime && volRegime.dataAvailable">
        <div class="v word">{{ volRegime.regime }}</div>
        <div class="s">게이트 {{ volRegime.gateMode }}</div>
        <span class="basis">252일 백분위 상위 10%</span>
      </template>
      <NoData v-else :reason="volRegime && volRegime.note" />
    </div>

    <!-- ⑤ 미판정 -->
    <div class="kpi" :class="{ alert: undecided && undecided.dataAvailable && undecided.count > 0 }">
      <div class="eyebrow">미판정</div>
      <template v-if="undecided && undecided.dataAvailable">
        <div class="v">{{ undecided.count }}<small>건</small></div>
        <div class="s">전체 {{ undecided.rosterSize }}건 중</div>
        <span class="basis">SCHEDULE_DECISIONS 판정 기록 표</span>
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

/**
 * 후보 수를 의심해야 하는 상태 — 0 건인데 스냅샷이 노후이거나, 스냅샷 자체가 없을 때.
 * 정상적으로 0 건일 수도 있지만, 그 둘을 화면에서 구분할 수 없으므로 눈에 띄게 해서
 * 운영자가 확인하도록 만든다(조용한 0 이 제일 나쁘다).
 */
const candidatesSuspect = computed(() => {
  const c = candidates.value
  if (!c || !c.dataAvailable) return false
  return c.total === 0 && (c.snapshotStale === true || c.latestSnapshotAt == null)
})

/**
 * 후보 카드 하단 기준 줄 — 스냅샷 시각을 겸한다.
 * 별도 줄을 하나 더 쓰면 1/5 폭 카드가 글자 벽이 된다.
 */
const candidatesBasis = computed(() => {
  const c = candidates.value
  if (!c) return ''
  if (!c.latestSnapshotAt) return 'momentum 보드 · 스냅샷 없음'
  const at = String(c.latestSnapshotAt).replace('T', ' ').slice(5, 16)
  return c.snapshotStale ? `스냅샷 ${at} · 노후` : `스냅샷 ${at}`
})

const breakerBasis = computed(() =>
  breaker.value?.mode ? `${breaker.value.mode} · 확정 매도만` : '확정 매도만'
)
const breakerBasisDetail =
  '당일 확정된 봇 매도만 합산(원 단위). 자산 대비 -3% 킬스위치는 별개 장치다.'

const pnlText = computed(() => formatKrw(breaker.value?.realizedPnlKrw))
const headroomText = computed(() => {
  const h = breakerHeadroom(breaker.value?.realizedPnlKrw, breaker.value?.limitKrw)
  return h === null ? null : formatKrw(h)
})
</script>

<style scoped>
.kpis {
  display: grid;
  /* minmax(0,…) 필수 — 맨 1fr 이면 안 끊기는 토큰이 칸을 밀어내 나머지가 쪼개진다 */
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.kpi {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  padding: 15px 17px;
  position: relative;
  min-height: 124px;
  min-width: 0;          /* 그리드 항목 기본 min-width:auto 해제 */
  overflow-wrap: anywhere; /* SCHEDULE_DECISIONS 같은 긴 토큰이 칸을 넘기지 않게 */
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
.kpi.warn { border-color: var(--cr-amb); }
.kpi.warn::before,
.kpi.warn::after { border-color: var(--cr-amb); }
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
  font-size: 30px;
  line-height: 1.15;
  margin: 7px 0 4px;
  color: #fff;
  letter-spacing: -0.01em;
  word-break: keep-all;
}
/* 숫자가 아닌 값(국면명 등) */
.v.word { font-size: 20px; padding-top: 6px; letter-spacing: 0; }
/* 값을 못 읽은 자리 — 숫자처럼 크게 두면 0 과 혼동된다 */
.v.none { font-size: 19px; padding-top: 6px; color: var(--cr-mut); letter-spacing: 0; }
.v small { font-size: 13px; color: var(--cr-mut); margin-left: 4px; letter-spacing: 0; }
.kpi.alert .v { color: var(--cr-red); }
.kpi.warn .v { color: var(--cr-amb); }

/*
 * 기준 표기 — 카드 **하단 각주**다(jewelry-leads 배치와 동일).
 * 라벨 바로 밑에 두면 숫자가 아래로 밀려 카드가 글자 벽처럼 보인다.
 */
.basis {
  font-family: var(--cr-mono);
  display: block;
  margin-top: 5px;
  font-size: 10px;
  letter-spacing: 0.02em;
  color: var(--cr-dim);
}

.s {
  font-size: 12px;
  color: var(--cr-mut);
  line-height: 1.45;
}
/* 사유는 한 줄만 — 전체 근거는 title 툴팁에 있다 */
.s.note {
  color: var(--cr-amb);
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.4;
  cursor: help;
}
.basis.stale { color: var(--cr-amb); }

.bar { display: flex; gap: 2px; margin-top: 8px; }
.bar i { flex: 1; height: 5px; background: var(--cr-dim); }
.bar i.g-open { background: var(--cr-grn); }
.bar i.g-closed { background: var(--cr-red); }
.bar i.g-unknown { background: var(--cr-amb); }

.gate-list { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; }
.gate-closed { color: var(--cr-red); }

@media (max-width: 1400px) {
  .v { font-size: 27px; }
}

@media (max-width: 1100px) {
  .kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 720px) {
  .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .kpi { padding: 12px 13px; min-height: 108px; }
  .v { font-size: 24px; }
}

@media (max-width: 420px) {
  .kpi { padding: 11px; min-height: 98px; }
  .v { font-size: 21px; }
  .basis { font-size: 9px; }
}
</style>
