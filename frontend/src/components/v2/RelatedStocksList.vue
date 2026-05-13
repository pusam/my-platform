<template>
  <section v-if="stocks && stocks.length > 0" class="related-section">
    <div class="related-header">
      <span class="related-icon">🔗</span>
      <h3 class="related-title">함께 움직이는 종목</h3>
      <InfoTooltip title="관련 종목 (correlation)">
        <p>같은 섹터 종목 중 최근 60일 일변화율이 비슷한 패턴을 보인 top 5.</p>
        <p><strong>상관 0.5+</strong>: 같이 움직이는 경향. <strong>0.7+</strong>: 매우 강함.</p>
        <p>왜 유용? <em>한 종목 보유 시 같이 움직이는 종목도 영향 받을 수 있음.</em>
          분산투자 / 동반 매수·매도 결정에 참고.</p>
      </InfoTooltip>
    </div>
    <div class="related-list">
      <div v-for="r in stocks" :key="r.stockCode"
           class="related-row" @click="$emit('select', r.stockCode)">
        <span class="related-name">{{ r.stockName }}</span>
        <span class="related-code">{{ r.stockCode }}</span>
        <span class="related-corr" :class="getCorrClass(r.correlation)">
          상관 {{ Number(r.correlation).toFixed(2) }}
        </span>
      </div>
    </div>
  </section>
</template>

<script setup>
import InfoTooltip from '../InfoTooltip.vue';

defineProps({
  stocks: { type: Array, default: () => [] }
});
defineEmits(['select']);

const getCorrClass = (c) => {
  const v = Math.abs(Number(c));
  if (v >= 0.7) return 'corr-strong';
  if (v >= 0.5) return 'corr-mid';
  return 'corr-weak';
};
</script>

<style scoped>
.related-section {
  margin: 12px 16px;
  padding: 12px 14px;
  background: rgba(20, 24, 38, 0.6);
  border-radius: 10px;
}
.related-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.related-icon { font-size: 16px; }
.related-title { margin: 0; font-size: 14px; }
.related-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 6px;
}
.related-row {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.15s;
}
.related-row:hover { background: rgba(255, 255, 255, 0.08); }
.related-name { font-weight: 600; }
.related-code { opacity: 0.55; font-size: 11px; }
.related-corr {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.corr-strong { background: rgba(34, 197, 94, 0.18); color: #86efac; }
.corr-mid    { background: rgba(234, 179, 8, 0.18); color: #fcd34d; }
.corr-weak   { background: rgba(255, 255, 255, 0.06); opacity: 0.7; }
</style>
