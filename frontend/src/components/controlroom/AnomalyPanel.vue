<template>
  <section class="panel">
    <div class="ph">
      <b>🔎 데이터 이상 점검</b>
      <span v-if="anomalies && anomalies.dataAvailable">
        {{ items.length ? `${items.length}건` : '이상 없음' }}
      </span>
    </div>

    <!-- 점검이 터진 것과 "이상 0건"은 완전히 다른 상태다(§4c) -->
    <NoData
      v-if="!anomalies || !anomalies.dataAvailable"
      :reason="anomalies?.note || '점검이 실패함 — \'이상 없음\'이 아니라 \'모름\'이다'"
    />

    <!--
      정상일 때 조용한 것이 이 패널의 계약이다. 다만 "아무것도 안 보임"이 되면
      점검이 도는지조차 알 수 없으므로, 규칙이 보는 범위가 한정적이라는 것만 한 줄 남긴다.
    -->
    <p v-else-if="items.length === 0" class="empty">
      규칙에 걸린 항목 없음.
      <span class="scope">규칙이 보는 범위 안에서만 그렇다 — 점검하지 않는 축까지 정상이라는 뜻은 아니다.</span>
    </p>

    <div v-else class="anom-list">
      <article v-for="a in items" :key="a.key" class="anom" :class="severityClass(a.severity)">
        <b class="head" title="에렌에게 이 이상 항목 처리 방법 묻기" @click="$emit('ask', askText(a))">
          {{ a.title }}
          <span v-if="a.key" class="k">{{ a.key }}</span>
        </b>
        <p v-if="a.evidence" class="evidence">{{ a.evidence }}</p>
        <!-- 본문은 2줄 클램프 — 규칙마다 "무엇을 확인하라"가 길어 펼치기로 둔다(FLAGGED 와 동일 규약) -->
        <p class="body" :class="{ open: isOpen(a) }" @click="toggle(a)">{{ a.detail }}</p>
      </article>
    </div>

    <p class="src">
      사람이 적는 FLAGGED 와 달리 <b>코드가 매 스냅샷마다 판정</b>한다.
      <span v-if="anomalies?.checkedAt" class="when">{{ checkedAtText }} 기준</span>
    </p>
  </section>
</template>

<script setup>
/**
 * 데이터 이상 점검 패널 — 결정적 규칙({@code DataAnomalyRules})이 찾은 것.
 *
 * **FLAGGED 와 왜 따로 두는가**: FLAGGED 는 사람이 적고 사람이 지운다. 이쪽은 코드가 매번
 * 다시 판정하므로 "지운다"는 개념이 없고, 고쳐지면 저절로 사라진다. 둘을 한 목록에 섞으면
 * "왜 안 지워지지" / "왜 사라졌지"가 헷갈린다.
 *
 * **왜 이 패널이 있는가**: 크루는 툴이 없어 스스로 데이터를 못 뒤진다(CLAUDE.md §7).
 * 탐지는 규칙이 하고 크루는 결과를 읽는다. 그런데 크루만 보고 사람은 못 보면 반쪽이라
 * 같은 결과를 화면에도 건다.
 *
 * ⚠ 여기서 판정을 다시 하지 않는다 — 임계는 백엔드 단일 출처다. 화면은 그리기만 한다.
 */
import { computed, ref } from 'vue'
import NoData from './NoData.vue'
import { severityClass } from '../../utils/controlRoomFormat'

const props = defineProps({
  anomalies: { type: Object, default: null }
})
defineEmits(['ask'])

const items = computed(() => props.anomalies?.items ?? [])

const checkedAtText = computed(() => {
  const t = props.anomalies?.checkedAt
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
})

const opened = ref(new Set())
const isOpen = (a) => opened.value.has(a.key)
function toggle(a) {
  const next = new Set(opened.value)
  if (next.has(a.key)) next.delete(a.key)
  else next.add(a.key)
  opened.value = next
}

/** 크루에게 넘길 질문 — 근거 수치까지 같이 준다. 수치 없이 물으면 크루가 추측한다. */
function askText(a) {
  const evidence = a.evidence ? ` (근거: ${a.evidence})` : ''
  return `데이터 이상 점검에 "${a.title}"${evidence} 가 잡혔다. `
    + '원인 후보와 확인 순서를 정리하고, 지금 판정·매매에 영향이 있는지 알려줘.'
}
</script>

<style scoped>
.panel {
  background: var(--cr-panel);
  border: 1px solid var(--cr-line);
  padding: 14px 16px;
  min-width: 0;
}
.ph {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
  font-family: var(--cr-mono);
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--cr-mut);
}
.ph b { color: var(--cr-tx); }

.empty { font-size: 11.5px; color: var(--cr-mut); line-height: 1.6; margin: 0; }
.empty .scope { display: block; margin-top: 4px; font-size: 10px; color: var(--cr-dim); }

.anom-list { display: grid; gap: 9px; }

/* 좌측 색띠로 심각도 — FLAGGED 와 같은 어휘를 쓰므로 같은 시각 규약을 따른다 */
.anom {
  border-left: 2px solid var(--cr-line);
  padding-left: 9px;
  min-width: 0;
}
.anom.crit { border-left-color: var(--cr-red); }
.anom.warn { border-left-color: var(--cr-amb); }
.anom.info { border-left-color: var(--cr-vio); }

.head {
  display: block;
  font-size: 12px;
  line-height: 1.45;
  color: var(--cr-tx);
  cursor: pointer;
  overflow-wrap: anywhere;
}
.head:hover { color: var(--cr-mag); }
.k {
  margin-left: 6px;
  font-family: var(--cr-mono);
  font-size: 9.5px;
  color: var(--cr-dim);
  border: 1px solid var(--cr-line);
  padding: 1px 5px;
}

.evidence {
  margin: 3px 0 0;
  font-family: var(--cr-mono);
  font-size: 10.5px;
  color: var(--cr-mut);
  overflow-wrap: anywhere;
}

.body {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.55;
  color: var(--cr-dim);
  cursor: pointer;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.body.open { display: block; overflow: visible; }

.src {
  margin: 10px 0 0;
  padding-top: 8px;
  border-top: 1px solid var(--cr-line);
  font-size: 10px;
  color: var(--cr-dim);
  line-height: 1.55;
}
.src .when { font-family: var(--cr-mono); }
</style>
