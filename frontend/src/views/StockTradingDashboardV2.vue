<template>
  <div class="v2-dashboard">
    <div class="v2-content">
      <!-- 헤더 -->
      <DashboardHeader @open-search="showSearch = true" />

      <!-- 2x2 그리드 -->
      <div class="dashboard-grid">
        <!-- A. AI 전략 -->
        <SectionAiStrategy
          :data="aiStrategyData"
          :loading="sections.aiStrategy.loading"
          :error="sections.aiStrategy.error"
        />

        <!-- B. 시장 지도 -->
        <SectionMarketMap
          :sectorData="sectorData"
          :marketData="marketData"
          :globalData="globalData"
          :loading="sections.marketMap.loading"
          :error="sections.marketMap.error"
        />

        <!-- C. 스마트 머니 -->
        <SectionSmartMoney
          :tradesData="tradesData"
          :consecutiveData="consecutiveData"
          :surgeData="surgeData"
          :loading="sections.smartMoney.loading"
          :error="sections.smartMoney.error"
        />

        <!-- D. AI 리서치 -->
        <SectionResearch
          :screenerData="screenerData"
          :newsData="newsData"
          :loading="sections.research.loading"
          :error="sections.research.error"
        />
      </div>

      <!-- 종목 검색 모달 -->
      <StockSearchModal
        :visible="showSearch"
        @close="showSearch = false"
        @select="onStockSelect"
      />
    </div>
  </div>
</template>

<script>
import DashboardHeader from '../components/v2/DashboardHeader.vue'
import SectionAiStrategy from '../components/v2/SectionAiStrategy.vue'
import SectionMarketMap from '../components/v2/SectionMarketMap.vue'
import SectionSmartMoney from '../components/v2/SectionSmartMoney.vue'
import SectionResearch from '../components/v2/SectionResearch.vue'
import StockSearchModal from '../components/v2/StockSearchModal.vue'
import {
  aiStrategyAPI, sectorAPI, marketAPI, tradingIndicatorAPI,
  investorAPI, screenerAPI, newsAPI
} from '../utils/api'

export default {
  name: 'StockTradingDashboardV2',
  components: {
    DashboardHeader,
    SectionAiStrategy,
    SectionMarketMap,
    SectionSmartMoney,
    SectionResearch,
    StockSearchModal
  },
  data() {
    return {
      showSearch: false,
      // Section states
      sections: {
        aiStrategy: { loading: true, error: false },
        marketMap: { loading: true, error: false },
        smartMoney: { loading: true, error: false },
        research: { loading: true, error: false }
      },
      // Section A: AI Strategy
      aiStrategyData: null,
      // Section B: Market Map
      sectorData: [],
      marketData: {},
      globalData: {},
      // Section C: Smart Money
      tradesData: { foreign: [], institution: [] },
      consecutiveData: [],
      surgeData: [],
      // Section D: Research
      screenerData: {},
      newsData: []
    }
  },
  mounted() {
    this.loadAllSections()
    this.setupKeyboardShortcut()
  },
  beforeUnmount() {
    this.removeKeyboardShortcut()
  },
  methods: {
    async loadAllSections() {
      const results = await Promise.allSettled([
        this.loadAiStrategy(),
        this.loadMarketMap(),
        this.loadSmartMoney(),
        this.loadResearch()
      ])
      results.forEach((r, i) => {
        if (r.status === 'rejected') {
          console.error('Section load failed:', i, r.reason)
        }
      })
    },

    // Section A: AI Strategy
    async loadAiStrategy() {
      try {
        this.sections.aiStrategy.loading = true
        this.sections.aiStrategy.error = false
        const res = await aiStrategyAPI.getLatest()
        if (res.data.success !== false) {
          this.aiStrategyData = res.data.data || res.data
        }
      } catch (e) {
        console.error('AI Strategy load failed:', e)
        this.sections.aiStrategy.error = true
      } finally {
        this.sections.aiStrategy.loading = false
      }
    },

    // Section B: Market Map
    async loadMarketMap() {
      try {
        this.sections.marketMap.loading = true
        this.sections.marketMap.error = false
        const [sectorRes, marketRes, leadingRes, nasdaqRes] = await Promise.allSettled([
          sectorAPI.getSectorTrading('TODAY'),
          marketAPI.getStatus(),
          tradingIndicatorAPI.getLeadingSectors(),
          tradingIndicatorAPI.getNasdaqFutures()
        ])
        if (sectorRes.status === 'fulfilled' && sectorRes.value?.data) {
          const d = sectorRes.value.data.data || sectorRes.value.data
          this.sectorData = Array.isArray(d) ? d : (d.sectors || [])
        }
        if (marketRes.status === 'fulfilled' && marketRes.value?.data) {
          this.marketData = marketRes.value.data.data || marketRes.value.data
        }
        this.globalData = {}
        if (nasdaqRes.status === 'fulfilled' && nasdaqRes.value?.data) {
          this.globalData.nasdaqFutures = nasdaqRes.value.data.data || nasdaqRes.value.data
        }
        if (leadingRes.status === 'fulfilled' && leadingRes.value?.data) {
          const ld = leadingRes.value.data.data || leadingRes.value.data
          this.globalData.leadingSectors = Array.isArray(ld) ? ld : []
        }
      } catch (e) {
        console.error('Market Map load failed:', e)
        this.sections.marketMap.error = true
      } finally {
        this.sections.marketMap.loading = false
      }
    },

    // Section C: Smart Money
    async loadSmartMoney() {
      try {
        this.sections.smartMoney.loading = true
        this.sections.smartMoney.error = false
        const [foreignRes, instRes, consecutiveRes, surgeRes] = await Promise.allSettled([
          investorAPI.getTopTrades('FOREIGN', 'BUY', 10),
          investorAPI.getTopTrades('INSTITUTION', 'BUY', 10),
          investorAPI.getAllConsecutiveBuy(3),
          investorAPI.getAllSurgeStocks()
        ])
        if (foreignRes.status === 'fulfilled' && foreignRes.value?.data) {
          const d = foreignRes.value.data.data || foreignRes.value.data
          this.tradesData.foreign = Array.isArray(d) ? d : []
        }
        if (instRes.status === 'fulfilled' && instRes.value?.data) {
          const d = instRes.value.data.data || instRes.value.data
          this.tradesData.institution = Array.isArray(d) ? d : []
        }
        if (consecutiveRes.status === 'fulfilled' && consecutiveRes.value?.data) {
          const d = consecutiveRes.value.data.data || consecutiveRes.value.data
          this.consecutiveData = Array.isArray(d) ? d : []
        }
        if (surgeRes.status === 'fulfilled' && surgeRes.value?.data) {
          const d = surgeRes.value.data.data || surgeRes.value.data
          this.surgeData = Array.isArray(d) ? d : []
        }
      } catch (e) {
        console.error('Smart Money load failed:', e)
        this.sections.smartMoney.error = true
      } finally {
        this.sections.smartMoney.loading = false
      }
    },

    // Section D: Research
    async loadResearch() {
      try {
        this.sections.research.loading = true
        this.sections.research.error = false
        const [screenerRes, newsRes] = await Promise.allSettled([
          screenerAPI.getSummary(),
          newsAPI.getTodayNews()
        ])
        if (screenerRes.status === 'fulfilled' && screenerRes.value?.data) {
          this.screenerData = screenerRes.value.data.data || screenerRes.value.data
        }
        if (newsRes.status === 'fulfilled' && newsRes.value?.data) {
          const d = newsRes.value.data.data || newsRes.value.data
          this.newsData = Array.isArray(d) ? d : []
        }
      } catch (e) {
        console.error('Research load failed:', e)
        this.sections.research.error = true
      } finally {
        this.sections.research.loading = false
      }
    },

    onStockSelect(stock) {
      if (stock?.stockCode) {
        this.$router.push(`/stock/${stock.stockCode}`)
      }
    },

    setupKeyboardShortcut() {
      this._onKeydown = (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
          e.preventDefault()
          this.showSearch = true
        }
        if (e.key === 'Escape') {
          this.showSearch = false
        }
      }
      window.addEventListener('keydown', this._onKeydown)
    },

    removeKeyboardShortcut() {
      window.removeEventListener('keydown', this._onKeydown)
    }
  }
}
</script>

<style scoped>
.v2-dashboard {
  min-height: 100vh;
  background: linear-gradient(180deg, #0f0f1a 0%, #1a1a2e 40%, #16213e 100%);
  color: white;
}

.v2-content {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 24px 60px;
}

/* 2x2 Grid */
.dashboard-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

/* Responsive */
@media (max-width: 1024px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .v2-content {
    padding: 12px 16px 40px;
  }
  .dashboard-grid {
    gap: 14px;
  }
}
</style>
