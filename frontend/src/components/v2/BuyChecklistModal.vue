<template>
  <div class="modal-backdrop" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h2>매수 체크리스트</h2>
        <button class="close-btn" @click="$emit('close')">×</button>
      </div>

      <div v-if="loading" class="modal-body loading">체크리스트 분석 중...</div>
      <div v-else-if="error" class="modal-body error">체크리스트를 불러오지 못했습니다.</div>
      <div v-else-if="checklist" class="modal-body">
        <div class="summary" :class="recommendationClass">
          <div class="rec-tag">{{ recommendationLabel }}</div>
          <div class="rec-progress">
            <span class="passed">{{ checklist.passedCount }}</span>
            <span class="divider">/</span>
            <span class="total">{{ checklist.totalCount }}</span>
            <span class="rec-text">충족</span>
          </div>
          <p class="rec-summary">{{ checklist.summary }}</p>
        </div>

        <ul class="items">
          <li v-for="item in checklist.items" :key="item.key"
              class="item" :class="{ passed: item.passed }">
            <div class="item-status">
              <span class="check-icon">{{ item.passed ? '✅' : '❌' }}</span>
            </div>
            <div class="item-body">
              <div class="item-header">
                <span class="item-label">{{ item.label }}</span>
                <span v-if="item.dimension" class="item-dim"
                      :class="'dim-' + item.dimension.toLowerCase()">
                  {{ dimensionLabel(item.dimension) }}
                </span>
              </div>
              <div class="item-meta">
                <span class="item-value">{{ item.value }}</span>
                <span v-if="item.threshold" class="item-threshold">기준 {{ item.threshold }}</span>
              </div>
              <div v-if="item.note" class="item-note">{{ item.note }}</div>
            </div>
          </li>
        </ul>

        <div class="modal-footer">
          <span class="hint">이 체크리스트는 자동매매 봇이 진입 전에 검증하는 룰을 노출한 것입니다.</span>
          <button class="journal-btn" @click="showJournal = true"
                  title="수동 매수 기록 (실주문 아님 — 신호 스냅샷 + 3거래일 평가)">
            📔 매수 기록
          </button>
        </div>
      </div>

      <ManualJournalModal v-if="showJournal" :stock-code="stockCode" @close="showJournal = false" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';
import ManualJournalModal from './ManualJournalModal.vue';

const props = defineProps({
  stockCode: { type: String, required: true }
});
defineEmits(['close']);

const checklist = ref(null);
const loading = ref(false);
const error = ref(false);
const showJournal = ref(false);   // 📔 수동 매수 기록 모달 (중첩 — z-index 상위)

const fetchChecklist = async (code) => {
  if (!code) return;
  loading.value = true;
  error.value = false;
  try {
    const { data } = await apiClient.get(`/stock/${code}/checklist`);
    if (data?.success) {
      checklist.value = data.data;
    } else {
      error.value = true;
    }
  } catch (e) {
    error.value = true;
  } finally {
    loading.value = false;
  }
};

watch(() => props.stockCode, (code) => fetchChecklist(code), { immediate: true });

const recommendationLabel = computed(() => {
  switch (checklist.value?.recommendation) {
    case 'STRONG': return '진입 권장';
    case 'MODERATE': return '양호';
    case 'CAUTION': return '신중';
    case 'NOT_RECOMMENDED': return '진입 비권장';
    default: return '-';
  }
});

const recommendationClass = computed(() => 'rec-' + (checklist.value?.recommendation || 'wait').toLowerCase().replace('_', '-'));

const dimensionLabel = (dim) => {
  switch (dim) {
    case 'SHORT': return '단기';
    case 'MID': return '중기';
    case 'LONG': return '장기';
    case 'META': return '필수';
    default: return '';
  }
};
</script>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.65);
  display: flex; align-items: center; justify-content: center;
  z-index: 9999;
}
.modal-content {
  background: #1a1f2e;
  border-radius: 14px;
  width: min(560px, 92vw);
  max-height: 90vh;
  overflow-y: auto;
  color: #fff;
  box-shadow: 0 12px 36px rgba(0,0,0,0.5);
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.modal-header h2 { margin: 0; font-size: 17px; }
.close-btn {
  background: none; border: none; color: #fff;
  font-size: 26px; cursor: pointer; padding: 0; width: 32px; height: 32px;
}
.modal-body { padding: 18px; }
.modal-body.loading,
.modal-body.error { text-align: center; opacity: 0.7; }

.summary {
  display: grid; grid-template-columns: auto 1fr;
  grid-template-rows: auto auto;
  gap: 6px 14px;
  padding: 12px 16px;
  border-radius: 10px;
  margin-bottom: 16px;
}
.rec-strong            { background: rgba(34, 197, 94, 0.15);   border: 1px solid rgba(34, 197, 94, 0.4); }
.rec-moderate          { background: rgba(59, 130, 246, 0.15);  border: 1px solid rgba(59, 130, 246, 0.4); }
.rec-caution           { background: rgba(234, 179, 8, 0.15);   border: 1px solid rgba(234, 179, 8, 0.4); }
.rec-not-recommended   { background: rgba(239, 68, 68, 0.15);   border: 1px solid rgba(239, 68, 68, 0.4); }

.rec-tag { font-weight: 700; font-size: 14px; }
.rec-progress { font-size: 14px; }
.rec-progress .passed { font-weight: 700; font-size: 18px; }
.rec-progress .divider { margin: 0 4px; opacity: 0.6; }
.rec-progress .total { opacity: 0.7; }
.rec-progress .rec-text { margin-left: 8px; opacity: 0.7; }
.rec-summary { grid-column: 1 / -1; margin: 4px 0 0; font-size: 13px; opacity: 0.85; }

.items { list-style: none; padding: 0; margin: 0; }
.item {
  display: flex; gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.item:last-child { border-bottom: none; }
.item-status { width: 32px; flex-shrink: 0; }
.check-icon { font-size: 18px; }
.item-body { flex: 1; }
.item-header { display: flex; justify-content: space-between; align-items: center; }
.item-label { font-weight: 600; font-size: 14px; }
.item-dim {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(255,255,255,0.08);
}
.dim-short { color: #fca5a5; }
.dim-mid   { color: #93c5fd; }
.dim-long  { color: #86efac; }
.dim-meta  { color: #d1d5db; }
.item-meta { font-size: 12px; opacity: 0.8; margin-top: 4px; }
.item-value { font-weight: 600; margin-right: 8px; }
.item-threshold { opacity: 0.65; }
.item-note { font-size: 12px; opacity: 0.65; margin-top: 4px; line-height: 1.4; }

.modal-footer {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 11px;
}
.modal-footer .hint { opacity: 0.55; text-align: left; }
.journal-btn {
  flex-shrink: 0;
  background: rgba(234, 179, 8, 0.14);
  color: #fde68a;
  border: 1px solid rgba(253, 230, 138, 0.35);
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.journal-btn:hover { background: rgba(234, 179, 8, 0.26); }
</style>
