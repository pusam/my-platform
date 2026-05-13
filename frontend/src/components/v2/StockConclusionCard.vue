<template>
  <div v-if="conclusion" class="conclusion-card" :class="levelClass">
    <div class="conclusion-main">
      <div class="conclusion-level">
        <span class="level-icon">{{ levelIcon }}</span>
        <span class="level-label">{{ levelLabel }}</span>
      </div>
      <div class="conclusion-text">
        <p class="headline">{{ conclusion.headline }}</p>
        <p v-if="conclusion.guidance" class="guidance">{{ conclusion.guidance }}</p>
      </div>
      <button class="checklist-btn" @click="openChecklist" title="매수 체크리스트">
        ✅ 매수 체크리스트
      </button>
    </div>

    <div v-if="conclusion.factors && conclusion.factors.length" class="conclusion-factors">
      <div v-for="f in conclusion.factors" :key="f.key" class="factor"
           :class="'factor-' + (f.verdict || 'neutral').toLowerCase()">
        <div class="factor-header">
          <span class="factor-label">{{ f.label }}</span>
          <span class="factor-dim" :class="'dim-' + (f.dimension || 'mid').toLowerCase()">
            {{ dimensionLabel(f.dimension) }}
          </span>
        </div>
        <div class="factor-score">{{ f.score }}</div>
        <div class="factor-note">{{ f.note }}</div>
      </div>
    </div>

    <div v-if="conclusion.dataAt" class="conclusion-meta">
      <span>스냅샷: {{ formatDataAt(conclusion.dataAt) }}</span>
    </div>

    <BuyChecklistModal v-if="showChecklist" :stock-code="stockCode" @close="showChecklist = false" />
  </div>
  <div v-else-if="loading" class="conclusion-card loading">결론 분석 중...</div>
  <div v-else-if="error" class="conclusion-card error">결론을 불러오지 못했습니다.</div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';
import BuyChecklistModal from './BuyChecklistModal.vue';

const props = defineProps({
  stockCode: { type: String, required: true }
});

const conclusion = ref(null);
const loading = ref(false);
const error = ref(false);
const showChecklist = ref(false);

const fetchConclusion = async (code) => {
  if (!code) return;
  loading.value = true;
  error.value = false;
  try {
    const { data } = await apiClient.get(`/stock/${code}/conclusion`);
    if (data?.success) {
      conclusion.value = data.data;
    } else {
      error.value = true;
    }
  } catch (e) {
    error.value = true;
  } finally {
    loading.value = false;
  }
};

watch(() => props.stockCode, (code) => fetchConclusion(code), { immediate: true });

const levelLabel = computed(() => {
  switch (conclusion.value?.level) {
    case 'STRONG_BUY': return '강력 매수';
    case 'BUY': return '매수';
    case 'HOLD': return '보유 / 분할';
    case 'WAIT': return '관망';
    default: return '-';
  }
});

const levelIcon = computed(() => {
  switch (conclusion.value?.level) {
    case 'STRONG_BUY': return '🟢';
    case 'BUY': return '🔵';
    case 'HOLD': return '🟡';
    case 'WAIT': return '⚪';
    default: return '⚪';
  }
});

const levelClass = computed(() => 'level-' + (conclusion.value?.level || 'wait').toLowerCase().replace('_', '-'));

const dimensionLabel = (dim) => {
  switch (dim) {
    case 'SHORT': return '단기';
    case 'MID': return '중기';
    case 'LONG': return '장기';
    case 'META': return '필수';
    default: return '';
  }
};

const formatDataAt = (iso) => {
  try {
    const d = new Date(iso);
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  } catch { return ''; }
};

const openChecklist = () => { showChecklist.value = true; };
</script>

<style scoped>
.conclusion-card {
  background: rgba(20, 24, 38, 0.85);
  border-radius: 12px;
  padding: 16px 20px;
  margin: 12px 16px;
  border-left: 4px solid #888;
  color: #fff;
}
.conclusion-card.loading,
.conclusion-card.error {
  text-align: center;
  font-size: 13px;
  opacity: 0.7;
}
.level-strong-buy { border-left-color: #22c55e; background: rgba(34, 197, 94, 0.12); }
.level-buy        { border-left-color: #3b82f6; background: rgba(59, 130, 246, 0.10); }
.level-hold       { border-left-color: #eab308; background: rgba(234, 179, 8, 0.10); }
.level-wait       { border-left-color: #888;    background: rgba(120, 120, 120, 0.10); }

.conclusion-main {
  display: flex;
  align-items: center;
  gap: 16px;
}
.conclusion-level {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 90px;
}
.level-icon { font-size: 28px; }
.level-label { font-size: 13px; font-weight: 700; margin-top: 4px; }
.conclusion-text { flex: 1; }
.headline { margin: 0; font-size: 15px; font-weight: 600; line-height: 1.4; }
.guidance { margin: 6px 0 0; font-size: 13px; opacity: 0.78; }
.checklist-btn {
  background: rgba(59, 130, 246, 0.18);
  color: #93c5fd;
  border: 1px solid rgba(147, 197, 253, 0.35);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
.checklist-btn:hover { background: rgba(59, 130, 246, 0.30); }

.conclusion-factors {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.factor {
  background: rgba(255, 255, 255, 0.04);
  border-radius: 8px;
  padding: 10px;
}
.factor-positive { border-left: 3px solid #22c55e; }
.factor-neutral  { border-left: 3px solid #eab308; }
.factor-negative { border-left: 3px solid #ef4444; }
.factor-header { display: flex; justify-content: space-between; align-items: center; }
.factor-label { font-size: 12px; font-weight: 600; opacity: 0.9; }
.factor-dim {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  opacity: 0.85;
}
.dim-short { color: #fca5a5; }
.dim-mid   { color: #93c5fd; }
.dim-long  { color: #86efac; }
.dim-meta  { color: #d1d5db; }
.factor-score { font-size: 18px; font-weight: 700; margin: 4px 0; }
.factor-note { font-size: 11px; opacity: 0.65; line-height: 1.3; }

.conclusion-meta {
  margin-top: 10px;
  font-size: 11px;
  opacity: 0.55;
  text-align: right;
}
</style>
