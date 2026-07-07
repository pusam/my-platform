<template>
  <!-- 📜 신호 이력 (signal_outcome 90일 재사용, read-only) — n=0 이면 섹션 자체 미렌더.
       DetailSection 접기 패턴(심화 영역) + 요약은 제목에 병기(접힌 상태에서도 보임). -->
  <DetailSection v-if="history && history.items && history.items.length" :title="sectionTitle">
    <div class="sh-body">
      <div class="sh-note">
        과거 시그널의 3거래일 평가 실측(적중 = α≥0 &amp; 상승) — 표시 전용, 점수 산식 미편입.
        평가 대기 = 3거래일 미도래(미적중 아님).
      </div>
      <div class="sh-list">
        <div v-for="(it, i) in history.items" :key="'sh-' + i" class="sh-row" :class="rowClass(it)">
          <span class="sh-date">{{ fmtDate(it.signalDate) }}</span>
          <span class="sh-type">{{ typeLabel(it.signalType) }}</span>
          <span class="sh-score" v-if="it.signalScore != null">{{ it.signalScore }}점</span>
          <span class="sh-result" :class="resultClass(it)">{{ resultLabel(it) }}</span>
          <span class="sh-pct" v-if="!it.pending" :class="pctClass(it.pctChange3d)">{{ signedPct(it.pctChange3d) }}</span>
          <span class="sh-alpha" v-if="!it.pending && it.alpha3d != null">α {{ signedPct(it.alpha3d) }}</span>
        </div>
      </div>
    </div>
  </DetailSection>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import apiClient from '../../utils/api';
import DetailSection from './DetailSection.vue';

const props = defineProps({
  stockCode: { type: String, required: true }
});

const history = ref(null);

// heavy(2단) 계열 — quick 경로와 독립 fetch(지연 없음), 실패 시 조용히 미렌더
const load = async (code) => {
  history.value = null;
  if (!code) return;
  try {
    const { data } = await apiClient.get(`/stock/${code}/signal-history`);
    if (data?.success) history.value = data.data;
  } catch (e) { /* 섹션 미렌더 */ }
};

watch(() => props.stockCode, load, { immediate: true });

// 요약 배지를 제목에 병기 — "최근 90일 5회 중 3회 적중 · 평균 α +1.2%" (접힌 상태에서도 보임)
const sectionTitle = computed(() => {
  const s = history.value?.summary;
  if (!s) return '📜 신호 이력';
  const parts = [];
  if (s.evaluatedCount > 0) {
    parts.push(`최근 ${history.value.windowDays}일 ${s.evaluatedCount}회 중 ${s.hitCount}회 적중`);
    if (s.avgAlpha != null) parts.push(`평균 α ${signedPct(s.avgAlpha)}`);
  }
  if (s.pendingCount > 0) parts.push(`평가 대기 ${s.pendingCount}건`);
  return parts.length ? `📜 신호 이력 — ${parts.join(' · ')}` : '📜 신호 이력';
});

const typeLabel = (t) => ({ STRONG_BUY: '강력매수', BUY: '매수' }[t] || t);
const resultLabel = (it) => (it.pending ? '⏳ 평가 대기' : it.hit ? '✅ 적중' : '❌ 미적중');
const resultClass = (it) => (it.pending ? 'pending' : it.hit ? 'hit' : 'miss');
const rowClass = (it) => (it.pending ? 'row-pending' : '');
const pctClass = (v) => {
  if (v == null) return '';
  const n = Number(v);
  return n > 0 ? 'positive' : n < 0 ? 'negative' : '';
};
const signedPct = (v) => (v == null ? '—' : `${Number(v) > 0 ? '+' : ''}${Number(v).toFixed(1)}%`);
const fmtDate = (d) => {
  const m = String(d || '').match(/^\d{4}-(\d{2})-(\d{2})/);
  return m ? `${Number(m[1])}/${Number(m[2])}` : d;
};
</script>

<style scoped>
.sh-body { padding: 4px 16px 14px; color: #e2e8f0; }
.sh-note {
  font-size: 11px; line-height: 1.5; opacity: 0.6; margin-bottom: 8px;
  border-left: 2px solid rgba(148, 163, 184, 0.4); padding-left: 8px;
}
.sh-list { display: flex; flex-direction: column; gap: 4px; }
.sh-row {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  font-size: 12.5px; padding: 6px 10px; border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
}
.sh-row.row-pending { opacity: 0.65; }
.sh-date { font-variant-numeric: tabular-nums; opacity: 0.7; min-width: 38px; }
.sh-type { font-weight: 600; }
.sh-score { font-size: 11px; opacity: 0.6; }
.sh-result { font-size: 12px; font-weight: 600; }
.sh-result.hit { color: #4ade80; }
.sh-result.miss { color: #f87171; }
.sh-result.pending { color: #cbd5e1; }
.sh-pct, .sh-alpha { font-variant-numeric: tabular-nums; }
.sh-alpha { font-size: 11.5px; opacity: 0.75; }
.positive { color: #4ade80; }
.negative { color: #f87171; }
</style>
