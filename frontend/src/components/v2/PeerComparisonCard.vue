<template>
  <!-- Peer Group 비교 — StockDetailDashboard 에서 분리 (P2-10) -->
  <div class="peer-section">
    <div class="peer-header">
      <h2>섹터 Peer Group</h2>
      <span v-if="sectorName" class="sector-name-badge">{{ sectorName }}</span>
    </div>
    <div class="peer-chart">
      <div
        v-for="(peer, i) in peerComparisons"
        :key="i"
        class="peer-bar-row"
        :class="{ current: peer.isCurrent }"
      >
        <span class="peer-name">{{ peer.stockName }}</span>
        <div class="peer-bar-container">
          <div
            class="peer-bar-fill"
            :style="{ width: getPeerBarWidth(peer.pbr) + '%' }"
            :class="getPeerBarClass(peer.pbr)"
          ></div>
          <div
            v-if="sectorAvgPbr"
            class="sector-avg-line"
            :style="{ left: getPeerBarWidth(sectorAvgPbr) + '%' }"
          ></div>
        </div>
        <span class="peer-pbr">PBR {{ peer.pbr?.toFixed(2) }}배</span>
        <span class="peer-div">배당 {{ peer.dividendYield?.toFixed(1) }}%</span>
      </div>
      <div v-if="sectorAvgPbr" class="sector-avg-label">
        <span class="avg-line-indicator"></span>
        업종 평균 PBR {{ sectorAvgPbr?.toFixed(2) }}배
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  peerComparisons: { type: Array, default: () => [] },
  sectorName: { type: String, default: '' },
  sectorAvgPbr: { type: Number, default: null }
});

// StockDetailDashboard 에서 이동, 로직 동일.
const getPeerBarWidth = (pbr) => {
  if (!pbr) return 0;
  return Math.min(100, (pbr / 2.0) * 100);
};
const getPeerBarClass = (pbr) => {
  if (!pbr) return '';
  if (pbr < 0.5) return 'peer-very-low';
  if (pbr < 1.0) return 'peer-low';
  if (pbr < 1.5) return 'peer-mid';
  return 'peer-high';
};
</script>

<style scoped>
/* Peer Group 비교 */
.peer-section {
  background: rgba(30, 30, 60, 0.6);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #2a2a5a;
  margin-top: 12px;
}

.peer-chart {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.peer-bar-row {
  display: grid;
  grid-template-columns: 80px 1fr 90px 70px;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 0.8rem;
}

.peer-bar-row.current {
  background: rgba(167, 139, 250, 0.1);
  border: 1px solid rgba(167, 139, 250, 0.3);
}

.peer-name {
  color: #ccc;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.peer-bar-row.current .peer-name {
  color: #a78bfa;
  font-weight: 700;
}

.peer-bar-container {
  position: relative;
  height: 14px;
  background: var(--border-light);
  border-radius: 7px;
  overflow: visible;
}

.peer-bar-fill {
  height: 100%;
  border-radius: 7px;
  transition: width 0.5s ease;
}

.peer-bar-fill.peer-very-low { background: linear-gradient(90deg, #22c55e, #4ade80); }
.peer-bar-fill.peer-low { background: linear-gradient(90deg, #4ade80, #a3e635); }
.peer-bar-fill.peer-mid { background: linear-gradient(90deg, #eab308, #f59e0b); }
.peer-bar-fill.peer-high { background: linear-gradient(90deg, #f87171, #ef4444); }

.peer-pbr { color: #aaa; font-family: 'Monaco', monospace; font-size: 0.75rem; }
.peer-div { color: #888; font-size: 0.7rem; }

.peer-bar-row.current .peer-pbr { color: #a78bfa; font-weight: 600; }
.peer-bar-row.current .peer-div { color: #c4b5fd; }

/* Peer Group 헤더/섹터 */
.peer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.peer-header h2 {
  margin: 0;
  font-size: 1.1rem;
}

.sector-name-badge {
  font-size: 0.7rem;
  padding: 3px 10px;
  border-radius: 10px;
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

/* 섹터 평균 PBR 라인 */
.sector-avg-line {
  position: absolute;
  top: -2px;
  width: 2px;
  height: 18px;
  background: #f59e0b;
  z-index: 2;
}

.sector-avg-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 0.7rem;
  color: #f59e0b;
}

.avg-line-indicator {
  display: inline-block;
  width: 12px;
  height: 2px;
  background: #f59e0b;
}

@media (max-width: 480px) {
  /* 피어 비교 바 — 고정폭 압축 (80/90/70 → 60/65/55) */
  .peer-bar-row { grid-template-columns: 60px 1fr 65px 55px; gap: 6px; padding: 5px 8px; font-size: 0.72rem; }
}
</style>
