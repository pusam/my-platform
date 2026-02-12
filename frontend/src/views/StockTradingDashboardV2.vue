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

// ===================== 유틸: 타임아웃 래퍼 =====================
function withTimeout(promise, ms = 3000) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error('TIMEOUT')), ms))
  ])
}
// ===================== Java API 응답 → 프론트 포맷 변환 =====================
function transformMarketData(d) {
  if (!d) return null
  // Java API: { kospi: { indexClose, indexChangeRate }, kosdaq: { ... }, combinedAdr, diagnosis }
  if (d.kospiIndex) return d // 이미 올바른 포맷 (V2 API)
  if (d.kospi && d.kospi.indexClose != null) {
    return {
      kospiIndex: Number(d.kospi.indexClose).toLocaleString('ko-KR', { minimumFractionDigits: 2 }),
      kospiChangeRate: Number(d.kospi.indexChangeRate) || 0,
      kosdaqIndex: d.kosdaq ? Number(d.kosdaq.indexClose).toLocaleString('ko-KR', { minimumFractionDigits: 2 }) : '-',
      kosdaqChangeRate: d.kosdaq ? Number(d.kosdaq.indexChangeRate) || 0 : 0,
      adr: Number(d.combinedAdr) || 0,
      marketStatus: d.diagnosis || ''
    }
  }
  return null
}

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
    // 섹터 데이터가 유효한지 (거래대금 있거나 섹터명 있으면 유효)
    hasSectorData(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(s => s.sectorName || (s.totalTradingValue && s.totalTradingValue > 0))
    },
    // 매매 데이터가 유효한지 (금액 전부 0이면 무효)
    hasTradeData(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(t => t.netBuyAmount && t.netBuyAmount !== 0)
    },
    // 투자자별 Map → 평탄 배열 변환 ({ FOREIGN: [...], INSTITUTION: [...] } → [...])
    flattenInvestorMap(data) {
      if (!data) return []
      if (Array.isArray(data)) return data.length > 0 ? data : []
      if (typeof data === 'object') {
        const merged = []
        const seen = new Set()
        for (const [key, arr] of Object.entries(data)) {
          if (Array.isArray(arr)) {
            for (const item of arr) {
              const id = item.stockCode || JSON.stringify(item)
              if (!seen.has(id)) {
                seen.add(id)
                merged.push(item)
              }
            }
          }
        }
        return merged
      }
      return []
    },

    // Section A: AI 전략 (V2 → Java, 3초 타임아웃)
    async loadAiStrategy() {
      try {
        this.sections.aiStrategy.loading = true
        this.sections.aiStrategy.error = false
        // 1차: Python V2 API (3초 타임아웃)
        try {
          const res = await withTimeout(aiStrategyV2API.getLatest())
          const d = this.extractData(res)
          console.log('[V2] AI Strategy 원본:', JSON.stringify(d, null, 2))
          const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
          if (hasStocks) { this.aiStrategyData = d; return }
        } catch (e) { console.warn('[V2] AI Strategy 실패:', e.message) }
        // 2차: Java API (3초 타임아웃)
        try {
          const res = await withTimeout(aiStrategyAPI.getLatest())
          const d = this.extractData(res)
          console.log('[Java] AI Strategy 원본:', JSON.stringify(d, null, 2))
          const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
          if (hasStocks) { this.aiStrategyData = d; return }
        } catch (e) { console.warn('[Java] AI Strategy 실패:', e.message) }
        // API 모두 실패 시 에러 상태 표시
        console.warn('[AI Strategy] 데이터 없음')
        this.sections.aiStrategy.error = true
      } catch {
        this.aiStrategyData = null
        this.sections.aiStrategy.error = true
      } finally {
        this.sections.aiStrategy.loading = false
      }
    },

    // Section B: 시장 지도 (V2 → Java, 3초 타임아웃)
    async loadMarketMap() {
      try {
        this.sections.marketMap.loading = true
        this.sections.marketMap.error = false
        const [sectorRes, marketRes, leadingRes, nasdaqRes] = await Promise.allSettled([
          withTimeout(marketV2API.getSectors('TODAY').catch(() => sectorAPI.getSectorTrading('TODAY')), 10000),
          withTimeout(marketV2API.getStatus().catch(() => marketAPI.getStatus())),
          withTimeout(marketV2API.getLeadingSectors().catch(() => tradingIndicatorAPI.getLeadingSectors())),
          withTimeout(marketV2API.getNasdaqFutures().catch(() => tradingIndicatorAPI.getNasdaqFutures()))
        ])
        // Sector
        if (sectorRes.status === 'fulfilled') {
          const d = this.extractData(sectorRes.value)
          console.log('[API] Sector 원본:', d)
          const arr = Array.isArray(d) ? d : (d?.sectors || [])
          this.sectorData = this.hasSectorData(arr) ? arr : []
        } else {
          this.sectorData = []
        }
        // Market - Java API 포맷 변환 (kospi.indexClose → kospiIndex)
        if (marketRes.status === 'fulfilled') {
          const d = this.extractData(marketRes.value)
          console.log('[API] Market 원본:', d)
          const transformed = transformMarketData(d)
          this.marketData = transformed || {}
        } else {
          this.marketData = {}
        }
        // Global
        this.globalData = {}
        if (nasdaqRes.status === 'fulfilled') {
          const d = this.extractData(nasdaqRes.value)
          this.globalData.nasdaqFutures = (d && d.price) ? d : null
        } else {
          this.globalData.nasdaqFutures = null
        }
        if (leadingRes.status === 'fulfilled') {
          const d = this.extractData(leadingRes.value)
          this.globalData.leadingSectors = (Array.isArray(d) && d.length > 0) ? d : []
        } else {
          this.globalData.leadingSectors = []
        }
      } catch {
        this.sectorData = []
        this.marketData = {}
        this.globalData = {}
        this.sections.marketMap.error = true
      } finally {
        this.sections.marketMap.loading = false
      }
    },

    // Section C: 스마트 머니 (V2 → Java, 3초 타임아웃)
    async loadSmartMoney() {
      try {
        this.sections.smartMoney.loading = true
        this.sections.smartMoney.error = false
        const [foreignRes, instRes, consecutiveRes, surgeRes] = await Promise.allSettled([
          withTimeout(investorV2API.getTopTrades('FOREIGN', 10).catch(() => investorAPI.getTopTrades('FOREIGN', 'BUY', 10))),
          withTimeout(investorV2API.getTopTrades('INSTITUTION', 10).catch(() => investorAPI.getTopTrades('INSTITUTION', 'BUY', 10))),
          withTimeout(investorV2API.getAllConsecutiveBuy(3).catch(() => investorAPI.getAllConsecutiveBuy(3))),
          withTimeout(investorV2API.getAllSurgeStocks().catch(() => investorAPI.getAllSurgeStocks()))
        ])
        // Foreign
        const fd = foreignRes.status === 'fulfilled' ? this.extractData(foreignRes.value) : null
        console.log('[API] Foreign 원본:', fd)
        this.tradesData.foreign = this.hasTradeData(fd) ? fd : []
        // Institution
        const id = instRes.status === 'fulfilled' ? this.extractData(instRes.value) : null
        console.log('[API] Institution 원본:', id)
        this.tradesData.institution = this.hasTradeData(id) ? id : []
        // Consecutive - API가 { FOREIGN: [...], INSTITUTION: [...] } Map 반환
        const cd = consecutiveRes.status === 'fulfilled' ? this.extractData(consecutiveRes.value) : null
        this.consecutiveData = this.flattenInvestorMap(cd)
        // Surge - API가 { FOREIGN: [...], INSTITUTION: [...], COMMON: [...] } Map 반환
        const sd = surgeRes.status === 'fulfilled' ? this.extractData(surgeRes.value) : null
        this.surgeData = this.flattenInvestorMap(sd)
      } catch {
        this.tradesData = { foreign: [], institution: [] }
        this.consecutiveData = []
        this.surgeData = []
        this.sections.smartMoney.error = true
      } finally {
        this.sections.smartMoney.loading = false
      }
    },

    // Section D: AI 리서치 (V2 → Java, 3초 타임아웃)
    async loadResearch() {
      try {
        this.sections.research.loading = true
        this.sections.research.error = false
        const [screenerRes, newsRes] = await Promise.allSettled([
          withTimeout(screenerV2API.getSummary().catch(() => screenerAPI.getSummary()), 15000),
          withTimeout(newsV2API.getTodayNews().catch(() => newsAPI.getTodayNews()))
        ])
        // Screener
        const sd = screenerRes.status === 'fulfilled' ? this.extractData(screenerRes.value) : null
        console.log('[API] Screener 원본:', sd)
        const hasScreener = sd && (sd.magicFormula?.length || sd.lowPeg?.length || sd.turnaround?.length)
        this.screenerData = hasScreener ? sd : {}
        // News
        const nd = newsRes.status === 'fulfilled' ? this.extractData(newsRes.value) : null
        console.log('[API] News 원본:', nd)
        this.newsData = (Array.isArray(nd) && nd.length > 0) ? nd : []
      } catch {
        this.screenerData = {}
        this.newsData = []
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
