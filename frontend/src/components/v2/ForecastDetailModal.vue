<template>
  <div v-if="visible" class="modal-overlay" @click.self="$emit('close')" @keydown.escape="$emit('close')">
    <div class="modal-container">
      <div class="modal-header">
        <h3>AI 시장 예측 상세 (KOSPI)</h3>
        <button class="close-btn" @click="$emit('close')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="modal-body" v-if="forecastData">
        <!-- Large Chart -->
        <div class="detail-chart-container">
          <Line :data="chartData" :options="chartOptions" />
        </div>

        <!-- Expanded Scenario Cards -->
        <div class="detail-scenario-cards">
          <div class="detail-scenario-card bull">
            <div class="detail-scenario-header">
              <span class="detail-scenario-icon bull-icon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/><polyline points="17 6 23 6 23 12"/>
                </svg>
              </span>
              <span class="detail-scenario-label">Bull 시나리오</span>
              <span class="detail-scenario-prob">{{ forecastData.scenarios.bull.probability }}%</span>
            </div>
            <p class="detail-scenario-reason">{{ forecastData.scenarios.bull.reason }}</p>
            <div class="detail-scenario-target" v-if="lastForecasts">
              <span class="target-label">D+5 예상</span>
              <span class="target-value up">{{ formatNum(lastForecasts.bull) }}</span>
            </div>
          </div>

          <div class="detail-scenario-card base-card">
            <div class="detail-scenario-header">
              <span class="detail-scenario-icon base-icon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.7)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="2" y1="12" x2="22" y2="12"/><polyline points="17 7 22 12 17 17"/>
                </svg>
              </span>
              <span class="detail-scenario-label">Base 시나리오</span>
              <span class="detail-scenario-prob">{{ forecastData.scenarios.base.probability }}%</span>
            </div>
            <p class="detail-scenario-reason">{{ forecastData.scenarios.base.reason }}</p>
            <div class="detail-scenario-target" v-if="lastForecasts">
              <span class="target-label">D+5 예상</span>
              <span class="target-value">{{ formatNum(lastForecasts.base) }}</span>
            </div>
          </div>

          <div class="detail-scenario-card bear">
            <div class="detail-scenario-header">
              <span class="detail-scenario-icon bear-icon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="23 18 13.5 8.5 8.5 13.5 1 6"/><polyline points="17 18 23 18 23 12"/>
                </svg>
              </span>
              <span class="detail-scenario-label">Bear 시나리오</span>
              <span class="detail-scenario-prob">{{ forecastData.scenarios.bear.probability }}%</span>
            </div>
            <p class="detail-scenario-reason">{{ forecastData.scenarios.bear.reason }}</p>
            <div class="detail-scenario-target" v-if="lastForecasts">
              <span class="target-label">D+5 예상</span>
              <span class="target-value down">{{ formatNum(lastForecasts.bear) }}</span>
            </div>
          </div>
        </div>

        <!-- AI Summary -->
        <div class="detail-summary">
          <div class="summary-label">AI 종합 분석</div>
          <p class="summary-text">{{ forecastData.summary }}</p>
        </div>

        <!-- Base Info -->
        <div class="detail-base-info">
          <span>KOSPI 기준 지수: {{ formatNum(forecastData.baseIndex) }}pt</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { Line } from 'vue-chartjs'
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
} from 'chart.js'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend, Filler)

export default {
  name: 'ForecastDetailModal',
  components: { Line },
  props: {
    visible: { type: Boolean, default: false },
    forecastData: { type: Object, default: null }
  },
  emits: ['close'],
  computed: {
    lastForecasts() {
      if (!this.forecastData || !this.forecastData.forecasts) return null
      const last = this.forecastData.forecasts[this.forecastData.forecasts.length - 1]
      return last || null
    },
    chartData() {
      if (!this.forecastData || !this.forecastData.forecasts) return { labels: [], datasets: [] }

      const base = this.forecastData.baseIndex
      const forecasts = this.forecastData.forecasts

      return {
        labels: ['오늘', 'D+1', 'D+2', 'D+3', 'D+4', 'D+5'],
        datasets: [
          {
            label: 'Bull 시나리오',
            data: [base, ...forecasts.map(f => f.bull)],
            borderColor: 'rgba(239, 68, 68, 0.8)',
            backgroundColor: 'rgba(239, 68, 68, 0.08)',
            fill: '+1',
            borderDash: [5, 5],
            pointRadius: 5,
            pointBackgroundColor: 'rgba(239, 68, 68, 0.8)',
            borderWidth: 2,
            tension: 0.3
          },
          {
            label: 'Base 시나리오',
            data: [base, ...forecasts.map(f => f.base)],
            borderColor: 'rgba(255, 255, 255, 0.9)',
            backgroundColor: 'rgba(255, 255, 255, 0.05)',
            fill: false,
            borderWidth: 3,
            pointRadius: 6,
            pointBackgroundColor: 'rgba(255, 255, 255, 0.9)',
            tension: 0.3
          },
          {
            label: 'Bear 시나리오',
            data: [base, ...forecasts.map(f => f.bear)],
            borderColor: 'rgba(59, 130, 246, 0.8)',
            backgroundColor: 'rgba(59, 130, 246, 0.08)',
            fill: '-1',
            borderDash: [5, 5],
            pointRadius: 5,
            pointBackgroundColor: 'rgba(59, 130, 246, 0.8)',
            borderWidth: 2,
            tension: 0.3
          }
        ]
      }
    },
    chartOptions() {
      return {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'top',
            labels: {
              color: 'rgba(255,255,255,0.7)',
              font: { size: 13 },
              boxWidth: 24,
              padding: 16
            }
          },
          tooltip: {
            backgroundColor: 'rgba(0,0,0,0.85)',
            titleColor: '#fff',
            titleFont: { size: 13 },
            bodyColor: 'rgba(255,255,255,0.8)',
            bodyFont: { size: 12 },
            borderColor: 'rgba(255,255,255,0.1)',
            borderWidth: 1,
            padding: 12,
            callbacks: {
              label: function(context) {
                return context.dataset.label + ': ' + Math.round(context.parsed.y).toLocaleString()
              }
            }
          },
          filler: { propagate: true }
        },
        scales: {
          x: {
            grid: { color: 'rgba(255,255,255,0.06)' },
            ticks: { color: 'rgba(255,255,255,0.6)', font: { size: 13 } }
          },
          y: {
            grid: { color: 'rgba(255,255,255,0.06)' },
            ticks: {
              color: 'rgba(255,255,255,0.6)',
              font: { size: 12 },
              callback: function(value) { return Math.round(value).toLocaleString() }
            }
          }
        }
      }
    }
  },
  watch: {
    visible(val) {
      if (val) {
        document.addEventListener('keydown', this.handleEsc)
      } else {
        document.removeEventListener('keydown', this.handleEsc)
      }
    }
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this.handleEsc)
  },
  methods: {
    handleEsc(e) {
      if (e.key === 'Escape') {
        this.$emit('close')
      }
    },
    formatNum(val) {
      if (val == null) return '-'
      return Math.round(val).toLocaleString()
    }
  }
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-container {
  background: #1a1a2e;
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  width: 100%;
  max-width: 900px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.modal-header h3 {
  margin: 0;
  color: rgba(255,255,255,0.95);
  font-size: 18px;
  font-weight: 700;
}

.close-btn {
  background: none;
  border: none;
  color: rgba(255,255,255,0.5);
  cursor: pointer;
  padding: 6px;
  border-radius: 8px;
  transition: all 0.2s;
}
.close-btn:hover {
  color: white;
  background: rgba(255,255,255,0.1);
}

.modal-body {
  padding: 24px;
}

/* Large Chart */
.detail-chart-container {
  height: 400px;
  margin-bottom: 24px;
}

/* Scenario Cards */
.detail-scenario-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}

.detail-scenario-card {
  background: var(--border-light);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 14px;
  padding: 16px;
}
.detail-scenario-card.bull { border-left: 4px solid #ef4444; }
.detail-scenario-card.base-card { border-left: 4px solid rgba(255,255,255,0.6); }
.detail-scenario-card.bear { border-left: 4px solid #3b82f6; }

.detail-scenario-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.detail-scenario-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  flex-shrink: 0;
}
.detail-scenario-icon.bull-icon { background: rgba(239, 68, 68, 0.15); }
.detail-scenario-icon.base-icon { background: rgba(255, 255, 255, 0.1); }
.detail-scenario-icon.bear-icon { background: rgba(59, 130, 246, 0.15); }
.detail-scenario-label {
  font-size: 14px;
  font-weight: 700;
  color: rgba(255,255,255,0.85);
  flex: 1;
}
.detail-scenario-prob {
  font-size: 20px;
  font-weight: 800;
  color: rgba(255,255,255,0.95);
}

.detail-scenario-reason {
  font-size: 13px;
  color: rgba(255,255,255,0.55);
  margin: 0 0 12px 0;
  line-height: 1.5;
}

.detail-scenario-target {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: rgba(255,255,255,0.04);
  border-radius: 8px;
}
.target-label {
  font-size: 12px;
  color: rgba(255,255,255,0.4);
}
.target-value {
  font-size: 15px;
  font-weight: 700;
  color: rgba(255,255,255,0.8);
}
.target-value.up { color: #ef4444; }
.target-value.down { color: #3b82f6; }

/* Summary */
.detail-summary {
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 16px 20px;
  margin-bottom: 16px;
}
.summary-label {
  font-size: 12px;
  font-weight: 600;
  color: rgba(255,255,255,0.4);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.summary-text {
  margin: 0;
  font-size: 15px;
  color: rgba(255,255,255,0.8);
  line-height: 1.7;
}

/* Base Info */
.detail-base-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: rgba(255,255,255,0.35);
}
/* Scrollbar */
.modal-container::-webkit-scrollbar { width: 6px; }
.modal-container::-webkit-scrollbar-track { background: transparent; }
.modal-container::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.1);
  border-radius: 3px;
}

@media (max-width: 768px) {
  .modal-container { max-width: 95%; margin: 0 10px; }
  .detail-scenario-cards { grid-template-columns: 1fr; }
  .detail-chart-container { height: 300px; }
}
</style>
