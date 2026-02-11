<template>
  <div class="program-trading-chart">
    <div class="chart-header">
      <h3>프로그램 매매 추이</h3>
      <div class="summary">
        <span class="net-buy" :class="netBuyClass">
          누적 {{ netBuyText }}
        </span>
        <span class="trend-badge" :class="trendClass">{{ trendText }}</span>
      </div>
    </div>

    <div class="chart-wrapper">
      <Line v-if="hasData && !isPreMarket" :data="chartData" :options="chartOptions" />
      <div v-else-if="isPreMarket" class="no-data pre-market">
        <div class="pre-market-icon">🕐</div>
        <p class="pre-market-title">장 시작 대기 중</p>
        <p class="pre-market-desc">09:00 장 시작 후 데이터가 표시됩니다</p>
      </div>
      <div v-else-if="isMarketHours" class="no-data collecting">
        <div class="collecting-icon">⏳</div>
        <p class="collecting-title">데이터 집계 중</p>
        <p class="collecting-desc">잠시 후 다시 조회해 주세요</p>
      </div>
      <div v-else class="no-data">
        <p>프로그램 매매 데이터가 없습니다</p>
      </div>
    </div>

    <div class="chart-legend">
      <span class="legend-item">
        <span class="legend-color positive"></span>
        순매수
      </span>
      <span class="legend-item">
        <span class="legend-color negative"></span>
        순매도
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { Line } from 'vue-chartjs';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler
} from 'chart.js';

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler
);

const props = defineProps({
  series: {
    type: Array,
    default: () => []
  },
  programNetBuy: {
    type: Number,
    default: 0
  },
  programTrend: {
    type: String,
    default: 'FLAT'
  }
});

const hasData = computed(() => {
  return props.series && props.series.length > 0;
});

// 장 운영 시간 체크 (09:00 ~ 15:30)
const isPreMarket = computed(() => {
  const now = new Date();
  const hour = now.getHours();
  const minute = now.getMinutes();
  const currentTime = hour * 60 + minute;
  // 09:00 이전
  return currentTime < 540;
});

// 장중 시간 체크 (09:00 ~ 15:30)
const isMarketHours = computed(() => {
  const now = new Date();
  const hour = now.getHours();
  const minute = now.getMinutes();
  const currentTime = hour * 60 + minute;
  // 09:00 ~ 15:30 (540분 ~ 930분)
  return currentTime >= 540 && currentTime <= 930;
});

const netBuyText = computed(() => {
  const value = props.programNetBuy || 0;
  const sign = value >= 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}억`;
});

const netBuyClass = computed(() => {
  const value = props.programNetBuy || 0;
  if (value > 0) return 'positive';
  if (value < 0) return 'negative';
  return 'neutral';
});

const trendText = computed(() => {
  switch (props.programTrend) {
    case 'UP': return '상승세';
    case 'DOWN': return '하락세';
    default: return '보합';
  }
});

const trendClass = computed(() => {
  switch (props.programTrend) {
    case 'UP': return 'up';
    case 'DOWN': return 'down';
    default: return 'flat';
  }
});

const chartData = computed(() => {
  if (!hasData.value) {
    return { labels: [], datasets: [] };
  }

  const labels = props.series.map(p => p.time);
  // netBuyAmount 또는 value 필드 지원 (API vs Mock 데이터 호환)
  const data = props.series.map(p => p.netBuyAmount ?? p.value ?? 0);

  // 양수/음수에 따른 색상
  const borderColor = data.map(v => v >= 0 ? '#ef4444' : '#3b82f6');
  const backgroundColor = data.map(v => v >= 0 ? 'rgba(239, 68, 68, 0.1)' : 'rgba(59, 130, 246, 0.1)');

  return {
    labels,
    datasets: [
      {
        label: '누적 순매수',
        data,
        borderColor: '#9f7aea',
        backgroundColor: 'rgba(159, 122, 234, 0.1)',
        borderWidth: 2,
        tension: 0.3,
        fill: true,
        pointRadius: 3,
        pointHoverRadius: 6,
        pointBackgroundColor: (ctx) => {
          const value = ctx.raw;
          return value >= 0 ? '#ef4444' : '#3b82f6';
        },
        segment: {
          borderColor: (ctx) => {
            const value = ctx.p1.parsed.y;
            return value >= 0 ? '#ef4444' : '#3b82f6';
          }
        }
      }
    ]
  };
});

const chartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: {
    mode: 'index',
    intersect: false
  },
  plugins: {
    legend: {
      display: false
    },
    tooltip: {
      backgroundColor: 'rgba(15, 15, 35, 0.95)',
      titleColor: '#fff',
      bodyColor: '#ccc',
      borderColor: '#4a4a8a',
      borderWidth: 1,
      padding: 12,
      callbacks: {
        label: function(context) {
          const value = context.parsed.y;
          const sign = value >= 0 ? '+' : '';
          return `순매수: ${sign}${value.toFixed(2)}억`;
        }
      }
    }
  },
  scales: {
    x: {
      ticks: {
        color: '#888',
        maxRotation: 0,
        autoSkip: true,
        maxTicksLimit: 10
      },
      grid: {
        color: 'rgba(255, 255, 255, 0.05)'
      }
    },
    y: {
      ticks: {
        color: '#888',
        callback: function(value) {
          return value.toFixed(0) + '억';
        }
      },
      grid: {
        color: 'rgba(255, 255, 255, 0.05)'
      }
    }
  }
}));
</script>

<style scoped>
.program-trading-chart {
  background: #1a1a3a;
  border-radius: 16px;
  padding: 24px;
  border: 1px solid #2a2a4a;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 12px;
}

.chart-header h3 {
  margin: 0;
  color: #fff;
  font-size: 1.2rem;
}

.summary {
  display: flex;
  align-items: center;
  gap: 12px;
}

.net-buy {
  font-size: 1.3rem;
  font-weight: 700;
  font-family: 'Monaco', 'Consolas', monospace;
}

.net-buy.positive { color: #ef4444; }
.net-buy.negative { color: #3b82f6; }
.net-buy.neutral { color: #a3a3a3; }

.trend-badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 0.8rem;
  font-weight: 600;
}

.trend-badge.up {
  background: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

.trend-badge.down {
  background: rgba(59, 130, 246, 0.2);
  color: #3b82f6;
}

.trend-badge.flat {
  background: rgba(163, 163, 163, 0.2);
  color: #a3a3a3;
}

.chart-wrapper {
  height: 250px;
  margin-bottom: 16px;
}

.no-data {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #666;
}

.no-data.pre-market,
.no-data.collecting {
  flex-direction: column;
  gap: 8px;
  color: #71717a;
}

.pre-market-icon,
.collecting-icon {
  font-size: 2.5rem;
  opacity: 0.7;
}

.pre-market-title,
.collecting-title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
  color: #a1a1aa;
}

.pre-market-desc,
.collecting-desc {
  margin: 0;
  font-size: 0.85rem;
  color: #71717a;
}

.collecting-icon {
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.5; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.1); }
}

.chart-legend {
  display: flex;
  justify-content: center;
  gap: 24px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #888;
  font-size: 0.85rem;
}

.legend-color {
  width: 16px;
  height: 3px;
  border-radius: 2px;
}

.legend-color.positive {
  background: #ef4444;
}

.legend-color.negative {
  background: #3b82f6;
}
</style>
