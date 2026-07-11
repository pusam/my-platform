<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">🗺️</span> 시장 지도</h2>
      <router-link to="/stock-dashboard?tab=analysis" class="more-link">더 보기 →</router-link>
    </div>

    <SkeletonLoader v-if="loading" type="heatmap" />

    <div v-else-if="error" class="state-box">
      <span class="state-icon">⚠️</span>
      <p class="state-text">데이터를 불러오지 못했습니다</p>
      <button class="state-btn" @click="$emit('retry')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 11-9-9"/><polyline points="21 3 21 9 15 9"/></svg>
        새로고침
      </button>
    </div>

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
        <div v-if="sectorData.length === 0" class="state-box">
          <span class="state-icon">📊</span>
          <p class="state-text">섹터 데이터가 없습니다</p>
          <button class="state-btn" @click="$emit('retry')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 11-9-9"/><polyline points="21 3 21 9 15 9"/></svg>
            새로고침
          </button>
        </div>
      </div>

      <!-- 섹터 자금 흐름 -->
      <div v-if="activeTab === 'rotation'" class="rotation-area">
        <div v-if="rotationLoading" class="loading-state">
          <div class="loading-spinner"></div>
          <span>자금 흐름 분석 중...</span>
        </div>

        <div v-else-if="rotationError" class="state-box state-box-sm">
          <span class="state-icon">⚠️</span>
          <p class="state-text">데이터를 불러오지 못했습니다</p>
          <button class="state-btn" @click="loadRotation">새로고침</button>
        </div>

        <div v-else-if="rotationData.length > 0" class="rotation-content">
          <!-- 요약 카운트 -->
          <div class="rotation-summary">
            <span class="summary-badge inflow">유입 {{ rotationData.filter(r => r.flowDirection === 'INFLOW').length }}</span>
            <span class="summary-badge neutral">중립 {{ rotationData.filter(r => r.flowDirection === 'NEUTRAL').length }}</span>
            <span class="summary-badge outflow">유출 {{ rotationData.filter(r => r.flowDirection === 'OUTFLOW').length }}</span>
          </div>

          <!-- 섹터별 흐름 리스트 -->
          <div class="rotation-list">
            <div
              v-for="item in rotationData"
              :key="item.sectorCode"
              class="rotation-item"
            >
              <div class="rotation-left">
                <span class="flow-icon" :class="getFlowClass(item.flowDirection)">{{ getFlowIcon(item.flowDirection) }}</span>
                <span class="rotation-name">{{ item.sectorName }}</span>
              </div>
              <div class="rotation-center">
                <div class="rotation-bar-track">
                  <div
                    class="rotation-bar-fill"
                    :class="getFlowClass(item.flowDirection)"
                    :style="{ width: getFlowBarWidth(item) }"
                  ></div>
                </div>
              </div>
              <div class="rotation-right">
                <span class="rotation-rate" :class="getFlowClass(item.flowDirection)">
                  {{ (item.changeRate || 0) >= 0 ? '+' : '' }}{{ (item.changeRate || 0).toFixed(1) }}%
                </span>
                <span class="rotation-amount">{{ formatBillion(item.todayTradingValue) }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="state-box state-box-sm">
          <span class="state-icon">🔄</span>
          <p class="state-text">로테이션 데이터가 없습니다</p>
        </div>
      </div>

      <!-- '시장 지표' 탭은 상단 시장 상태 바와 중복되어 제거됨.
           '글로벌' 탭은 GNB 글로벌 탭(/global-futures) 풀 페이지와 중복되어 제거됨. -->

      <!-- AI 예측 -->
      <div v-if="activeTab === 'forecast'" class="forecast-section">
        <div v-if="forecastLoading" class="loading-state">
          <div class="loading-spinner"></div>
          <span>AI 예측 생성 중...</span>
        </div>

        <div v-else-if="forecastData" class="forecast-content">
          <div v-if="forecastData.fallback" class="fallback-notice">
            <span>AI 분석 일시 불가 — 현재 지수 기반 기계적 예측입니다.</span>
            <button class="retry-btn-sm" @click="retryForecast">AI 재분석</button>
          </div>
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
          <button class="forecast-detail-btn" @click="showForecastDetail = true">자세히 보기 →</button>
        </div>

        <div v-else-if="forecastError" class="forecast-error">
          <span class="error-icon">⚠️</span>
          <span class="error-text">AI 분석 실패</span>
          <button class="retry-btn" @click="retryForecast">재분석 요청</button>
        </div>

        <div v-else class="state-box state-box-sm">
          <span class="state-icon">🤖</span>
          <p class="state-text">예측 데이터를 불러올 수 없습니다</p>
        </div>
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
import { marketAPI, sectorAPI } from '../../utils/api'
import { formatChange } from '../../utils/marketFormatters'

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
  emits: ['retry'],
  data() {
    return {
      activeTab: 'heatmap',
      tabs: [
        { key: 'heatmap', label: '섹터 히트맵' },
        { key: 'rotation', label: '자금 흐름' },
        { key: 'forecast', label: 'AI 예측' }
      ],
      forecastData: null,
      forecastLoading: false,
      forecastError: false,
      forecastRetryCount: 0,
      showForecastDetail: false,
      rotationData: [],
      rotationLoading: false,
      rotationError: false,
      _forecastRetryTimer: null
    }
  },
  computed: {
    // [정리됨] isCrashStatus / displayMarketStatus / usdKrwDisplay 는 시장지표 탭 제거에 따라 미사용
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
      if (newTab === 'rotation' && this.rotationData.length === 0) {
        this.loadRotation()
      }
    }
  },
  beforeUnmount() {
    // forecast 자동 재시도 타이머 정리 — 언마운트 후 60s 타임아웃 AI 호출이 유령 실행되는 것 방지
    if (this._forecastRetryTimer) clearTimeout(this._forecastRetryTimer)
  },
  methods: {
    async loadForecast() {
      this.forecastLoading = true
      this.forecastError = false
      try {
        const res = await marketAPI.getForecast()
        if (res.data && res.data.success !== false) {
          this.forecastData = res.data
        } else {
          this.forecastError = true
          // 자동 재시도 1회 (5초 후) — 타이머는 언마운트 시 정리(유령 AI 호출 방지)
          if (this.forecastRetryCount < 1) {
            this.forecastRetryCount++
            this._forecastRetryTimer = setTimeout(() => this.loadForecast(), 5000)
          }
        }
      } catch (e) {
        console.error('Forecast load failed:', e)
        this.forecastError = true
        if (this.forecastRetryCount < 1) {
          this.forecastRetryCount++
          this._forecastRetryTimer = setTimeout(() => this.loadForecast(), 5000)
        }
      } finally {
        this.forecastLoading = false
      }
    },
    retryForecast() {
      this.forecastRetryCount = 0
      this.forecastData = null
      this.loadForecast()
    },
    async loadRotation() {
      this.rotationLoading = true
      this.rotationError = false
      try {
        const res = await sectorAPI.getSectorRotation()
        const d = res.data
        if (d && d.success !== false && d.data) {
          this.rotationData = d.data
        } else {
          this.rotationError = true
        }
      } catch (e) {
        console.error('Rotation load failed:', e)
        this.rotationError = true
      } finally {
        this.rotationLoading = false
      }
    },
    getFlowIcon(direction) {
      if (direction === 'INFLOW') return '\u25B2'   // ▲
      if (direction === 'OUTFLOW') return '\u25BC'   // ▼
      return '\u25CF'                                  // ●
    },
    getFlowClass(direction) {
      if (direction === 'INFLOW') return 'flow-inflow'
      if (direction === 'OUTFLOW') return 'flow-outflow'
      return 'flow-neutral'
    },
    getFlowBarWidth(item) {
      if (!this.rotationData.length) return '0%'
      const maxAbs = Math.max(...this.rotationData.map(r => Math.abs(r.changeRate || 0)), 1)
      return Math.min(100, (Math.abs(item.changeRate || 0) / maxAbs) * 100) + '%'
    },
    formatBillion(val) {
      if (!val && val !== 0) return '-'
      const n = Number(val)
      if (n >= 10000) return (n / 10000).toFixed(1) + '조'
      return n.toLocaleString('ko-KR') + '억'
    },
    getBlockStyle(sector) {
      const pct = sector.percentage || 0
      const size = Math.max(60, Math.min(140, 60 + pct * 1.6))
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
    }
  }
}
</script>

<style scoped>
.section-card {
  background: var(--border-light);
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
.more-link { font-size: 13px; color: var(--primary-start); text-decoration: none; }
.more-link:hover { color: #8b9cf7; }
/* Empty / Error state box */
.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 20px;
  text-align: center;
}
.state-box-sm { padding: 28px 16px; }
.state-icon { font-size: 36px; margin-bottom: 12px; opacity: 0.6; }
.state-text { font-size: 14px; color: rgba(255,255,255,0.4); margin: 0 0 16px 0; }
.state-box-sm .state-text { margin-bottom: 0; }
.state-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  background: rgba(102,126,234,0.12);
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 10px;
  color: var(--primary-start);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.state-btn:hover {
  background: rgba(102,126,234,0.22);
  border-color: var(--primary-start);
}

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
.ind-value.data-delayed { color: rgba(245, 158, 11, 0.7); font-size: 13px; font-weight: 500; }

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
.gauge-fill.adr-crash { background: linear-gradient(90deg, #dc2626, #991b1b); animation: crashPulse 1.5s ease-in-out infinite; }
@keyframes crashPulse { 0%,100%{opacity:1} 50%{opacity:0.6} }
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

.market-status.crash-status {
  color: #fca5a5;
  background: rgba(220, 38, 38, 0.15);
  border: 1px solid rgba(220, 38, 38, 0.3);
  font-weight: 700;
  animation: crashPulse 1.5s ease-in-out infinite;
}

.analysis-date {
  font-size: 11px;
  color: rgba(255,255,255,0.35);
  text-align: right;
  margin-bottom: 6px;
}
.more-links { margin-top: 8px; }
.more-links a { font-size: 12px; color: rgba(255,255,255,0.4); text-decoration: none; }
.more-links a:hover { color: var(--primary-start); }

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
  border-top-color: var(--primary-start);
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
  background: var(--border-light);
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  margin-bottom: 12px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.25);
  border-radius: 8px;
  font-size: 12px;
  color: rgba(245, 158, 11, 0.85);
}
.retry-btn-sm {
  padding: 4px 12px;
  background: rgba(102,126,234,0.15);
  border: 1px solid rgba(102,126,234,0.3);
  border-radius: 6px;
  color: var(--primary-start);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}
.retry-btn-sm:hover {
  background: rgba(102,126,234,0.25);
}
.forecast-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 0;
}
.error-icon { font-size: 28px; }
.error-text {
  font-size: 14px;
  color: rgba(255,255,255,0.5);
  font-weight: 600;
}
.retry-btn {
  padding: 8px 20px;
  background: rgba(102,126,234,0.15);
  border: 1px solid rgba(102,126,234,0.3);
  border-radius: 8px;
  color: var(--primary-start);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.retry-btn:hover {
  background: rgba(102,126,234,0.25);
  border-color: var(--primary-start);
}
.forecast-detail-btn {
  display: block;
  margin: 12px auto 0;
  padding: 6px 16px;
  background: transparent;
  border: 1px solid rgba(255,255,255,0.15);
  border-radius: 8px;
  color: var(--primary-start);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.forecast-detail-btn:hover {
  background: rgba(102,126,234,0.1);
  border-color: var(--primary-start);
  color: #8b9cf7;
}

/* Rotation (자금 흐름) */
.rotation-area { padding: 4px 0; }
.rotation-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.summary-badge {
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
}
.summary-badge.inflow {
  background: rgba(16, 185, 129, 0.12);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.25);
}
.summary-badge.neutral {
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.summary-badge.outflow {
  background: rgba(239, 68, 68, 0.12);
  color: #fca5a5;
  border: 1px solid rgba(239, 68, 68, 0.25);
}
.rotation-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rotation-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 8px;
  transition: background 0.2s;
}
.rotation-item:hover { background: rgba(255, 255, 255, 0.06); }
.rotation-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 110px;
}
.flow-icon { font-size: 11px; font-weight: 700; }
.flow-icon.flow-inflow { color: #34d399; }
.flow-icon.flow-outflow { color: #f87171; }
.flow-icon.flow-neutral { color: rgba(255, 255, 255, 0.35); font-size: 8px; }
.rotation-name {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 80px;
}
.rotation-center { flex: 1; }
.rotation-bar-track {
  height: 6px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 3px;
  overflow: hidden;
}
.rotation-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}
.rotation-bar-fill.flow-inflow { background: linear-gradient(90deg, rgba(16, 185, 129, 0.3), #34d399); }
.rotation-bar-fill.flow-outflow { background: linear-gradient(90deg, rgba(239, 68, 68, 0.3), #f87171); }
.rotation-bar-fill.flow-neutral { background: rgba(255, 255, 255, 0.15); }
.rotation-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  min-width: 70px;
}
.rotation-rate {
  font-size: 12px;
  font-weight: 700;
}
.rotation-rate.flow-inflow { color: #34d399; }
.rotation-rate.flow-outflow { color: #f87171; }
.rotation-rate.flow-neutral { color: rgba(255, 255, 255, 0.4); }
.rotation-amount {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.35);
}

@media (max-width: 768px) {
  .section-card { padding: 16px; border-radius: 14px; }
  .section-title-row h2 { font-size: 14px; }
  .scenario-cards { grid-template-columns: 1fr; }
  .forecast-chart-container { height: 200px; }
}
</style>
