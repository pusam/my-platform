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
        <p v-if="conclusion.conflictNote" class="conflict-note">{{ conclusion.conflictNote }}</p>
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
        <div class="factor-score">{{ displayScore(f.score) }}</div>
        <div class="factor-note">{{ f.note }}</div>
      </div>
    </div>

    <div v-if="accuracyStat" class="accuracy-line">
      <span class="acc-label">📊 {{ accuracyStat.signalType }} 시그널 지난 30일 적중률</span>
      <span class="acc-rate" :class="accuracyClass">{{ accuracyStat.hitRate }}%</span>
      <span class="acc-detail">({{ accuracyStat.hitCount }}/{{ accuracyStat.totalSignals }}건, 평균 {{ accuracyStat.avgPctChange }}%)</span>
    </div>
    <div v-else-if="accuracyEmpty" class="accuracy-line empty">
      <span class="acc-label">📊 적중률 데이터 누적 중 — 3일 후 첫 평가 결과 확보</span>
    </div>

    <div v-if="conclusion.dataAt" class="conclusion-meta">
      <span class="freshness-dot" :class="freshnessClass"
            :title="`데이터 ${minutesAgo}분 경과 — ${freshnessLabel}`"></span>
      <span>스냅샷: {{ formatDataAt(conclusion.dataAt) }} · {{ minutesAgo }}분 전</span>
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
const accuracyStats = ref([]);   // 전체 시그널 타입별 통계 (배열)
const accuracyEmpty = ref(false); // 데이터 누적 중 표시 플래그

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

const fetchAccuracy = async () => {
  try {
    const { data } = await apiClient.get('/signal-outcomes/accuracy', { params: { days: 30 } });
    if (data?.success) {
      const stats = data.data?.stats || [];
      accuracyStats.value = stats;
      accuracyEmpty.value = stats.length === 0;
    }
  } catch (e) {
    // 데이터 부족 / API 오류 시 조용히 무시
  }
};

watch(() => props.stockCode, (code) => {
  fetchConclusion(code);
  fetchAccuracy();
}, { immediate: true });

// 현재 결론 level 에 해당하는 시그널 타입의 적중률 통계 1건.
// STRONG_BUY/BUY level 만 노출 (HOLD/WAIT 은 시그널이 발생하지 않으므로 통계 없음).
const accuracyStat = computed(() => {
  const level = conclusion.value?.level;
  if (level !== 'STRONG_BUY' && level !== 'BUY') return null;
  return accuracyStats.value.find(s => s.signalType === level) || null;
});

const accuracyClass = computed(() => {
  const rate = Number(accuracyStat.value?.hitRate || 0);
  if (rate >= 60) return 'acc-good';
  if (rate >= 40) return 'acc-mid';
  return 'acc-low';
});

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

// 점수 표시 — 음수(-1)는 "데이터 없음(NA)" 의미이므로 "—" 로 표기 (밸류/성장성 등 LONG factor).
const displayScore = (score) => (score == null || score < 0 ? '—' : score);

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

// 신선도 신호등 (phase 23) — dataAt 기준 경과 분
const minutesAgo = computed(() => {
  if (!conclusion.value?.dataAt) return 0;
  try {
    const ms = Date.now() - new Date(conclusion.value.dataAt).getTime();
    return Math.max(0, Math.floor(ms / 60000));
  } catch { return 0; }
});
const freshnessClass = computed(() => {
  const m = minutesAgo.value;
  if (m <= 5) return 'fresh-good';   // 녹: 5분 이내
  if (m <= 15) return 'fresh-mid';   // 노: 5~15분
  return 'fresh-stale';              // 빨: 15분 초과
});
const freshnessLabel = computed(() => {
  switch (freshnessClass.value) {
    case 'fresh-good': return '신선';
    case 'fresh-mid': return '주의 — 곧 갱신';
    case 'fresh-stale': return 'stale — 다음 스냅샷 대기 권장';
    default: return '';
  }
});

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
.conflict-note {
  margin: 8px 0 0;
  font-size: 12.5px;
  padding: 6px 10px;
  background: rgba(234, 179, 8, 0.10);
  border-left: 3px solid #eab308;
  border-radius: 4px;
  line-height: 1.45;
}
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
  opacity: 0.75;
  text-align: right;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
}
.freshness-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.fresh-good  { background: #22c55e; box-shadow: 0 0 6px rgba(34, 197, 94, 0.5); }
.fresh-mid   { background: #eab308; box-shadow: 0 0 6px rgba(234, 179, 8, 0.5); }
.fresh-stale { background: #ef4444; box-shadow: 0 0 6px rgba(239, 68, 68, 0.5); }

.accuracy-line {
  margin-top: 12px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 6px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.accuracy-line.empty {
  opacity: 0.55;
}
.acc-label { opacity: 0.8; }
.acc-rate {
  font-weight: 700;
  font-size: 14px;
  padding: 2px 8px;
  border-radius: 4px;
}
.acc-good { color: #22c55e; background: rgba(34, 197, 94, 0.18); }
.acc-mid  { color: #eab308; background: rgba(234, 179, 8, 0.18); }
.acc-low  { color: #ef4444; background: rgba(239, 68, 68, 0.18); }
.acc-detail { opacity: 0.65; font-size: 11px; }
</style>
