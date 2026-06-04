<template>
  <!-- 지지/저항 레벨 (피벗 클러스터링) — StockDetailDashboard 에서 분리 (P2-10) -->
  <section class="sr-section">
    <div class="sr-header">
      <span class="sr-header-icon">🛡️</span>
      <h3 class="sr-title">지지/저항 레벨</h3>
      <InfoTooltip title="자주 닿은 가격대">
        <p><strong>저항선</strong>(▲ 빨강, 위): 가격이 올라갈 때 매도 압력 강한 가격.
          깨고 올라가기 어려움.</p>
        <p><strong>지지선</strong>(▼ 파랑, 아래): 가격이 내려갈 때 매수 압력 강한 가격.
          받쳐줌.</p>
        <p><strong>강도</strong>: 같은 가격대 닿은 횟수. 강(3+) > 중(2) > 약(1).
          많이 닿을수록 의미 큼.</p>
        <div class="tip-row" style="margin-top:8px"><b>활용</b></div>
        <div class="tip-row">강한 지지 근처 → <em>매수 검토</em> (반등 가능성)</div>
        <div class="tip-row">강한 저항 근처 → <em>매도/관망</em></div>
        <div class="tip-row">지지 깨짐 → 다음 지지선까지 추가 하락</div>
        <div class="tip-row">저항 돌파(거래량↑) → 추세 전환 가능</div>
      </InfoTooltip>
      <span class="sr-disclaimer">최근 90일 피벗 기준</span>
    </div>
    <div class="sr-body">
      <!-- 위 저항선 (가까운 순으로 거꾸로 — 화면 위에서 멀리, 아래로 가까이) -->
      <div v-if="supportResistance.resistance?.length > 0" class="sr-list">
        <div v-for="(lv, i) in [...supportResistance.resistance].reverse()" :key="'r'+i"
             class="sr-row sr-resistance" :class="'st-' + lv.strength?.toLowerCase()">
          <span class="sr-arrow">▲</span>
          <span class="sr-price">{{ Number(lv.price).toLocaleString() }}원</span>
          <span class="sr-touches">{{ lv.touches }}회 터치</span>
          <span class="sr-strength" :class="'st-' + lv.strength?.toLowerCase()">{{ getSrStrengthLabel(lv.strength) }}</span>
          <span class="sr-distance">+{{ Number(lv.distancePct).toFixed(1) }}%</span>
        </div>
      </div>
      <!-- 현재가 -->
      <div class="sr-current">
        <span class="sr-current-label">현재가</span>
        <span class="sr-current-price">{{ Number(supportResistance.currentPrice).toLocaleString() }}원</span>
      </div>
      <!-- 아래 지지선 -->
      <div v-if="supportResistance.support?.length > 0" class="sr-list">
        <div v-for="(lv, i) in supportResistance.support" :key="'s'+i"
             class="sr-row sr-support" :class="'st-' + lv.strength?.toLowerCase()">
          <span class="sr-arrow">▼</span>
          <span class="sr-price">{{ Number(lv.price).toLocaleString() }}원</span>
          <span class="sr-touches">{{ lv.touches }}회 터치</span>
          <span class="sr-strength" :class="'st-' + lv.strength?.toLowerCase()">{{ getSrStrengthLabel(lv.strength) }}</span>
          <span class="sr-distance">{{ Number(lv.distancePct).toFixed(1) }}%</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import InfoTooltip from '../InfoTooltip.vue';

defineProps({
  supportResistance: { type: Object, required: true }
});

// StockDetailDashboard 에서 이동, 로직 동일.
const getSrStrengthLabel = (s) => ({ HIGH: '강', MEDIUM: '중', LOW: '약' }[s] || s || '중');
</script>

<style scoped>
/* ========== 지지/저항 레벨 섹션 ========== */
.sr-section {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.sr-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.sr-header-icon { font-size: 18px; }
.sr-title { margin: 0; color: rgba(255,255,255,0.9); font-size: 15px; font-weight: 600; flex: 1; }
.sr-disclaimer {
  font-size: 11px; color: rgba(255,255,255,0.4);
  padding: 2px 8px; background: rgba(255,255,255,0.05); border-radius: 10px;
}
.sr-body { display: flex; flex-direction: column; gap: 4px; }
.sr-list { display: flex; flex-direction: column; gap: 4px; }
.sr-row {
  display: grid;
  grid-template-columns: 18px 1fr auto auto auto;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: rgba(255,255,255,0.03);
  border-radius: 6px;
  font-size: 13px;
}
.sr-resistance .sr-arrow { color: #ef4444; }   /* 한국 관행: 위로=빨강 */
.sr-support .sr-arrow { color: #3b82f6; }      /* 아래로=파랑 */
.sr-row.st-high { background: rgba(255,255,255,0.06); border-left: 2px solid #4ade80; padding-left: 8px; }
.sr-row.st-medium { border-left: 2px solid #facc15; padding-left: 8px; }
.sr-price { color: rgba(255,255,255,0.95); font-variant-numeric: tabular-nums; font-weight: 600; }
.sr-touches { color: rgba(255,255,255,0.55); font-size: 11px; }
.sr-strength {
  font-size: 11px; padding: 1px 6px; border-radius: 8px; min-width: 18px; text-align: center;
}
.sr-strength.st-high { background: rgba(34,197,94,0.18); color: #4ade80; }
.sr-strength.st-medium { background: rgba(234,179,8,0.18); color: #facc15; }
.sr-strength.st-low { background: rgba(156,163,175,0.18); color: #d1d5db; }
.sr-distance {
  font-size: 11px; color: rgba(255,255,255,0.5);
  font-variant-numeric: tabular-nums; min-width: 50px; text-align: right;
}
.sr-resistance .sr-distance { color: #f87171; }
.sr-support .sr-distance { color: #60a5fa; }
.sr-current {
  display: flex; align-items: center; justify-content: center; gap: 10px;
  padding: 8px; margin: 6px 0;
  background: rgba(99,102,241,0.12);
  border: 1px dashed rgba(99,102,241,0.35);
  border-radius: 6px;
}
.sr-current-label { font-size: 11px; color: rgba(255,255,255,0.5); }
.sr-current-price {
  color: #fff; font-weight: 700; font-size: 15px;
  font-variant-numeric: tabular-nums;
}
</style>
