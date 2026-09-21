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
        <span class="basis" :class="{ stale: candidates.snapshotStale || isFallback }" :title="basisDetail">
          {{ candidatesBasis }}
        </span>
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

    <!--
      ⑥ 재무 입력층 — 이 카드가 없어서 겪은 일:
      KIS 손익계산서 응답의 금액 필드명이 틀려 434종목 전 기간의 매출·영업이익·순이익이
      몇 달 동안 NULL 이었는데 어느 화면에도 안 보였다(2026-08-26). 종목별 WARN 로그는
      있었지만 434줄을 세는 사람은 없다. "몇 개 중 몇 개"는 집계로 봐야 보인다.
      세 값이 서로 다른 KIS 호출이라 어느 쪽이 죽었는지 한눈에 갈린다.
    -->
    <div class="kpi" :class="{ alert: financialSuspect }">
      <div class="eyebrow">재무 입력층</div>
      <template v-if="financial && financial.dataAvailable">
        <div class="v" :class="{ none: financial.total === 0 }">
          <template v-if="financial.total > 0">
            {{ financial.withStatement }}<small>/{{ financial.total }}</small>
          </template>
          <template v-else>행 없음</template>
        </div>
        <div class="s field-rows">
          <span :class="{ dead: financial.withRatios === 0 }">비율 {{ financial.withRatios }}</span>
          <span :class="{ dead: financial.withStatement === 0 }">손익 {{ financial.withStatement }}</span>
          <span :class="{ dead: financial.withBalance === 0 }">재무상태 {{ financial.withBalance }}</span>
        </div>
        <div v-if="financial.note" class="s note" :title="financial.noteDetail || financial.note">
          {{ financial.note }}
        </div>
        <span class="basis">{{ financial.asOf || '기준일 미상' }} · 큰 숫자 = 손익계산서 충전</span>
      </template>
      <NoData v-else :reason="financial && financial.note" :title="financial && financial.noteDetail" />
    </div>

    <!--
      ⑦ 믿고 사도 되나 — 표본·비용·불확실성 3단계(2026-09-16).
      이 카드가 없을 때 이 질문의 답은 사람이 적중률 숫자를 눈으로 보고 내렸다. 적중률만으론
      손익을 모르고(맞을 때 얼마 벌고 틀릴 때 얼마 잃는지가 빠진다) 거래비용도 빠져 있었다.
      ⚠ state 는 승인 등급이 아니다 — EVALUABLE 은 "이제 숫자를 읽을 수 있다"이고,
      CONSIDER_EXPANDING 도 모의운용 확대 검토까지다. 임계는 백엔드 TrustGateRules 단일 출처라
      여기서 다시 계산하지 않는다(재무 입력층 카드와 같은 규약).
    -->
    <div class="kpi trust" :class="trustClass">
      <div class="eyebrow">믿고 사도 되나</div>
      <template v-if="trust && trust.dataAvailable">
        <div class="v trust-state">{{ trustLabel }}</div>
        <div class="s">
          표본 {{ trust.rows }}건 · <b>고유 {{ trust.distinctDays }}일</b> · 대조군 {{ trust.controlRows }}건
        </div>
        <div class="s trust-nums">
          <span :class="signClass(trust.costAdjustedReturn)">
            비용차감 {{ pct(trust.costAdjustedReturn) }}
          </span>
          <span :class="{ dead: !trust.edgeExceedsUncertainty }" :title="edgeTitle">
            대조군比 {{ pct(trust.edgeVsControl) }}
            <em v-if="trust.edgeMarginOfError != null">±{{ num(trust.edgeMarginOfError) }}</em>
            <em v-else>±?</em>
          </span>
        </div>
        <div class="s trust-shape">
          이익 {{ pct(trust.avgWin) }} / 손실 {{ pct(trust.avgLoss) }}
          · 최악 {{ pct(trust.worst) }} · 낙폭 {{ pct(trust.avgMaePct) }}
        </div>
        <div v-if="trust.note" class="s note" :title="trust.noteDetail || trust.note">
          {{ trust.note }}
        </div>
        <span class="basis">V59 교정 평가 기준(D+3 종가) · 실매수 승인 아님</span>
      </template>
      <NoData v-else :reason="trust && trust.note" :title="trust && trust.noteDetail" />
    </div>
  </div>
</template>

<script setup>
/**
 * 관제실 KPI 6종.
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
const financial = computed(() => props.kpis?.financialInput ?? null)

// ── ⑦ 믿고 사도 되나 ────────────────────────────────────────────────────────
// 판정(상태·임계)은 전부 백엔드 TrustGateRules 가 확정한다. 여기서는 라벨과 색만 붙인다 —
// 화면이 임계를 다시 계산하면 두 곳이 언젠가 갈린다(재무 입력층 카드와 같은 규약).
const trust = computed(() => props.kpis?.trustGate ?? null)

const TRUST_LABELS = {
  COLLECTING: '표본 수집 중',
  EVALUABLE: '평가 가능',
  CONSIDER_EXPANDING: '모의운용 확대 검토'
}
const trustLabel = computed(() => TRUST_LABELS[trust.value?.state] || '판정 불가')

// 통과(CONSIDER_EXPANDING)만 ok. 나머지는 경고도 정상도 아닌 중립 — 표본 수집 중을 빨갛게 칠하면
// 사람이 "고장"으로 읽고, 초록으로 칠하면 "괜찮다"로 읽는다. 둘 다 사실이 아니다.
const trustClass = computed(() => ({
  ok: trust.value?.state === 'CONSIDER_EXPANDING',
  collecting: trust.value?.state === 'COLLECTING'
}))

const edgeTitle = computed(() => {
  const t = trust.value
  if (!t) return ''
  if (t.edgeMarginOfError == null) return '불확실성 폭을 아직 계산할 수 없다(비교 가능한 날이 2일 미만). 0 이 아니라 "모름"이다.'
  return t.edgeExceedsUncertainty
    ? '우위가 95% 불확실성 폭을 넘었다 — 0 과 구분된다.'
    : '우위가 불확실성 폭 안에 있다 — 0 과 구분되지 않는다(우연일 수 있다).'
})

/** %는 결측이면 '-' — 0 으로 위장하지 않는다(§4c). */
const pct = (v) => (v == null ? '-' : `${Number(v) > 0 ? '+' : ''}${Number(v).toFixed(2)}%`)
const num = (v) => (v == null ? '?' : Number(v).toFixed(2))
const signClass = (v) => (v == null ? { none: true } : { up: Number(v) > 0, down: Number(v) < 0 })


/**
 * 경고를 켤 조건 — 백엔드가 note 를 달았을 때만.
 *
 * 임계 판단을 화면에서 다시 하지 않는다(§ 스냅샷이 확정한 값을 재계산하지 않는다).
 * note 가 null 이면 정상이고, 정상일 때 조용한 것이 이 카드의 계약이다.
 */
const financialSuspect = computed(
  () => !!financial.value && (!financial.value.dataAvailable || !!financial.value.note)
)

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
 * 이 숫자가 어제 스냅샷 폴백인가.
 *
 * getTop5 는 캐시가 비었거나 장외면 DB 스냅샷을 돌려주고 realtime=false 로 알린다.
 * 그걸 화면이 안 보여주면 "어제 1건"을 "오늘 1건"으로 읽는다 — 2026-08-26 실제 발생
 * (09:00 화면 1종목 / 09:30 재계산 0건).
 */
const isFallback = computed(() => candidates.value?.realtime === false)

/**
 * 후보 카드 하단 기준 줄 — 숫자의 **출처**를 밝힌다.
 * 별도 줄을 더 쓰면 1/5 폭 카드가 글자 벽이 되므로 이 한 줄에 담는다.
 */
const candidatesBasis = computed(() => {
  const c = candidates.value
  if (!c) return ''
  if (isFallback.value) return `${c.asOf || '이전 스냅샷'} · 실시간 아님`
  if (c.asOf) return `${c.asOf} · 실시간`
  if (!c.latestSnapshotAt) return 'momentum 보드 · 스냅샷 없음'
  const at = String(c.latestSnapshotAt).replace('T', ' ').slice(5, 16)
  return c.snapshotStale ? `스냅샷 ${at} · 노후` : `스냅샷 ${at}`
})

const basisDetail = computed(() => {
  if (!isFallback.value) return '이번 계산으로 나온 실시간 값이다'
  return '캐시가 비었거나 장외라 이전 스냅샷을 그대로 보여주는 중이다 — 오늘 계산 결과가 아니다. '
    + '다음 계산에서 값이 달라질 수 있다.'
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
  grid-template-columns: repeat(6, minmax(0, 1fr));
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

  /* ⚠ 전역 유틸리티 `.alert`(common.css — 경고 배너용: display:flex·padding·margin-bottom)와
     카드의 상태 modifier `.kpi.alert` 가 같은 이름이라 충돌한다(2026-09-21 실측).
     경보 상태 카드 5종이 전부 배너 레이아웃을 물려받아 자식들이 **가로로** 늘어섰고,
     375px 에서는 "봇 게 이 트" 처럼 글자가 세로로 쪼개졌다.
     전역 규칙엔 실제 소비자가 없지만(배너들은 .alert-box 를 쓴다) 지우는 건 별건이라,
     여기서 레이아웃을 명시해 덮이지 않게 한다 — 스코프 덕에 specificity 가 이긴다. */
  display: block;
  margin-bottom: 0;
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

/* ⑦ 믿고 사도 되나 — 6칸 그리드에 7번째를 끼우면 한 칸짜리 고아 행이 생긴다.
   숫자가 네 줄(표본/수익/분포/사유)이라 좁은 칸에서 제일 먼저 눌리는 카드이기도 해서,
   아예 한 줄을 통째로 쓴다. 경고색은 쓰지 않는다 — '표본 수집 중'은 고장이 아니다. */
.kpi.trust { grid-column: 1 / -1; min-height: 0; }
.kpi.trust.collecting { border-style: dashed; }
.trust-state { font-size: 21px; letter-spacing: 0; }
.trust-nums { display: flex; flex-wrap: wrap; gap: 4px 14px; font-family: var(--cr-mono); }
.trust-nums em { font-style: normal; color: var(--cr-mut); }
.trust-nums .dead { color: var(--cr-mut); }
.trust-shape { color: var(--cr-mut); font-family: var(--cr-mono); }
.trust-nums .up { color: var(--cr-grn); }
.trust-nums .down { color: var(--cr-red); }
.trust-nums .none { color: var(--cr-mut); }

.eyebrow {
  font-family: var(--cr-mono);
    font-size: 11px;
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
  font-size: 11px;
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

/* 세 호출의 충전 상태 — 0 인 것만 붉게 튀어야 눈이 거기로 간다. */
.field-rows { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 8px; }
.field-rows .dead { color: var(--cr-red); }
/* 막힌 게이트가 둘 이상이면 공백만으론 한 문장처럼 붙어 읽힌다 —
   'NXT 주문 라우팅 NXT 연장장 청산'. 칩 테두리로 경계를 준다. */
.gate-closed {
  color: var(--cr-red);
  border: 1px solid rgba(255, 77, 109, 0.35);
  padding: 1px 6px;
  line-height: 1.5;
}

/* 6칸은 1400px 아래에서 칸당 200px 을 밑돌아 라벨이 눌린다 —
   폰트만 줄이지 말고 3칸 2줄로 접는다(6이 3의 배수라 줄이 깔끔하게 찬다). */
@media (max-width: 1400px) {
  .kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); }
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

/* 2열을 끝까지 유지하면 375px 에서 카드가 ~172px 로 눌려 "봇 게 이 트"처럼 글자가
   세로로 쪼개진다(2026-09-21 실측). 한 줄에 하나씩 놓아 라벨이 온전히 읽히게 한다. */
@media (max-width: 480px) {
  .kpis { grid-template-columns: minmax(0, 1fr); }
  .kpi { padding: 12px 14px; min-height: 0; }
  .v { font-size: 22px; }
  .basis { font-size: 11px; }
  .k { white-space: nowrap; }   /* 라벨은 줄바꿈하지 않는다 — 한 칸을 다 쓰므로 자리가 충분하다 */
}
</style>
