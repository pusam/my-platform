<template>
  <!-- 📜 신호 이력 (signal_outcome 90일 재사용, read-only) — n=0 이면 섹션 자체 미렌더.
       DetailSection 접기 패턴(심화 영역) + 요약은 제목에 병기(접힌 상태에서도 보임). -->
  <DetailSection v-if="(history && history.items && history.items.length) || myTrades.length" :title="sectionTitle">
    <div class="sh-body">
      <div class="sh-note">
        과거 시그널의 3거래일 평가 실측(적중 = α≥0 &amp; 상승) — 표시 전용, 점수 산식 미편입.
        평가 대기 = 3거래일 미도래(미적중 아님).
      </div>

      <!-- 📔 내 매수/매도 마커 (수동 저널) — 시그널과 같은 잣대의 3거래일 평가 병기. 없으면 미표시. -->
      <div v-if="myTrades.length" class="sh-my-trades">
        <div v-for="j in myTrades" :key="'mt-' + j.id" class="sh-row sh-my-row">
          <span class="sh-date">{{ fmtDate(j.buyAt) }}</span>
          <span class="sh-type">📔 내 매수</span>
          <span class="sh-score">{{ fmtPrice(j.buyPrice) }}원</span>
          <template v-if="j.evaluatedAt">
            <span class="sh-result" :class="j.hit ? 'hit' : 'miss'">{{ j.hit ? '✅ 적중' : '❌ 미적중' }}</span>
            <span class="sh-pct" :class="pctClass(j.pctChange3d)">{{ signedPct(j.pctChange3d) }}</span>
            <span class="sh-alpha" v-if="j.alpha3d != null">α {{ signedPct(j.alpha3d) }}</span>
          </template>
          <span v-else class="sh-result pending">⏳ 평가 대기</span>
          <span v-if="j.sellAt" class="sh-sell" :class="pctClass(j.realizedPct)">
            → {{ fmtDate(j.sellAt) }} 매도 {{ signedPct(j.realizedPct) }}
          </span>
          <span v-else class="sh-sell holding">보유 중</span>
        </div>
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
import apiClient, { manualJournalAPI } from '../../utils/api';
import DetailSection from './DetailSection.vue';

const props = defineProps({
  stockCode: { type: String, required: true }
});

const history = ref(null);
const myTrades = ref([]);   // 📔 내 수동 매매(저널) 마커 — best-effort, 실패/0건 = 미표시

// 요청 시퀀스 토큰 — 종목 전환 시 늦게 온 이전 종목 응답이 현재 종목 이력을 덮어쓰는 경합 방지
let reqSeq = 0;

// heavy(2단) 계열 — quick 경로와 독립 fetch(지연 없음), 실패 시 조용히 미렌더
const load = async (code) => {
  history.value = null;
  if (!code) return;
  const seq = ++reqSeq;
  try {
    const { data } = await apiClient.get(`/stock/${code}/signal-history`);
    if (seq !== reqSeq) return;   // 종목 전환됨 — 폐기
    if (data?.success) history.value = data.data;
  } catch (e) { /* 섹션 미렌더 */ }
};

const loadMyTrades = async (code) => {
  myTrades.value = [];
  if (!code) return;
  const seq = reqSeq;   // load 와 같은 전환 사이클 공유
  try {
    const { data } = await manualJournalAPI.listByStock(code);
    if (seq !== reqSeq) return;
    if (data?.success) myTrades.value = data.data || [];
  } catch (e) { /* 마커 미표시 */ }
};

watch(() => props.stockCode, (code) => { load(code); loadMyTrades(code); }, { immediate: true });

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
const fmtPrice = (v) => (v == null ? '-' : Number(v).toLocaleString());
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
.sh-my-trades { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
.sh-my-row {
  background: rgba(234, 179, 8, 0.07);
  border: 1px solid rgba(234, 179, 8, 0.25);
}
.sh-sell { font-size: 11.5px; opacity: 0.85; }
.sh-sell.holding { opacity: 0.55; }
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
