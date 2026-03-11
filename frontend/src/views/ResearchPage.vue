<template>
  <div class="research-page">
    <div class="page-header-unified">
      <BackButton :dark="true" />
      <div class="header-title">
        <h1>리서치</h1>
        <p class="subtitle">실적 스크리너와 섹터별 거래대금을 한눈에</p>
      </div>
    </div>

    <div class="tab-bar">
      <button class="tab-btn" :class="{ active: activeTab === 'screener' }" @click="activeTab = 'screener'">실적 스크리너</button>
      <button class="tab-btn" :class="{ active: activeTab === 'sector' }" @click="activeTab = 'sector'">섹터 거래대금</button>
    </div>

    <EarningsScreenerPage v-if="activeTab === 'screener'" :embedded="true" />
    <SectorTradingPage v-if="activeTab === 'sector'" :embedded="true" />
  </div>
</template>

<script>
import BackButton from '../components/BackButton.vue'
import EarningsScreenerPage from './EarningsScreenerPage.vue'
import SectorTradingPage from './SectorTradingPage.vue'

export default {
  name: 'ResearchPage',
  components: { BackButton, EarningsScreenerPage, SectorTradingPage },
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

.tab-btn:hover:not(.active) {
  background: rgba(255, 255, 255, 0.1);
}
</style>
