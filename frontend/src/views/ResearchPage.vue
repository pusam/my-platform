<template>
  <div class="research-page">
    <GlobalNav subtitle="종목 발굴" />

    <div class="research-tab-bar">
      <div class="research-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['research-tab', { active: activeTab === tab.key }]"
          @click="activeTab = tab.key"
        >
          <span class="rt-icon">{{ tab.icon }}</span>
          <span class="rt-label">{{ tab.label }}</span>
        </button>
      </div>
    </div>

    <div class="research-content">
      <EarningsScreenerPage v-if="activeTab === 'screener'" :embedded="true" />
      <SectorTradingPage v-if="activeTab === 'sector'" :embedded="true" />
      <InvestorAnalysisPage v-if="activeTab === 'investor'" :embedded="true" />
      <SectionRadar v-if="activeTab === 'radar'" />
    </div>
  </div>
</template>

<script>
import GlobalNav from '../components/GlobalNav.vue'
import EarningsScreenerPage from './EarningsScreenerPage.vue'
import SectorTradingPage from './SectorTradingPage.vue'
import InvestorAnalysisPage from './InvestorAnalysisPage.vue'
import SectionRadar from '../components/v2/SectionRadar.vue'

export default {
  name: 'ResearchPage',
  components: { GlobalNav, EarningsScreenerPage, SectorTradingPage, InvestorAnalysisPage, SectionRadar },
  data() {
    return {
      activeTab: 'screener',
      tabs: [
        { key: 'screener', icon: '🔬', label: '실적 스크리너' },
        { key: 'sector', icon: '📊', label: '섹터 거래대금' },
        { key: 'investor', icon: '💰', label: '투자자 분석' },
        { key: 'radar', icon: '🎯', label: '선점 레이더' }
      ]
    }
  }
}
</script>

<style scoped>
.research-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
  color: #e0e0e0;
}

.research-tab-bar {
  display: flex;
  justify-content: center;
  padding: 12px 20px;
}

.research-tabs {
  display: flex;
  gap: 4px;
  background: rgba(255,255,255,0.04);
  padding: 4px;
  border-radius: 12px;
}

.research-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-radius: 9px;
  transition: all 0.2s;
  white-space: nowrap;
}

.research-tab:hover {
  color: rgba(255,255,255,0.75);
  background: rgba(255,255,255,0.04);
}

.research-tab.active {
  background: rgba(102,126,234,0.18);
  color: #a5b4fc;
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(102,126,234,0.15);
}

.rt-icon { font-size: 14px; }
.rt-label { font-size: 13px; }

.research-content {
  padding: 0 20px 20px;
}

@media (max-width: 768px) {
  .research-tabs { width: 100%; }
  .research-tab {
    flex: 1;
    justify-content: center;
    padding: 7px 10px;
  }
  .rt-label { font-size: 12px; }
  .research-tab-bar { padding: 8px 12px; }
  .research-content { padding: 0 12px 12px; }
}
</style>
