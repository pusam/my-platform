<template>
  <div class="bot-pnl-chart-wrap">
    <canvas ref="canvasRef"></canvas>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import { Chart, registerables } from 'chart.js';

Chart.register(...registerables);

const props = defineProps({
  /** [{ date: '2026-05-13', pnl: 12000, tradeCount: 3 }, ...] — 최신순. 컴포넌트가 역순 정렬. */
  dailyPnl: { type: Array, default: () => [] }
});

const canvasRef = ref(null);
let chart = null;

const formatKrw = (v) => {
  if (v === null || v === undefined) return '0';
  const n = Number(v);
  if (Math.abs(n) >= 1_0000_0000) return (n / 1_0000_0000).toFixed(1) + '억';
  if (Math.abs(n) >= 1_0000) return (n / 1_0000).toFixed(0) + '만';
  return n.toLocaleString();
};

const buildChart = () => {
  if (!canvasRef.value) return;
  const list = (props.dailyPnl || []).slice().reverse(); // 옛날→오늘 정렬
  const labels = list.map(d => d.date);
  const dailyValues = list.map(d => Number(d.pnl || 0));
  // 누적 손익
  let cum = 0;
  const cumulative = dailyValues.map(v => { cum += v; return cum; });

  const colors = dailyValues.map(v => v >= 0 ? 'rgba(34, 197, 94, 0.65)' : 'rgba(239, 68, 68, 0.65)');

  if (chart) chart.destroy();
  chart = new Chart(canvasRef.value, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        {
          type: 'bar',
          label: '일별 손익',
          data: dailyValues,
          backgroundColor: colors,
          borderColor: colors,
          yAxisID: 'y',
          order: 2
        },
        {
          type: 'line',
          label: '누적 손익',
          data: cumulative,
          borderColor: '#93c5fd',
          backgroundColor: 'rgba(147, 197, 253, 0.10)',
          tension: 0.25,
          fill: true,
          yAxisID: 'y',
          order: 1,
          pointRadius: 2
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { labels: { color: 'rgba(255,255,255,0.85)' } },
        tooltip: {
          callbacks: {
            label: (ctx) => `${ctx.dataset.label}: ${formatKrw(ctx.parsed.y)}원`
          }
        }
      },
      scales: {
        x: {
          ticks: { color: 'rgba(255,255,255,0.65)', maxRotation: 0, autoSkip: true, maxTicksLimit: 10 },
          grid: { color: 'rgba(255,255,255,0.05)' }
        },
        y: {
          ticks: {
            color: 'rgba(255,255,255,0.65)',
            callback: (v) => formatKrw(v) + '원'
          },
          grid: { color: 'rgba(255,255,255,0.08)' }
        }
      }
    }
  });
};

onMounted(buildChart);
watch(() => props.dailyPnl, buildChart, { deep: true });
onBeforeUnmount(() => { if (chart) chart.destroy(); });
</script>

<style scoped>
.bot-pnl-chart-wrap {
  height: 280px;
  margin-bottom: 16px;
  padding: 12px;
  background: var(--surface-card-soft, rgba(20, 24, 38, 0.6));
  border-radius: 10px;
}
</style>
