<template>
  <div class="skeleton-wrapper">
    <div v-if="type === 'card'" class="skeleton-card">
      <div class="skeleton-line skeleton-title"></div>
      <div class="skeleton-line skeleton-subtitle"></div>
      <div class="skeleton-line skeleton-body"></div>
      <div class="skeleton-line skeleton-body short"></div>
    </div>
    <div v-else-if="type === 'table'" class="skeleton-table">
      <div v-for="i in rows" :key="i" class="skeleton-row">
        <div class="skeleton-cell" style="width: 15%"></div>
        <div class="skeleton-cell" style="width: 30%"></div>
        <div class="skeleton-cell" style="width: 20%"></div>
        <div class="skeleton-cell" style="width: 15%"></div>
        <div class="skeleton-cell" style="width: 20%"></div>
      </div>
    </div>
    <div v-else-if="type === 'score'" class="skeleton-scores">
      <div v-for="i in 4" :key="i" class="skeleton-score-item">
        <div class="skeleton-line" style="width: 60px; height: 14px;"></div>
        <div class="skeleton-bar"></div>
        <div class="skeleton-line" style="width: 30px; height: 14px;"></div>
      </div>
    </div>
    <div v-else-if="type === 'heatmap'" class="skeleton-heatmap">
      <div class="skeleton-tabs-row">
        <div v-for="i in 4" :key="i" class="skeleton-tab"></div>
      </div>
      <div class="skeleton-heatmap-grid">
        <div v-for="i in 12" :key="i" class="skeleton-block"></div>
      </div>
    </div>
    <div v-else class="skeleton-default">
      <div class="skeleton-line" style="width: 60%; height: 18px; margin-bottom: 12px;"></div>
      <div class="skeleton-line" style="width: 100%; height: 14px; margin-bottom: 8px;"></div>
      <div class="skeleton-line" style="width: 80%; height: 14px;"></div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'SkeletonLoader',
  props: {
    type: { type: String, default: 'default' },
    rows: { type: Number, default: 5 }
  }
}
</script>

<style scoped>
.skeleton-wrapper {
  padding: 16px 0;
}

.skeleton-line {
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 6px;
  height: 14px;
}

.skeleton-title { width: 50%; height: 20px; margin-bottom: 12px; }
.skeleton-subtitle { width: 70%; height: 14px; margin-bottom: 16px; }
.skeleton-body { width: 100%; height: 12px; margin-bottom: 8px; }
.skeleton-body.short { width: 60%; }

.skeleton-card {
  padding: 20px;
  background: rgba(255,255,255,0.03);
  border-radius: 12px;
  margin-bottom: 12px;
}

.skeleton-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton-row {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}

.skeleton-cell {
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 4px;
  height: 16px;
}

.skeleton-scores {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.skeleton-score-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.skeleton-bar {
  flex: 1;
  height: 8px;
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 4px;
}

/* Heatmap skeleton */
.skeleton-tabs-row {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  padding: 3px;
}

.skeleton-tab {
  flex: 1;
  height: 32px;
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 8px;
}

.skeleton-heatmap-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
  padding: 8px 0;
}

.skeleton-block {
  background: linear-gradient(90deg, rgba(255,255,255,0.06) 25%, rgba(255,255,255,0.12) 50%, rgba(255,255,255,0.06) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 8px;
  width: 80px;
  height: 80px;
}

.skeleton-block:nth-child(1)  { width: 110px; height: 110px; }
.skeleton-block:nth-child(2)  { width: 95px;  height: 95px;  }
.skeleton-block:nth-child(3)  { width: 100px; height: 100px; }
.skeleton-block:nth-child(4)  { width: 75px;  height: 75px;  }
.skeleton-block:nth-child(5)  { width: 85px;  height: 85px;  }
.skeleton-block:nth-child(6)  { width: 105px; height: 105px; }
.skeleton-block:nth-child(7)  { width: 70px;  height: 70px;  }
.skeleton-block:nth-child(8)  { width: 90px;  height: 90px;  }
.skeleton-block:nth-child(9)  { width: 80px;  height: 80px;  }
.skeleton-block:nth-child(10) { width: 100px; height: 100px; }
.skeleton-block:nth-child(11) { width: 65px;  height: 65px;  }
.skeleton-block:nth-child(12) { width: 88px;  height: 88px;  }

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
