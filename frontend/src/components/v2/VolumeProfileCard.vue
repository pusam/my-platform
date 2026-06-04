<template>
  <!-- Volume Profile (가격대별 누적 거래량) — StockDetailDashboard 에서 분리 (P2-10) -->
  <section class="vp-section">
    <div class="vp-header">
      <span class="vp-header-icon">📊</span>
      <h3 class="vp-title">Volume Profile</h3>
      <InfoTooltip title="가격대별 누적 거래량">
        <p><strong>POC</strong>(노란 막대): 90일간 가장 거래 많이 일어난 가격.
          시장이 "공정가" 로 보는 자석 — 가격이 멀어지면 돌아오려는 경향.</p>
        <p><strong>Value Area</strong>(보라 영역): 누적 70% 거래가 일어난 구간.
          대부분 거래가 이 안에서 발생.</p>
        <div class="tip-row" style="margin-top:8px"><b>활용</b></div>
        <div class="tip-row">현재가 < VAL → <em>저평가</em> 영역, 매수 검토</div>
        <div class="tip-row">현재가 > VAH → <em>과열</em> 영역, 매도 검토</div>
        <div class="tip-row">큰 막대 = 미래 지지/저항 가능성 ↑</div>
      </InfoTooltip>
      <span class="vp-stat">POC <strong>{{ Number(volumeProfile.poc).toLocaleString() }}원</strong></span>
      <span class="vp-stat">VA {{ Number(volumeProfile.val).toLocaleString() }} ~ {{ Number(volumeProfile.vah).toLocaleString() }}</span>
      <span class="vp-disclaimer">{{ volumeProfile.periodDays }}일 누적</span>
    </div>
    <div class="vp-grid">
      <!-- 가격 높은 순으로 위→아래 배치 -->
      <div v-for="(bin, i) in [...volumeProfile.bins].reverse()" :key="'vp'+i"
           class="vp-row"
           :class="{
             'vp-poc': isVpPoc(bin),
             'vp-in-va': isVpInValueArea(bin)
           }">
        <span class="vp-price">{{ Math.round((Number(bin.priceLow) + Number(bin.priceHigh)) / 2 / 100) * 100 }}</span>
        <div class="vp-bar-wrap">
          <div class="vp-bar" :style="{ width: vpBarWidth(bin) + '%' }"></div>
        </div>
        <span class="vp-pct">{{ bin.volumePct.toFixed(1) }}%</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue';
import InfoTooltip from '../InfoTooltip.vue';

const props = defineProps({
  volumeProfile: { type: Object, required: true }
});

// ===== Volume Profile helpers (StockDetailDashboard 에서 이동, 로직 동일) =====
const vpMaxPct = computed(() => {
  if (!props.volumeProfile?.bins) return 1;
  return Math.max(...props.volumeProfile.bins.map(b => b.volumePct), 1);
});
const vpBarWidth = (bin) => (bin.volumePct / vpMaxPct.value) * 100;
const isVpPoc = (bin) => {
  if (!props.volumeProfile) return false;
  const poc = Number(props.volumeProfile.poc);
  return poc >= Number(bin.priceLow) && poc < Number(bin.priceHigh);
};
const isVpInValueArea = (bin) => {
  if (!props.volumeProfile) return false;
  const vah = Number(props.volumeProfile.vah);
  const val = Number(props.volumeProfile.val);
  const mid = (Number(bin.priceLow) + Number(bin.priceHigh)) / 2;
  return mid >= val && mid <= vah;
};
</script>

<style scoped>
/* ========== Volume Profile 섹션 ========== */
.vp-section {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.vp-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.vp-header-icon { font-size: 18px; }
.vp-title { margin: 0; color: rgba(255,255,255,0.9); font-size: 15px; font-weight: 600; }
.vp-stat {
  font-size: 11px; color: rgba(255,255,255,0.65);
  padding: 3px 8px; background: rgba(255,255,255,0.06);
  border-radius: 10px;
  font-variant-numeric: tabular-nums;
}
.vp-stat strong { color: #fff; }
.vp-disclaimer {
  font-size: 11px; color: rgba(255,255,255,0.4);
  padding: 2px 8px; background: rgba(255,255,255,0.05); border-radius: 10px;
  margin-left: auto;
}
.vp-grid { display: flex; flex-direction: column; gap: 1px; }
.vp-row {
  display: grid;
  grid-template-columns: 60px 1fr 50px;
  align-items: center;
  gap: 8px;
  padding: 2px 4px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  border-radius: 3px;
}
.vp-row.vp-in-va { background: rgba(99,102,241,0.06); }
.vp-row.vp-poc { background: rgba(234,179,8,0.15); }
.vp-price { color: rgba(255,255,255,0.7); text-align: right; }
.vp-row.vp-poc .vp-price { color: #facc15; font-weight: 700; }
.vp-bar-wrap { height: 10px; background: rgba(255,255,255,0.03); border-radius: 2px; overflow: hidden; }
.vp-bar {
  height: 100%;
  background: linear-gradient(90deg, rgba(99,102,241,0.4), rgba(99,102,241,0.7));
  border-radius: 2px;
  transition: width 0.3s;
}
.vp-row.vp-poc .vp-bar { background: linear-gradient(90deg, rgba(234,179,8,0.5), #facc15); }
.vp-pct { color: rgba(255,255,255,0.55); text-align: right; }
.vp-row.vp-poc .vp-pct { color: #facc15; font-weight: 700; }
</style>
