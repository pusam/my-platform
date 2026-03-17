<template>
  <div class="research-page">
    <GlobalNav />
    <div class="page-header-unified">
      <div class="header-title">
        <h1>종목 발굴</h1>
        <p class="subtitle">퀀트 스크리너 + 섹터 거래대금 + 투자자 수급 + 선점 레이더</p>
      </div>
    </div>

    <div class="tab-bar">
      <button class="tab-btn" :class="{ active: activeTab === 'screener' }" @click="activeTab = 'screener'">
        🔬 실적 스크리너
      </button>
      <button class="tab-btn" :class="{ active: activeTab === 'sector' }" @click="activeTab = 'sector'">
        📊 섹터 거래대금
      </button>
      <button class="tab-btn" :class="{ active: activeTab === 'investor' }" @click="activeTab = 'investor'">
        💰 투자자 분석
      </button>
      <button class="tab-btn radar-btn" :class="{ active: activeTab === 'radar' }" @click="activeTab = 'radar'">
        🎯 선점 레이더
      </button>
    </div>

    <EarningsScreenerPage v-if="activeTab === 'screener'" :embedded="true" />
    <SectorTradingPage v-if="activeTab === 'sector'" :embedded="true" />
    <InvestorAnalysisPage v-if="activeTab === 'investor'" :embedded="true" />
    <SectionRadar v-if="activeTab === 'radar'" />
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
      activeTab: 'screener'
    }
  }
}
</script>

<style scoped>
.research-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
  color: #e0e0e0;
  padding: 20px;
}

.tab-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

.tab-btn {
  padding: 10px 24px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.6);
  border: none;
  cursor: pointer;
  font-weight: 600;
  transition: 0.2s;
}

.tab-btn.active {
  color: #fff;
  background: linear-gradient(135deg, #667eea, #764ba2);
}

.tab-btn.radar-btn.active {
  background: linear-gradient(135deg, #f59e0b, #d97706);
}

.tab-btn:hover:not(.active) {
  background: rgba(255, 255, 255, 0.1);
}

@media (max-width: 768px) {
  .tab-bar {
    flex-wrap: wrap;
    gap: 6px;
  }
  .tab-btn {
    padding: 10px 16px;
    font-size: 13px;
  }
}
</style>
