<template>
  <!-- 차트 해설 — 관찰용(매수 신호 아님). 근거 없으면 카드 자체를 렌더하지 않는다. -->
  <section v-if="narrative && narrative.sections?.length" class="narrative-card">
    <header class="nc-head">
      <h3 class="nc-title">📖 차트 해설</h3>
      <span class="nc-verdict" :class="verdictClass">{{ narrative.verdictLabel }}</span>
    </header>

    <p class="nc-reason">{{ narrative.verdictReason }}</p>

    <div v-for="section in narrative.sections" :key="section.title" class="nc-section">
      <h4 class="nc-section-title">{{ section.title }}</h4>
      <ul class="nc-lines">
        <li v-for="(line, i) in section.lines" :key="i">{{ line }}</li>
      </ul>
    </div>

    <p class="nc-disclaimer">
      지표를 규칙대로 읽어 문장으로 옮긴 <strong>관찰용 해설</strong>입니다.
      매수 신호가 아니며 종합추천 점수·매매봇에 반영되지 않습니다.
    </p>
  </section>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';

const props = defineProps({
  stockCode: { type: String, required: true }
});

const narrative = ref(null);

// 판단보류(UNKNOWN)는 카드를 띄우지 않는다 — 빈 해설로 자리만 차지하지 않기 위함
const fetchNarrative = async (code) => {
  narrative.value = null;
  if (!code) return;
  try {
    const { data } = await apiClient.get(`/quant-ta/${code}/narrative`);
    if (data?.success && data.data?.verdict !== 'UNKNOWN') {
      narrative.value = data.data;
    }
  } catch (e) {
    narrative.value = null;   // best-effort — 실패하면 조용히 숨김
  }
};

const verdictClass = computed(() => {
  switch (narrative.value?.verdict) {
    case 'WATCH': return 'v-watch';
    case 'OVERHEATED': return 'v-overheated';
    default: return 'v-wait';
  }
});

watch(() => props.stockCode, (code) => fetchNarrative(code), { immediate: true });
</script>

<style scoped>
.narrative-card {
  /* 2026-09-21 점검: 라이트 테마 색이 하드코딩돼 있어 어두운 종목상세 화면에서 혼자 흰 카드였다.
     공용 토큰으로 맞춘다 — 색만 바꾸고 레이아웃·문구·판정은 그대로다. */
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  border-radius: 12px;
  padding: 16px 18px;
  margin: 12px 0;
}

.nc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.nc-title {
  font-size: 15px;
  font-weight: 700;
  margin: 0;
  color: var(--text-primary, #f0f0f5);
}

.nc-verdict {
  font-size: 13px;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: 999px;
}

.v-wait { background: rgba(255, 255, 255, 0.08); color: var(--text-secondary, #b0b0c8); }
.v-overheated { background: var(--warning-light, rgba(251, 191, 36, 0.12)); color: var(--warning, #fbbf24); }
.v-watch { background: var(--info-light, rgba(96, 165, 250, 0.12)); color: var(--info, #60a5fa); }

.nc-reason {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #f0f0f5);
  margin: 0 0 14px;
  line-height: 1.5;
}

.nc-section { margin-bottom: 12px; }

.nc-section-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-muted, #7878a0);
  margin: 0 0 4px;
}

.nc-lines {
  margin: 0;
  padding-left: 16px;
  list-style: none;
}

.nc-lines li {
  font-size: 13.5px;
  color: var(--text-secondary, #b0b0c8);
  line-height: 1.65;
  position: relative;
}

.nc-lines li::before {
  content: '·';
  position: absolute;
  left: -12px;
  color: var(--text-muted, #7878a0);
}

.nc-disclaimer {
  font-size: 11.5px;
  color: var(--text-muted, #7878a0);
  line-height: 1.5;
  margin: 10px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--border-light, rgba(255, 255, 255, 0.05));
}
</style>
