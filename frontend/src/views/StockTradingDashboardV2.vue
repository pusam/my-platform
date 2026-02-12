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
  investorAPI, screenerAPI, newsAPI,
  aiStrategyV2API, marketV2API, investorV2API, screenerV2API, newsV2API
} from '../utils/api'

// ===================== 메인 컴포넌트 =====================

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
      sections: {
        aiStrategy: { loading: true, error: false },
        marketMap: { loading: true, error: false },
        smartMoney: { loading: true, error: false },
        research: { loading: true, error: false }
      },
      aiStrategyData: null,
      sectorData: [],
      marketData: {},
      globalData: {},
      tradesData: { foreign: [], institution: [] },
      consecutiveData: [],
      surgeData: [],
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
      await Promise.allSettled([
        this.loadAiStrategy(),
        this.loadMarketMap(),
        this.loadSmartMoney(),
        this.loadResearch()
      ])
    },

    // ---- helpers ----
    extractData(res) {
      if (!res?.data) return null
      return res.data.data !== undefined ? res.data.data : res.data
    },
    hasData(d) {
      if (!d) return false
      if (Array.isArray(d)) return d.length > 0
      if (typeof d === 'object') return Object.keys(d).length > 0
      return true
    },
    // 섹터 데이터가 유효한지 (전부 0이면 무효)
    hasSectorData(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(s => s.changeRate !== 0 && s.changeRate !== undefined)
    },
    // 매매 데이터가 유효한지 (금액 전부 0이면 무효)
    hasTradeData(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(t => t.netBuyAmount && t.netBuyAmount !== 0)
    },

    // Section A (V2 → Java)
    async loadAiStrategy() {
      try {
        this.sections.aiStrategy.loading = true
        this.sections.aiStrategy.error = false
        // 1차: Python V2 API
        try {
          const res = await aiStrategyV2API.getLatest()
          const d = this.extractData(res)
          const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
          if (hasStocks) { this.aiStrategyData = d; return }
        } catch { /* V2 실패 → Java 시도 */ }
        // 2차: Java API
        const res = await aiStrategyAPI.getLatest()
        const d = this.extractData(res)
        const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
        if (hasStocks) this.aiStrategyData = d
        else this.sections.aiStrategy.error = true
      } catch {
        this.sections.aiStrategy.error = true
      } finally {
        this.sections.aiStrategy.loading = false
      }
    },

    // Section B (V2 → Java)
    async loadMarketMap() {
      try {
        this.sections.marketMap.loading = true
        this.sections.marketMap.error = false
        const [sectorRes, marketRes, leadingRes, nasdaqRes] = await Promise.allSettled([
          marketV2API.getSectors('TODAY').catch(() => sectorAPI.getSectorTrading('TODAY')),
          marketV2API.getStatus().catch(() => marketAPI.getStatus()),
          marketV2API.getLeadingSectors().catch(() => tradingIndicatorAPI.getLeadingSectors()),
          marketV2API.getNasdaqFutures().catch(() => tradingIndicatorAPI.getNasdaqFutures())
        ])
        // Sector
        if (sectorRes.status === 'fulfilled') {
          const d = this.extractData(sectorRes.value)
          const arr = Array.isArray(d) ? d : (d?.sectors || [])
          this.sectorData = this.hasSectorData(arr) ? arr : []
        }
        // Market
        if (marketRes.status === 'fulfilled') {
          const d = this.extractData(marketRes.value)
          this.marketData = (d && d.kospiIndex) ? d : {}
        }
        // Global
        this.globalData = {}
        if (nasdaqRes.status === 'fulfilled') {
          const d = this.extractData(nasdaqRes.value)
          if (d && d.price) this.globalData.nasdaqFutures = d
        }
        if (leadingRes.status === 'fulfilled') {
          const d = this.extractData(leadingRes.value)
          if (Array.isArray(d) && d.length > 0) this.globalData.leadingSectors = d
        }
        // 모든 데이터가 비어있으면 에러 표시
        if (!this.sectorData.length && !Object.keys(this.marketData).length) {
          this.sections.marketMap.error = true
        }
      } catch {
        this.sections.marketMap.error = true
      } finally {
        this.sections.marketMap.loading = false
      }
    },

    // Section C (V2 → Java)
    async loadSmartMoney() {
      try {
        this.sections.smartMoney.loading = true
        this.sections.smartMoney.error = false
        const [foreignRes, instRes, consecutiveRes, surgeRes] = await Promise.allSettled([
          investorV2API.getTopTrades('FOREIGN', 10).catch(() => investorAPI.getTopTrades('FOREIGN', 'BUY', 10)),
          investorV2API.getTopTrades('INSTITUTION', 10).catch(() => investorAPI.getTopTrades('INSTITUTION', 'BUY', 10)),
          investorV2API.getAllConsecutiveBuy(3).catch(() => investorAPI.getAllConsecutiveBuy(3)),
          investorV2API.getAllSurgeStocks().catch(() => investorAPI.getAllSurgeStocks())
        ])
        // Foreign
        const fd = foreignRes.status === 'fulfilled' ? this.extractData(foreignRes.value) : null
        this.tradesData.foreign = this.hasTradeData(fd) ? fd : []
        // Institution
        const id = instRes.status === 'fulfilled' ? this.extractData(instRes.value) : null
        this.tradesData.institution = this.hasTradeData(id) ? id : []
        // Consecutive
        const cd = consecutiveRes.status === 'fulfilled' ? this.extractData(consecutiveRes.value) : null
        this.consecutiveData = (Array.isArray(cd) && cd.length > 0) ? cd : []
        // Surge
        const sd = surgeRes.status === 'fulfilled' ? this.extractData(surgeRes.value) : null
        this.surgeData = (Array.isArray(sd) && sd.length > 0) ? sd : []
        // 모든 데이터가 비어있으면 에러 표시
        if (!this.tradesData.foreign.length && !this.tradesData.institution.length) {
          this.sections.smartMoney.error = true
        }
      } catch {
        this.sections.smartMoney.error = true
      } finally {
        this.sections.smartMoney.loading = false
      }
    },

    // Section D (V2 → Java)
    async loadResearch() {
      try {
        this.sections.research.loading = true
        this.sections.research.error = false
        const [screenerRes, newsRes] = await Promise.allSettled([
          screenerV2API.getSummary().catch(() => screenerAPI.getSummary()),
          newsV2API.getTodayNews().catch(() => newsAPI.getTodayNews())
        ])
        // Screener
        const sd = screenerRes.status === 'fulfilled' ? this.extractData(screenerRes.value) : null
        const hasScreener = sd && (sd.magicFormula?.length || sd.lowPeg?.length || sd.turnaround?.length)
        this.screenerData = hasScreener ? sd : {}
        // News
        const nd = newsRes.status === 'fulfilled' ? this.extractData(newsRes.value) : null
        this.newsData = (Array.isArray(nd) && nd.length > 0) ? nd : []
        // 모든 데이터가 비어있으면 에러 표시
        if (!Object.keys(this.screenerData).length && !this.newsData.length) {
          this.sections.research.error = true
        }
      } catch {
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

.dashboard-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

@media (max-width: 1024px) {
  .dashboard-grid { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .v2-content { padding: 12px 16px 40px; }
  .dashboard-grid { gap: 14px; }
}
</style>
