<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">🗺️</span> 시장 지도</h2>
      <router-link to="/sector" class="more-link">더 보기 →</router-link>
    </div>

    <SkeletonLoader v-if="loading" type="default" />

    <div v-else-if="error" class="section-error">데이터를 불러올 수 없습니다.</div>

    <template v-else>
      <!-- 내부 탭 -->
      <div class="inner-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['tab-btn', { active: activeTab === tab.key }]"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- 섹터 히트맵 -->
      <div v-if="activeTab === 'heatmap'" class="heatmap-area">
        <div class="heatmap-grid">
          <div
            v-for="sector in sectorData"
            :key="sector.sectorName"
            class="heatmap-block"
            :style="getBlockStyle(sector)"
            :title="sector.sectorName + ': ' + (sector.changeRate || 0).toFixed(2) + '%'"
          >
            <span class="block-name">{{ shortenName(sector.sectorName) }}</span>
            <span class="block-rate" :class="sector.changeRate >= 0 ? 'up' : 'down'">
              {{ sector.changeRate >= 0 ? '+' : '' }}{{ (sector.changeRate || 0).toFixed(1) }}%
            </span>
          </div>
        </div>
        <div v-if="sectorData.length === 0" class="empty-msg">섹터 데이터 없음</div>
      </div>

      <!-- 시장 지표 -->
      <div v-if="activeTab === 'market'" class="market-indicators">
        <div class="indicator-card">
          <span class="ind-label">KOSPI</span>
          <span class="ind-value">{{ marketData.kospiIndex || '-' }}</span>
          <span class="ind-change" :class="(marketData.kospiChangeRate || 0) >= 0 ? 'up' : 'down'">
            {{ (marketData.kospiChangeRate || 0) >= 0 ? '+' : '' }}{{ (marketData.kospiChangeRate || 0).toFixed(2) }}%
          </span>
        </div>
        <div class="indicator-card">
          <span class="ind-label">KOSDAQ</span>
          <span class="ind-value">{{ marketData.kosdaqIndex || '-' }}</span>
          <span class="ind-change" :class="(marketData.kosdaqChangeRate || 0) >= 0 ? 'up' : 'down'">
            {{ (marketData.kosdaqChangeRate || 0) >= 0 ? '+' : '' }}{{ (marketData.kosdaqChangeRate || 0).toFixed(2) }}%
          </span>
        </div>
        <!-- ADR Gauge -->
        <div class="adr-gauge">
          <span class="adr-label">ADR (등락비율)</span>
          <div class="gauge-bar">
            <div class="gauge-fill" :style="{ width: Math.min(100, marketData.adr || 0) + '%' }" :class="getAdrClass()"></div>
          </div>
          <span class="adr-value">{{ (marketData.adr || 0).toFixed(1) }}%</span>
        </div>
        <div class="market-status" v-if="marketData.marketStatus">
          {{ marketData.marketStatus }}
        </div>
        <div class="more-links">
          <router-link to="/market-timing">시장 타이밍 →</router-link>
        </div>
      </div>

      <!-- 글로벌 -->
      <div v-if="activeTab === 'global'" class="global-area">
        <div class="indicator-card" v-if="globalData.nasdaqFutures">
          <span class="ind-label">나스닥 선물</span>
          <span class="ind-value">{{ globalData.nasdaqFutures.price || '-' }}</span>
          <span class="ind-change" :class="(globalData.nasdaqFutures.changeRate || 0) >= 0 ? 'up' : 'down'">
            {{ (globalData.nasdaqFutures.changeRate || 0) >= 0 ? '+' : '' }}{{ (globalData.nasdaqFutures.changeRate || 0).toFixed(2) }}%
          </span>
        </div>
        <div class="indicator-card" v-if="globalData.leadingSectors && globalData.leadingSectors.length > 0">
          <span class="ind-label">주도 섹터</span>
          <span class="ind-value">{{ globalData.leadingSectors[0].sectorName || '-' }}</span>
        </div>
        <div v-if="!globalData.nasdaqFutures && (!globalData.leadingSectors || globalData.leadingSectors.length === 0)" class="empty-msg">
          글로벌 데이터 없음
        </div>
        <div class="more-links">
          <router-link to="/trading-indicators">트레이딩 지표 →</router-link>
        </div>
      </div>

      <!-- AI 예측 -->
      <div v-if="activeTab === 'forecast'" class="forecast-section">
        <div v-if="forecastLoading" class="loading-state">
          <div class="loading-spinner"></div>
          <span>AI 예측 생성 중...</span>
        </div>

        <div v-else-if="forecastData" class="forecast-content">
          <div class="forecast-chart-container">
            <Line :data="forecastChartData" :options="forecastChartOptions" />
          </div>

          <div class="scenario-cards">
            <div class="scenario-card bull">
              <div class="scenario-header">
                <span class="scenario-label">Bull</span>
                <span class="scenario-probability">{{ forecastData.scenarios.bull.probability }}%</span>
              </div>
              <p class="scenario-reason">{{ forecastData.scenarios.bull.reason }}</p>
            </div>
            <div class="scenario-card base">
              <div class="scenario-header">
                <span class="scenario-label">Base</span>
                <span class="scenario-probability">{{ forecastData.scenarios.base.probability }}%</span>
              </div>
              <p class="scenario-reason">{{ forecastData.scenarios.base.reason }}</p>
            </div>
            <div class="scenario-card bear">
              <div class="scenario-header">
                <span class="scenario-label">Bear</span>
                <span class="scenario-probability">{{ forecastData.scenarios.bear.probability }}%</span>
              </div>
              <p class="scenario-reason">{{ forecastData.scenarios.bear.reason }}</p>
            </div>
          </div>

          <div class="forecast-summary">{{ forecastData.summary }}</div>
          <div v-if="forecastData.fallback" class="fallback-notice">* 기본 예측 (AI 응답 실패 시 기계적 산출)</div>
          <button class="forecast-detail-btn" @click="showForecastDetail = true">자세히 보기 →</button>
        </div>

        <div v-else class="empty-msg">예측 데이터를 불러올 수 없습니다.</div>
      </div>
    </template>

    <ForecastDetailModal
      :visible="showForecastDetail"
      :forecastData="forecastData"
      @close="showForecastDetail = false"
    />
  </div>
</template>

<script>
import SkeletonLoader from './SkeletonLoader.vue'
import ForecastDetailModal from './ForecastDetailModal.vue'
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
import { marketAPI } from '../../utils/api'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend, Filler)

export default {
  name: 'SectionMarketMap',
  components: { SkeletonLoader, Line, ForecastDetailModal },
  props: {
    sectorData: { type: Array, default: () => [] },
    marketData: { type: Object, default: () => ({}) },
    globalData: { type: Object, default: () => ({}) },
    loading: { type: Boolean, default: false },
    error: { type: Boolean, default: false }
  },
  data() {
    return {
      activeTab: 'heatmap',
      tabs: [
        { key: 'heatmap', label: '섹터 히트맵' },
        { key: 'market', label: '시장 지표' },
        { key: 'global', label: '글로벌' },
        { key: 'forecast', label: 'AI 예측' }
      ],
      forecastData: null,
      forecastLoading: false,
      showForecastDetail: false
    }
  },
  computed: {
    maxTradingValue() {
      if (this.sectorData.length === 0) return 1
      return Math.max(...this.sectorData.map(s => s.totalTradingValue || s.tradingValue || 1))
    },
    forecastChartData() {
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
            backgroundColor: 'rgba(239, 68, 68, 0.1)',
            fill: '+1',
            borderDash: [5, 5],
            pointRadius: 3,
            pointBackgroundColor: 'rgba(239, 68, 68, 0.8)',
            borderWidth: 1.5,
            tension: 0.3
          },
          {
            label: '기본 시나리오',
            data: [base, ...forecasts.map(f => f.base)],
            borderColor: 'rgba(255, 255, 255, 0.9)',
            backgroundColor: 'rgba(255, 255, 255, 0.05)',
            fill: false,
            borderWidth: 2,
            pointRadius: 4,
            pointBackgroundColor: 'rgba(255, 255, 255, 0.9)',
            tension: 0.3
          },
          {
            label: 'Bear 시나리오',
            data: [base, ...forecasts.map(f => f.bear)],
            borderColor: 'rgba(59, 130, 246, 0.8)',
            backgroundColor: 'rgba(59, 130, 246, 0.1)',
            fill: '-1',
            borderDash: [5, 5],
            pointRadius: 3,
            pointBackgroundColor: 'rgba(59, 130, 246, 0.8)',
            borderWidth: 1.5,
            tension: 0.3
          }
        ]
      }
    },
    forecastChartOptions() {
      return {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'top',
            labels: {
              color: 'rgba(255,255,255,0.7)',
              font: { size: 11 },
              boxWidth: 20,
              padding: 12
            }
          },
          tooltip: {
            backgroundColor: 'rgba(0,0,0,0.8)',
            titleColor: '#fff',
            bodyColor: 'rgba(255,255,255,0.8)',
            borderColor: 'rgba(255,255,255,0.1)',
            borderWidth: 1,
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
            ticks: { color: 'rgba(255,255,255,0.6)', font: { size: 11 } }
          },
          y: {
            grid: { color: 'rgba(255,255,255,0.06)' },
            ticks: {
              color: 'rgba(255,255,255,0.6)',
              font: { size: 11 },
              callback: function(value) { return Math.round(value).toLocaleString() }
            }
          }
        }
      }
    }
  },
  watch: {
    activeTab(newTab) {
      if (newTab === 'forecast' && !this.forecastData) {
        this.loadForecast()
      }
    }
  },
  methods: {
    async loadForecast() {
      this.forecastLoading = true
      try {
        const res = await marketAPI.getForecast()
        if (res.data && res.data.success !== false) {
          this.forecastData = res.data
        }
      } catch (e) {
        console.error('Forecast load failed:', e)
      } finally {
        this.forecastLoading = false
      }
    },
    getBlockStyle(sector) {
      const ratio = (sector.totalTradingValue || sector.tradingValue || 0) / this.maxTradingValue
      const size = Math.max(60, Math.min(120, 60 + ratio * 60))
      const rate = sector.changeRate || 0
      let bg
      if (rate > 3) bg = 'rgba(239,68,68,0.5)'
      else if (rate > 1) bg = 'rgba(239,68,68,0.3)'
      else if (rate > 0) bg = 'rgba(239,68,68,0.15)'
      else if (rate > -1) bg = 'rgba(59,130,246,0.15)'
      else if (rate > -3) bg = 'rgba(59,130,246,0.3)'
      else bg = 'rgba(59,130,246,0.5)'
      return {
        width: size + 'px',
        height: size + 'px',
        background: bg
      }
    },
    shortenName(name) {
      if (!name) return ''
      return name.length > 5 ? name.substring(0, 5) + '..' : name
    },
    getAdrClass() {
      const adr = this.marketData.adr || 0
      if (adr >= 120) return 'adr-hot'
      if (adr >= 80) return 'adr-normal'
      return 'adr-cold'
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 24px;
  min-height: 380px;
}

.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.section-title-row h2 { margin: 0; font-size: 16px; font-weight: 700; color: rgba(255,255,255,0.95); }
.section-icon { margin-right: 6px; }
.more-link { font-size: 13px; color: #667eea; text-decoration: none; }
.more-link:hover { color: #8b9cf7; }
.section-error { text-align: center; color: rgba(255,255,255,0.4); padding: 40px 0; font-size: 14px; }

/* Inner Tabs */
.inner-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  background: rgba(255,255,255,0.04);
  padding: 3px;
  border-radius: 10px;
}
.tab-btn {
  flex: 1;
  padding: 6px 12px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s;
}
.tab-btn:hover { color: rgba(255,255,255,0.7); }
.tab-btn.active {
  background: rgba(255,255,255,0.1);
  color: white;
  font-weight: 600;
}

/* Heatmap */
.heatmap-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
}
.heatmap-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  transition: transform 0.2s;
  cursor: default;
}
.heatmap-block:hover { transform: scale(1.05); }
.block-name { font-size: 10px; color: rgba(255,255,255,0.8); font-weight: 600; }
.block-rate { font-size: 10px; font-weight: 700; }
.block-rate.up { color: #fca5a5; }
.block-rate.down { color: #93c5fd; }

/* Indicators */
.indicator-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: rgba(255,255,255,0.04);
  border-radius: 10px;
  margin-bottom: 8px;
}
.ind-label { font-size: 13px; color: rgba(255,255,255,0.5); width: 80px; }
.ind-value { font-size: 15px; font-weight: 700; color: rgba(255,255,255,0.9); flex: 1; }
.ind-change { font-size: 13px; font-weight: 600; }
.ind-change.up { color: #ef4444; }
.ind-change.down { color: #3b82f6; }

/* ADR */
.adr-gauge {
  padding: 10px 12px;
  background: rgba(255,255,255,0.04);
  border-radius: 10px;
  margin-bottom: 8px;
}
.adr-label { font-size: 12px; color: rgba(255,255,255,0.5); display: block; margin-bottom: 6px; }
.gauge-bar {
  height: 8px;
  background: rgba(255,255,255,0.08);
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 4px;
}
.gauge-fill { height: 100%; border-radius: 4px; transition: width 0.6s; }
.gauge-fill.adr-hot { background: linear-gradient(90deg, #f59e0b, #ef4444); }
.gauge-fill.adr-normal { background: linear-gradient(90deg, #10b981, #3b82f6); }
.gauge-fill.adr-cold { background: linear-gradient(90deg, #3b82f6, #6366f1); }
.adr-value { font-size: 13px; color: rgba(255,255,255,0.7); font-weight: 600; }

.market-status {
  text-align: center;
  padding: 8px;
  font-size: 13px;
  color: rgba(255,255,255,0.6);
  background: rgba(255,255,255,0.03);
  border-radius: 8px;
  margin-bottom: 8px;
}

.more-links { margin-top: 8px; }
.more-links a { font-size: 12px; color: rgba(255,255,255,0.4); text-decoration: none; }
.more-links a:hover { color: #667eea; }

.empty-msg { text-align: center; color: rgba(255,255,255,0.3); font-size: 13px; padding: 20px 0; }

/* Forecast */
.forecast-section { padding: 4px 0; }
.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 60px 0;
  color: rgba(255,255,255,0.5);
  font-size: 13px;
}
.loading-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255,255,255,0.1);
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.forecast-chart-container { height: 260px; }
.scenario-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-top: 14px;
}
.scenario-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 12px;
  padding: 10px 12px;
}
.scenario-card.bull { border-left: 3px solid #ef4444; }
.scenario-card.base { border-left: 3px solid rgba(255,255,255,0.6); }
.scenario-card.bear { border-left: 3px solid #3b82f6; }
.scenario-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.scenario-label {
  font-size: 12px;
  font-weight: 700;
  color: rgba(255,255,255,0.8);
}
.scenario-probability {
  font-size: 14px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}
.scenario-reason {
  font-size: 11px;
  color: rgba(255,255,255,0.5);
  margin: 0;
  line-height: 1.4;
}
.forecast-summary {
  margin-top: 14px;
  padding: 10px 12px;
  background: rgba(255,255,255,0.03);
  border-radius: 8px;
  color: rgba(255,255,255,0.65);
  font-size: 12px;
  line-height: 1.6;
}
.fallback-notice {
  margin-top: 8px;
  font-size: 11px;
  color: rgba(255,255,255,0.3);
  text-align: center;
}
.forecast-detail-btn {
  display: block;
  margin: 12px auto 0;
  padding: 6px 16px;
  background: transparent;
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  color: #667eea;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.forecast-detail-btn:hover {
  background: rgba(102,126,234,0.1);
  border-color: #667eea;
  color: #8b9cf7;
}
</style>
