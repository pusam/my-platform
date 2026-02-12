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
    </template>
  </div>
</template>

<script>
import SkeletonLoader from './SkeletonLoader.vue'

export default {
  name: 'SectionMarketMap',
  components: { SkeletonLoader },
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
        { key: 'global', label: '글로벌' }
      ]
    }
  },
  computed: {
    maxTradingValue() {
      if (this.sectorData.length === 0) return 1
      return Math.max(...this.sectorData.map(s => s.totalTradingValue || s.tradingValue || 1))
    }
  },
  methods: {
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
</style>
