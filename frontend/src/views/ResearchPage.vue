<template>
  <div class="research-page">
    <GlobalNav subtitle="분석" />

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
      <SectionAiStrategy
        v-if="activeTab === 'ai'"
        :data="aiStrategyData"
        :loading="aiLoading"
        :error="aiError"
        @retry="loadAiStrategy"
      />
      <SectionSmartMoney
        v-if="activeTab === 'smart'"
        :tradesData="tradesData"
        :consecutiveData="consecutiveData"
        :surgeData="surgeData"
        :loading="smartLoading"
        :error="smartError"
        @retry="loadSmartMoney"
      />
      <MarketTimingPage v-if="activeTab === 'timing'" :embedded="true" />
      <SectionWatchlist v-if="activeTab === 'watchlist'" />
      <SectionEarnings v-if="activeTab === 'earnings'" />
      <SectionBacktest v-if="activeTab === 'backtest'" />
      <NewsPage v-if="activeTab === 'news'" :embedded="true" />
    </div>
  </div>
</template>

<script>
import GlobalNav from '../components/GlobalNav.vue'
import EarningsScreenerPage from './EarningsScreenerPage.vue'
import SectorTradingPage from './SectorTradingPage.vue'
import InvestorAnalysisPage from './InvestorAnalysisPage.vue'
import MarketTimingPage from './MarketTimingPage.vue'
import SectionRadar from '../components/v2/SectionRadar.vue'
import SectionAiStrategy from '../components/v2/SectionAiStrategy.vue'
import SectionSmartMoney from '../components/v2/SectionSmartMoney.vue'
import SectionWatchlist from '../components/v2/SectionWatchlist.vue'
import SectionEarnings from '../components/v2/SectionEarnings.vue'
import SectionBacktest from '../components/v2/SectionBacktest.vue'
import NewsPage from './NewsPage.vue'
import {
  aiStrategyAPI, investorAPI
} from '../utils/api'

export default {
  name: 'ResearchPage',
  components: {
    GlobalNav, EarningsScreenerPage, SectorTradingPage, InvestorAnalysisPage,
    MarketTimingPage, SectionRadar, SectionAiStrategy, SectionSmartMoney,
    SectionWatchlist, SectionEarnings, SectionBacktest, NewsPage
  },
  data() {
    return {
      activeTab: this.$route.query.tab || 'screener',
      tabs: [
        { key: 'screener', icon: '🔬', label: '스크리너' },
        { key: 'sector', icon: '📊', label: '섹터' },
        { key: 'investor', icon: '💰', label: '투자자' },
        { key: 'radar', icon: '🎯', label: '레이더' },
        { key: 'ai', icon: '🤖', label: 'AI전략' },
        { key: 'smart', icon: '📈', label: '스마트머니' },
        { key: 'timing', icon: '⏱️', label: '시장타이밍' },
        { key: 'watchlist', icon: '⭐', label: '관심종목' },
        { key: 'earnings', icon: '📋', label: '실적공시' },
        { key: 'backtest', icon: '📊', label: 'AI성과' },
        { key: 'news', icon: '📰', label: '뉴스' }
      ],
      // AI 전략
      aiStrategyData: null,
      aiLoading: false,
      aiError: false,
      // 스마트머니
      tradesData: { foreign: [], institution: [] },
      consecutiveData: [],
      surgeData: [],
      smartLoading: false,
      smartError: false
    }
  },
  watch: {
    activeTab(tab) {
      if (tab === 'ai' && !this.aiStrategyData) this.loadAiStrategy()
      if (tab === 'smart' && !this.tradesData.foreign.length) this.loadSmartMoney()
    }
  },
  methods: {
    extractData(res) {
      const d = res?.data?.data ?? res?.data ?? res
      return d
    },
    async loadAiStrategy() {
      this.aiLoading = true
      this.aiError = false
      try {
        const res = await aiStrategyAPI.getLatest()
        const d = this.extractData(res)
        if (d?.strategies) this.aiStrategyData = d
        else this.aiError = true
      } catch { this.aiError = true }
      this.aiLoading = false
    },
    async loadSmartMoney() {
      this.smartLoading = true
      this.smartError = false
      try {
        const [fRes, iRes, cRes, sRes] = await Promise.allSettled([
          investorAPI.getTopTrades('FOREIGN', 'BUY', 10),
          investorAPI.getTopTrades('INSTITUTION', 'BUY', 10),
          investorAPI.getAllConsecutiveBuy(3),
          investorAPI.getAllSurgeStocks()
        ])
        this.tradesData.foreign = fRes.status === 'fulfilled' ? (this.extractData(fRes.value) || []) : []
        this.tradesData.institution = iRes.status === 'fulfilled' ? (this.extractData(iRes.value) || []) : []
        const cd = cRes.status === 'fulfilled' ? this.extractData(cRes.value) : null
        this.consecutiveData = Array.isArray(cd) ? cd : (cd ? Object.values(cd).flat() : [])
        const sd = sRes.status === 'fulfilled' ? this.extractData(sRes.value) : null
        this.surgeData = Array.isArray(sd) ? sd : (sd ? Object.values(sd).flat() : [])
      } catch { this.smartError = true }
      this.smartLoading = false
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
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
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
