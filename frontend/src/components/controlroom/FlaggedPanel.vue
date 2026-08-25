<template>
  <section class="panel">
    <div class="ph">
      <b>⚠ FLAGGED</b>
      <span v-if="flagged && flagged.dataAvailable">
        {{ flagged.flags.length }}건 (critical {{ flagged.criticalCount }})
      </span>
    </div>

    <!-- 문서를 못 읽은 것과 "플래그 0건"은 완전히 다른 상태다 -->
    <NoData
      v-if="!flagged || !flagged.dataAvailable"
      reason="docs/CONTROL_ROOM_FLAGS.md 를 읽지 못함 — '이상 없음'이 아니다"
    />

    <p v-else-if="flags.length === 0" class="empty">열려 있는 플래그 없음.</p>

    <div v-else class="flag-list">
      <article v-for="flag in flags" :key="flag.id + flag.title" class="flag" :class="severityClass(flag.severity)">
        <b>
          {{ flag.title }}
          <span v-if="flag.key" class="k">{{ flag.key }}</span>
          <!-- 사람이 적은 항목과 시스템이 유도한 항목(파싱 오류·미등록 판정)을 구분한다 -->
          <span v-if="flag.derived" class="derived">자동 감지</span>
        </b>
        <p>{{ flag.body }}</p>
        <div class="meta">
          <span v-if="ageLabel(flag.ageDays)" :class="{ stale: flag.ageDays >= 30 }">
            {{ flag.recordedOn }} 기록 · {{ ageLabel(flag.ageDays) }}
          </span>
          <span v-if="flag.ref" class="ref">{{ flag.ref }}</span>
        </div>
        <button type="button" class="ask" @click="$emit('ask', askText(flag))">에렌에게 물어보기</button>
      </article>
    </div>
  </section>
</template>

<script setup>
/**
 * FLAGGED 패널.
 *
 * 목록은 `docs/CONTROL_ROOM_FLAGS.md`(사람이 관리) + 시스템 유도 항목 2종(파싱 오류·미등록 판정)이다.
 * 유도 항목은 "자동 감지" 배지로 구분한다 — 사람이 지워야 할 것과 코드가 다시 만들 것을 헷갈리지 않게.
 *
 * 오래된 플래그(30일+)는 흐리게 강조한다. 해소됐는데 안 지운 항목이 관제실을 거짓말로 만드는 것이
 * 이 화면의 가장 큰 실패 모드라, 신선도를 눈에 띄게 둔다.
 */
import { computed } from 'vue'
import NoData from './NoData.vue'
import { severityClass, ageLabel } from '../../utils/controlRoomFormat'

const props = defineProps({
  flagged: { type: Object, default: null }
})

defineEmits(['ask'])

const flags = computed(() => props.flagged?.flags ?? [])

function askText(flag) {
  return `FLAGGED "${flag.title}" 지금 어떻게 처리해야 하는지 정리해`
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
}
.ph b { font-size: 12px; letter-spacing: 0.2em; text-transform: uppercase; }
.ph span { font-size: 11px; color: var(--cr-mut); }

.empty { font-size: 12px; color: var(--cr-mut); }

/* 항목이 늘어도 패널이 무한히 길어지지 않게 — 좁은 화면에선 페이지 스크롤 하나로 합친다 */
.flag-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: min(560px, 58dvh);
  overflow-y: auto;
}

.flag {
  border-left: 3px solid var(--cr-red);
  padding: 8px 10px;
  background: rgba(255, 255, 255, 0.02);
}
.flag.warn { border-left-color: var(--cr-amb); }
.flag.info { border-left-color: var(--cr-cyn); }

.flag b { display: block; font-size: 12.5px; margin-bottom: 3px; line-height: 1.4; }
.flag .k {
    font-size: 10px;
  color: var(--cr-vio);
  margin-left: 6px;
}
.flag .derived {
    font-size: 9px;
  letter-spacing: 0.1em;
  color: var(--cr-cyn);
  border: 1px solid var(--cr-cyn);
  padding: 0 4px;
  margin-left: 6px;
}

.flag p { font-size: 11.5px; color: var(--cr-mut); line-height: 1.55; margin: 0; }

.meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 5px;
    font-size: 9.5px;
  color: var(--cr-dim);
}
.meta .stale { color: var(--cr-amb); }
.meta .ref { opacity: 0.8; }

.ask {
  margin-top: 7px;
  background: transparent;
  border: 1px solid var(--cr-line);
  color: var(--cr-tx);
  font-size: 10.5px;
  padding: 3px 8px;
  cursor: pointer;
}
.ask:hover { border-color: var(--cr-vio); }

@media (max-width: 900px) {
  /* 중첩 스크롤 방지 — 페이지 스크롤 하나로 */
  .flag-list { max-height: none; }
}
</style>
