<template>
  <div class="mj-section">
    <div class="mj-header">
      <h2>📔 수동 매매 저널</h2>
      <button class="mj-refresh" :disabled="loading" @click="reload">
        {{ loading ? '불러오는 중…' : '🔄 새로고침' }}
      </button>
    </div>
    <p class="mj-caption">
      직접 한 매매의 기록. 매수 시점 신호가 자동 스냅샷되고 <b>3거래일 후 시그널과 같은 잣대</b>(α≥0 & 상승)로
      평가됩니다. 실주문·봇과는 무관한 기록 전용.
    </p>

    <!-- 통계 카드 -->
    <div v-if="stats" class="mj-stats">
      <div class="mj-stat">
        <span class="stat-label">기록</span>
        <span class="stat-value">{{ stats.totalTrades }}건</span>
        <span class="stat-sub">보유 {{ stats.openTrades }} · 매도 {{ stats.closedTrades }}</span>
      </div>
      <div class="mj-stat">
        <span class="stat-label">3일 적중률</span>
        <span class="stat-value">{{ pctOrDash(stats.hitRate) }}</span>
        <span class="stat-sub">{{ stats.hitCount }}/{{ stats.evaluatedTrades }}건 평가</span>
      </div>
      <div class="mj-stat">
        <span class="stat-label">평균 α (3일)</span>
        <span class="stat-value" :class="signClass(stats.avgAlpha3d)">{{ signedPctOrDash(stats.avgAlpha3d) }}</span>
        <span class="stat-sub">vs KOSPI</span>
      </div>
      <div class="mj-stat">
        <span class="stat-label">실현 승률</span>
        <span class="stat-value">{{ pctOrDash(stats.realizedWinRate) }}</span>
        <span class="stat-sub">평균 {{ signedPctOrDash(stats.avgRealizedPct) }}</span>
      </div>
    </div>
    <p v-if="stats && stats.insufficientSample" class="mj-sample-note">
      ⚠ 평가 표본 {{ stats.evaluatedTrades }}건 &lt; 10 — 통계 신뢰 낮음(누적 중)
    </p>

    <!-- 조건별 breakdown (표본 있는 것만) -->
    <div v-if="visibleBreakdowns.length" class="mj-breakdowns">
      <span v-for="b in visibleBreakdowns" :key="b.key" class="mj-bd-chip"
            :title="`평가 ${b.evaluatedTrades}건` + (b.insufficientSample ? ' — 표본 부족' : '')">
        {{ b.label }} {{ pctOrDash(b.hitRate) }}<template v-if="b.insufficientSample">*</template>
      </span>
    </div>

    <!-- 리스트 -->
    <div v-if="entries.length" class="mj-table-wrap">
      <table class="mj-table">
        <thead>
          <tr>
            <th>매수</th>
            <th>종목</th>
            <th class="right">매수가</th>
            <th>스냅샷</th>
            <th>3일 평가</th>
            <th>실현</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="j in entries" :key="j.id">
            <td class="mj-date">{{ fmtDateTime(j.buyAt) }}</td>
            <td>
              <b>{{ j.stockName || j.stockCode }}</b>
              <span class="mj-code">{{ j.stockCode }}</span>
              <div v-if="j.memo" class="mj-memo" :title="j.memo">💬 {{ j.memo }}</div>
            </td>
            <td class="right">
              {{ fmtPrice(j.buyPrice) }}<template v-if="j.quantity"> ×{{ j.quantity }}</template>
            </td>
            <td class="mj-chips">
              <span v-if="j.totalScore != null" class="chip">{{ j.totalScore }}점</span>
              <span v-if="j.rsi != null" class="chip" :class="{ hot: Number(j.rsi) >= 70 }">RSI {{ Number(j.rsi).toFixed(0) }}</span>
              <span v-if="j.catalystType" class="chip" :class="'cat-' + (j.catalystDirection || '').toLowerCase()">🔥 {{ j.catalystType }}</span>
              <span v-if="j.regime" class="chip">{{ regimeLabel(j.regime) }}</span>
            </td>
            <td>
              <template v-if="j.evaluatedAt">
                <span class="mj-hit" :class="j.hit ? 'ok' : 'no'">{{ j.hit ? '✅ 적중' : '❌ 미적중' }}</span>
                <span class="mj-eval" :class="signClass(j.pctChange3d)">{{ signedPctOrDash(j.pctChange3d) }}</span>
                <span v-if="j.alpha3d != null" class="mj-eval dim">α {{ signedPctOrDash(j.alpha3d) }}</span>
              </template>
              <span v-else class="mj-pending">평가 대기</span>
            </td>
            <td>
              <template v-if="j.sellAt">
                <span :class="signClass(j.realizedPct)">{{ signedPctOrDash(j.realizedPct) }}</span>
                <span class="mj-eval dim">{{ fmtPrice(j.sellPrice) }}</span>
              </template>
              <span v-else class="mj-pending">보유 중</span>
            </td>
            <td>
              <button v-if="!j.sellAt" class="mj-sell-btn" @click="openSell(j)">매도 기록</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else-if="!loading" class="mj-empty">
      아직 기록이 없습니다. 종목 상세의 <b>📔 매수 기록</b> 버튼으로 시작하세요.
    </div>

    <!-- 매도 기록 미니 모달 (전량 가정) -->
    <div v-if="sellTarget" class="mj-modal-backdrop" @click.self="sellTarget = null">
      <div class="mj-modal">
        <h3>매도 기록 — {{ sellTarget.stockName || sellTarget.stockCode }}</h3>
        <p class="mj-modal-hint">전량 매도 가정(v1). 실현 수익률이 확정됩니다.</p>
        <label class="mj-field">
          <span>매도가 *</span>
          <input v-model="sellPrice" type="number" step="any" min="1" />
        </label>
        <p v-if="sellError" class="mj-error">{{ sellError }}</p>
        <div class="mj-modal-actions">
          <button class="mj-btn ghost" @click="sellTarget = null">취소</button>
          <button class="mj-btn primary" :disabled="selling" @click="confirmSell">
            {{ selling ? '저장 중…' : '확정' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { manualJournalAPI } from '../../utils/api';

const entries = ref([]);
const stats = ref(null);
const loading = ref(false);

const sellTarget = ref(null);
const sellPrice = ref('');
const sellError = ref('');
const selling = ref(false);

// 표본 0건 bucket 은 숨김(§4c — hitRate null). '*' = 표본 부족(n<10).
const visibleBreakdowns = computed(() =>
  (stats.value?.breakdowns || []).filter(b => b.evaluatedTrades > 0));

const reload = async () => {
  loading.value = true;
  try {
    const [listRes, statsRes] = await Promise.all([
      manualJournalAPI.list(),
      manualJournalAPI.stats()
    ]);
    if (listRes.data?.success) entries.value = listRes.data.data || [];
    if (statsRes.data?.success) stats.value = statsRes.data.data;
  } catch (e) { /* 로드 실패 — 빈 상태 유지 */ } finally {
    loading.value = false;
  }
};
onMounted(reload);

const openSell = (j) => {
  sellTarget.value = j;
  sellPrice.value = '';
  sellError.value = '';
};

const confirmSell = async () => {
  const price = Number(sellPrice.value);
  if (!price || price <= 0) {
    sellError.value = '매도가를 입력해 주세요.';
    return;
  }
  selling.value = true;
  sellError.value = '';
  try {
    const { data } = await manualJournalAPI.close(sellTarget.value.id, price);
    if (data?.success) {
      sellTarget.value = null;
      await reload();
    } else {
      sellError.value = data?.message || '저장에 실패했습니다.';
    }
  } catch (e) {
    sellError.value = '저장에 실패했습니다.';
  } finally {
    selling.value = false;
  }
};

// ---- 표시 헬퍼 (null=데이터 없음 → '-' §4c) ----
const pctOrDash = (v) => (v == null ? '-' : `${Number(v).toFixed(1)}%`);
const signedPctOrDash = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}%`;
};
const signClass = (v) => {
  if (v == null) return '';
  return Number(v) > 0 ? 'pos' : Number(v) < 0 ? 'neg' : '';
};
const fmtPrice = (v) => (v == null ? '-' : Number(v).toLocaleString());
const fmtDateTime = (iso) => {
  if (!iso) return '-';
  const d = new Date(iso);
  if (isNaN(d)) return iso;
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};
const regimeLabel = (r) => ({ BULL: '상승장', BEAR: '하락장', SIDEWAYS: '횡보장' }[r] || r);
</script>

<style scoped>
.mj-section {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 18px;
  color: #fff;
}
.mj-header { display: flex; justify-content: space-between; align-items: center; }
.mj-header h2 { margin: 0; font-size: 17px; }
.mj-refresh {
  background: rgba(255,255,255,0.08); color: #fff;
  border: none; border-radius: 8px; padding: 6px 12px;
  font-size: 12px; cursor: pointer;
}
.mj-caption { font-size: 12px; opacity: 0.6; margin: 6px 0 14px; line-height: 1.5; }

.mj-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}
.mj-stat {
  background: rgba(255,255,255,0.04);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex; flex-direction: column; gap: 2px;
}
.stat-label { font-size: 11px; opacity: 0.6; }
.stat-value { font-size: 18px; font-weight: 700; }
.stat-sub { font-size: 11px; opacity: 0.55; }
.mj-sample-note { font-size: 11px; color: #fde68a; margin: 8px 0 0; }

.mj-breakdowns { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.mj-bd-chip {
  font-size: 11px;
  background: rgba(255,255,255,0.06);
  border-radius: 999px;
  padding: 3px 10px;
  opacity: 0.85;
}

.mj-table-wrap { overflow-x: auto; margin-top: 14px; }
.mj-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.mj-table th {
  text-align: left; font-size: 11px; opacity: 0.6; font-weight: 600;
  padding: 6px 8px; border-bottom: 1px solid rgba(255,255,255,0.1);
}
.mj-table td { padding: 8px; border-bottom: 1px solid rgba(255,255,255,0.05); vertical-align: top; }
.mj-table .right { text-align: right; }
.mj-date { white-space: nowrap; opacity: 0.75; }
.mj-code { margin-left: 6px; font-size: 11px; opacity: 0.5; }
.mj-memo { font-size: 11px; opacity: 0.55; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.mj-chips { display: flex; flex-wrap: wrap; gap: 4px; }
.chip {
  font-size: 11px;
  background: rgba(255,255,255,0.07);
  border-radius: 6px;
  padding: 2px 7px;
  white-space: nowrap;
}
.chip.hot { color: #fca5a5; }
.chip.cat-positive { color: #86efac; }
.chip.cat-negative { color: #fca5a5; }

.mj-hit.ok { color: #86efac; }
.mj-hit.no { color: #fca5a5; }
.mj-eval { margin-left: 6px; }
.mj-eval.dim { opacity: 0.6; font-size: 12px; }
.mj-pending { opacity: 0.5; font-size: 12px; }
.pos { color: #f87171; }   /* 국내 관례: 상승=빨강 */
.neg { color: #60a5fa; }

.mj-sell-btn {
  background: rgba(59, 130, 246, 0.18); color: #93c5fd;
  border: 1px solid rgba(147, 197, 253, 0.35);
  border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer;
  white-space: nowrap;
}
.mj-empty { text-align: center; opacity: 0.6; font-size: 13px; padding: 24px 0 10px; }

.mj-modal-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.65);
  display: flex; align-items: center; justify-content: center; z-index: 10000;
}
.mj-modal {
  background: #1a1f2e; border-radius: 14px; padding: 18px;
  width: min(360px, 92vw); box-shadow: 0 12px 36px rgba(0,0,0,0.5);
}
.mj-modal h3 { margin: 0 0 6px; font-size: 15px; }
.mj-modal-hint { font-size: 12px; opacity: 0.6; margin: 0 0 12px; }
.mj-field span { display: block; font-size: 12px; opacity: 0.75; margin-bottom: 4px; }
.mj-field input {
  width: 100%; box-sizing: border-box;
  background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.14);
  border-radius: 8px; color: #fff; padding: 8px 10px; font-size: 14px;
}
.mj-error { color: #fca5a5; font-size: 12px; margin: 6px 0 0; }
.mj-modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.mj-btn { border: none; border-radius: 8px; cursor: pointer; padding: 8px 16px; font-size: 13px; font-weight: 600; }
.mj-btn.primary { background: #3b82f6; color: #fff; }
.mj-btn.primary:disabled { opacity: 0.6; }
.mj-btn.ghost { background: rgba(255,255,255,0.08); color: #fff; }
</style>
