<template>
  <div class="backtest-panel">
    <div class="bt-header">
      <h2>📊 추천 트랙레코드 <span class="bt-sub">— 과거 추천이 실제로 먹혔나 (거래비용 차감 순수익)</span></h2>
      <div class="bt-days">
        <button v-for="d in [30, 60, 90]" :key="d"
                :class="['bt-day-btn', { active: days === d }]"
                @click="changeDays(d)">{{ d }}일</button>
      </div>
    </div>

    <div v-if="loading" class="bt-state">트랙레코드 계산 중...</div>
    <div v-else-if="error" class="bt-state">트랙레코드를 불러오지 못했습니다.</div>
    <div v-else-if="!overall || overall.totalPicks === 0" class="bt-state">
      집계할 추천 이력이 아직 없습니다 — 추천 누적 후 표시됩니다.
    </div>

    <template v-else>
      <div class="bt-overall">
        <div class="bt-stat">
          <span class="bt-label">적중률</span>
          <span class="bt-value" :class="rateClass(overall.hitRate)">{{ overall.hitRate }}%</span>
          <span class="bt-detail">{{ overall.winCount }}/{{ overall.totalPicks }}건</span>
        </div>
        <div class="bt-stat">
          <span class="bt-label">평균 수익률</span>
          <span class="bt-value" :class="signClass(overall.avgReturn)">{{ signed(overall.avgReturn) }}%</span>
        </div>
        <div class="bt-stat">
          <span class="bt-label">MDD</span>
          <span class="bt-value">-{{ overall.mdd }}%</span>
        </div>
        <div class="bt-stat">
          <span class="bt-label">Sharpe</span>
          <span class="bt-value">{{ overall.sharpeRatio }}</span>
        </div>
      </div>

      <table class="bt-table">
        <thead>
          <tr>
            <th>전략</th><th>표본</th><th>적중률</th><th>평균수익</th><th>최고</th><th>최악</th><th>MDD</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in strategies" :key="s.strategyType">
            <td class="bt-strategy">{{ s.label }}</td>
            <td>{{ s.totalPicks }}건</td>
            <td :class="rateClass(s.hitRate)">{{ s.hitRate }}%</td>
            <td :class="signClass(s.avgReturn)">{{ signed(s.avgReturn) }}%</td>
            <td class="bt-hint" :class="signClass(s.bestReturn)" :title="s.bestStock || ''">{{ s.bestReturn != null ? signed(s.bestReturn) + '%' : '—' }}</td>
            <td class="bt-hint" :class="signClass(s.worstReturn)" :title="s.worstStock || ''">{{ s.worstReturn != null ? signed(s.worstReturn) + '%' : '—' }}</td>
            <td>-{{ s.mdd }}%</td>
          </tr>
        </tbody>
      </table>
      <p class="bt-caption">
        ⓘ 최근 {{ days }}일 AI 전략 TOP3 첫 추천 종목의 현재가 대비 성과. 수수료 0.015%×2 + 세금 0.18% + 전략별 슬리피지 차감 기준.
      </p>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import apiClient from '../../utils/api';

const days = ref(30);
const loading = ref(false);
const error = ref(false);
const overall = ref(null);
const strategies = ref([]);

const fetchPerformance = async () => {
  loading.value = true;
  error.value = false;
  try {
    const { data } = await apiClient.get('/backtest/performance', { params: { days: days.value } });
    if (data?.success) {
      overall.value = data.data?.overall || null;
      strategies.value = data.data?.strategies || [];
    } else {
      error.value = true;
    }
  } catch (e) {
    error.value = true;
  } finally {
    loading.value = false;
  }
};

const changeDays = (d) => {
  if (days.value === d) return;
  days.value = d;
  fetchPerformance();
};

const signed = (v) => (v == null ? '—' : `${Number(v) > 0 ? '+' : ''}${v}`);
// ±수익률은 한국 관례색(+빨강/−파랑) — 적중률 신호등(rateClass, 초록=좋음)과 극성이 다르다.
const signClass = (v) => (Number(v) > 0 ? 'bt-up' : Number(v) < 0 ? 'bt-down' : '');
const rateClass = (v) => {
  const r = Number(v || 0);
  if (r >= 60) return 'bt-pos';
  if (r >= 40) return 'bt-mid';
  return 'bt-neg';
};

onMounted(fetchPerformance);
</script>

<style scoped>
.backtest-panel {
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border-radius: var(--surface-radius, 12px);
  padding: 18px 20px;
  margin-top: 24px;
  color: #fff;
}
.bt-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.bt-header h2 { margin: 0; font-size: 17px; }
.bt-sub { font-size: 12px; font-weight: 400; opacity: 0.6; }
.bt-days { display: flex; gap: 6px; }
.bt-day-btn {
  background: rgba(255, 255, 255, 0.06);
  color: #cbd5e1;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
}
.bt-day-btn.active {
  background: rgba(59, 130, 246, 0.25);
  color: #93c5fd;
  border-color: rgba(147, 197, 253, 0.4);
}
.bt-state { padding: 22px 0; text-align: center; font-size: 13px; opacity: 0.65; }

.bt-overall {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.bt-stat {
  background: rgba(255, 255, 255, 0.04);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.bt-label { font-size: 11px; opacity: 0.65; }
.bt-value { font-size: 19px; font-weight: 700; }
.bt-detail { font-size: 11px; opacity: 0.55; }

.bt-table {
  width: 100%;
  margin-top: 14px;
  border-collapse: collapse;
  font-size: 12.5px;
}
.bt-table th {
  text-align: left;
  font-size: 11px;
  opacity: 0.6;
  font-weight: 600;
  padding: 6px 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.bt-table td {
  padding: 7px 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}
.bt-strategy { font-weight: 600; }
/* 적중률 신호등 (good/bad) */
.bt-pos { color: #4ade80; }
.bt-mid { color: #eab308; }
.bt-neg { color: #f87171; }
/* ±수익률 — 앱 공통 한국 관례(상승=빨강/하락=파랑, --stock-up/--stock-down 동기) */
.bt-up { color: #f87171; }
.bt-down { color: #60a5fa; }
.bt-hint { cursor: help; }
.bt-caption { margin: 10px 0 0; font-size: 11px; opacity: 0.55; }
</style>
