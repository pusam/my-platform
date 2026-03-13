<template>
  <div class="v2-dashboard">
    <div class="v2-content">
      <!-- 헤더 (GNB 3탭 통합) -->
      <DashboardHeader
        :activeTab="activeGnbTab"
        @open-search="showSearch = true"
        @tab-change="activeGnbTab = $event"
      />

      <!-- ═══ Tab 1: 시장 뷰 ═══ -->
      <div v-if="activeGnbTab === 'market'" class="tab-panel">
        <SectionMarketMap
          :sectorData="sectorData"
          :marketData="marketData"
          :globalData="globalData"
          :loading="sections.marketMap.loading"
          :error="sections.marketMap.error"
          @retry="loadMarketMap"
        />
        <!-- 뉴스 영역 -->
        <div class="news-panel section-card" v-if="!sections.research.loading">
          <div class="section-title-row">
            <h2><span class="section-icon">📰</span> 주요 뉴스</h2>
            <router-link to="/news" class="more-link">전체 뉴스 →</router-link>
          </div>
          <div v-if="newsData.length">
            <div
              v-for="(item, i) in newsData.slice(0, 8)"
              :key="'news-' + i"
              class="news-row"
            >
              <span class="news-title">{{ item.title }}</span>
              <span class="news-time">{{ formatNewsTime(item.publishedAt || item.summarizedAt) }}</span>
            </div>
          </div>
          <div v-else class="empty-msg">뉴스를 불러오는 중...</div>
        </div>
      </div>

      <!-- ═══ Tab 2: 종목 발굴 ═══ -->
      <div v-if="activeGnbTab === 'discover'" class="tab-panel">
        <div class="discover-tabs">
          <button
            v-for="tab in discoverSubTabs"
            :key="tab.key"
            :class="['discover-tab-btn', { active: discoverTab === tab.key }]"
            @click="discoverTab = tab.key"
          >
            <span class="dtab-icon">{{ tab.icon }}</span>
            {{ tab.label }}
          </button>
        </div>

        <SectionAiStrategy
          v-if="discoverTab === 'ai'"
          :data="aiStrategyData"
          :loading="sections.aiStrategy.loading"
          :error="sections.aiStrategy.error"
          @retry="loadAiStrategy"
        />
        <SectionWatchlist
          v-if="discoverTab === 'watchlist'"
        />
        <SectionSmartMoney
          v-if="discoverTab === 'smart'"
          :tradesData="tradesData"
          :consecutiveData="consecutiveData"
          :surgeData="surgeData"
          :loading="sections.smartMoney.loading"
          :error="sections.smartMoney.error"
          @retry="loadSmartMoney"
        />
        <SectionResearch
          v-if="discoverTab === 'screener'"
          :screenerData="screenerData"
          :newsData="newsData"
          :loading="sections.research.loading"
          :error="sections.research.error"
          @retry="loadResearch"
        />
        <SectionBacktest
          v-if="discoverTab === 'backtest'"
        />
        <SectionEarnings
          v-if="discoverTab === 'earnings'"
        />
      </div>

      <!-- ═══ Tab 3: 내 계좌/봇 ═══ -->
      <div v-if="activeGnbTab === 'account'" class="tab-panel">
        <PaperTradingPage :embedded="true" />
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
import SectionWatchlist from '../components/v2/SectionWatchlist.vue'
import SectionBacktest from '../components/v2/SectionBacktest.vue'
import SectionEarnings from '../components/v2/SectionEarnings.vue'
import StockSearchModal from '../components/v2/StockSearchModal.vue'
import PaperTradingPage from './PaperTradingPage.vue'
import {
  aiStrategyAPI, sectorAPI, marketAPI, tradingIndicatorAPI,
  investorAPI, screenerAPI, newsAPI,
  aiStrategyV2API, marketV2API, investorV2API, screenerV2API, newsV2API,
  globalFuturesAPI
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
  // Java API: { kospi: { indexClose, indexChangeRate }, kosdaq: { ... }, combinedAdr, diagnosis, analysisDate }
  if (d.kospiIndex) return d // 이미 올바른 포맷 (V2 API)
  if (d.kospi && d.kospi.indexClose != null) {
    // 당일 등락비 우선, 없으면 20일 ADR 사용
    const kospiDaily = d.kospi.dailyRatio ? Number(d.kospi.dailyRatio) : null
    const kosdaqDaily = d.kosdaq && d.kosdaq.dailyRatio ? Number(d.kosdaq.dailyRatio) : null
    const dailyRatio = (kospiDaily && kosdaqDaily) ? (kospiDaily + kosdaqDaily) / 2 :
                       (kospiDaily || kosdaqDaily || null)
    return {
      kospiIndex: Number(d.kospi.indexClose).toLocaleString('ko-KR', { minimumFractionDigits: 2 }),
      kospiChangeRate: Number(d.kospi.indexChangeRate) || 0,
      kosdaqIndex: d.kosdaq ? Number(d.kosdaq.indexClose).toLocaleString('ko-KR', { minimumFractionDigits: 2 }) : '-',
      kosdaqChangeRate: d.kosdaq ? Number(d.kosdaq.indexChangeRate) || 0 : 0,
      adr: Number(d.combinedAdr) || 0,
      dailyRatio: dailyRatio,
      marketStatus: d.diagnosis || '',
      analysisDate: d.analysisDate || null
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
    SectionWatchlist,
    SectionBacktest,
    SectionEarnings,
    StockSearchModal,
    PaperTradingPage
  },
  provide() {
    return {
      openStock: (code) => this.$router.push(`/stock/${code}`)
    }
  },
  data() {
    return {
      activeGnbTab: 'market',
      discoverTab: 'ai',
      discoverSubTabs: [
        { key: 'ai', label: 'AI 전략', icon: '🤖' },
        { key: 'watchlist', label: '관심종목', icon: '⭐' },
        { key: 'smart', label: '스마트 머니', icon: '💰' },
        { key: 'screener', label: '실적 스크리너', icon: '🔬' },
        { key: 'backtest', label: 'AI 성과', icon: '📊' },
        { key: 'earnings', label: '실적공시', icon: '📋' }
      ],
      showSearch: false,
      dataLoaded: { market: false, discover: false },
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
  watch: {
    activeGnbTab(tab) {
      this.loadTabData(tab)
    }
  },
  mounted() {
    this.loadTabData('market')
    this.loadNews() // 뉴스는 market + research 공용
    this.setupKeyboardShortcut()
    // 60초마다 활성 탭 데이터 자동 갱신
    this._refreshTimer = setInterval(() => {
      if (this.activeGnbTab === 'market') this.loadMarketMap()
      if (this.activeGnbTab === 'discover') this.loadSmartMoney()
    }, 60000)
  },
  beforeUnmount() {
    this.removeKeyboardShortcut()
    if (this._refreshTimer) {
      clearInterval(this._refreshTimer)
      this._refreshTimer = null
    }
  },
  methods: {
    // ---- 탭별 데이터 로딩 ----
    loadTabData(tab) {
      if (tab === 'market' && !this.dataLoaded.market) {
        this.loadMarketMap()
        this.dataLoaded.market = true
      }
      if (tab === 'discover' && !this.dataLoaded.discover) {
        this.loadAiStrategy()
        this.loadSmartMoney()
        this.loadResearch()
        this.dataLoaded.discover = true
      }
      // account 탭은 PaperTradingPage 내부에서 자체 로드
    },

    // ---- 뉴스 로딩 (공용) ----
    async loadNews() {
      try {
        let nd = null
        try {
          const res = await withTimeout(newsV2API.getTodayNews())
          nd = this.extractData(res)
        } catch { /* V2 실패 */ }
        if (!Array.isArray(nd) || nd.length === 0) {
          try {
            const res = await newsAPI.getTodayNews()
            nd = this.extractData(res)
          } catch { /* Java도 실패 */ }
        }
        if (!Array.isArray(nd) || nd.length === 0) {
          try {
            const fallback = await newsAPI.getRecentNews()
            nd = this.extractData(fallback) || []
          } catch { nd = [] }
        }
        this.newsData = Array.isArray(nd) ? nd.slice(0, 10) : []
      } catch {
        this.newsData = []
      }
    },

    // ---- 종목 선택 → 상세 페이지 이동 ----
    onStockSelect(stock) {
      if (stock?.stockCode) {
        this.$router.push(`/stock/${stock.stockCode}`)
      }
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
    hasSectorData(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(s => s.sectorName || (s.totalTradingValue && s.totalTradingValue > 0))
    },
    hasSectorChangeRate(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(s => s.changeRate && s.changeRate !== 0)
    },
    hasTradeData(arr) {
      if (!Array.isArray(arr) || arr.length === 0) return false
      return arr.some(t => t.netBuyAmount && t.netBuyAmount !== 0)
    },
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
    formatNewsTime(dateStr) {
      if (!dateStr) return ''
      try {
        const d = new Date(dateStr)
        const now = new Date()
        const diff = now - d
        if (diff < 3600000) return Math.floor(diff / 60000) + '분 전'
        if (diff < 86400000) return Math.floor(diff / 3600000) + '시간 전'
        return d.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' })
      } catch { return '' }
    },

    // Section B: 시장 지도 (V2 → Java, changeRate 검증 포함)
    async loadMarketMap() {
      try {
        this.sections.marketMap.loading = true
        this.sections.marketMap.error = false
        const [sectorRes, marketRes, leadingRes, nasdaqRes, usdKrwRes] = await Promise.allSettled([
          withTimeout(marketV2API.getSectors('TODAY'), 5000)
            .catch(() => null),
          withTimeout(marketV2API.getStatus().catch(() => marketAPI.getStatus())),
          withTimeout(marketV2API.getLeadingSectors().catch(() => tradingIndicatorAPI.getLeadingSectors())),
          withTimeout(marketV2API.getNasdaqFutures().catch(() => tradingIndicatorAPI.getNasdaqFutures())),
          withTimeout(globalFuturesAPI.getQuote('KRW'), 5000).catch(() => null)
        ])
        let sectorArr = []
        if (sectorRes.status === 'fulfilled' && sectorRes.value) {
          const d = this.extractData(sectorRes.value)
          const arr = Array.isArray(d) ? d : (d?.sectors || [])
          if (this.hasSectorData(arr)) {
            sectorArr = arr
          }
        }
        if (sectorArr.length === 0) {
          try {
            const javaRes = await withTimeout(sectorAPI.getSectorTrading('TODAY'), 120000)
            const jd = this.extractData(javaRes)
            const jarr = Array.isArray(jd) ? jd : (jd?.sectors || [])
            sectorArr = this.hasSectorData(jarr) ? jarr : []
          } catch (e) {
            console.warn('[API] Sector Java API 실패:', e.message)
          }
        }
        this.sectorData = sectorArr
        if (marketRes.status === 'fulfilled') {
          const d = this.extractData(marketRes.value)
          const transformed = transformMarketData(d)
          this.marketData = transformed || {}
        } else {
          this.marketData = {}
        }
        // globalData를 한번에 새 객체로 할당 (Vue 반응성 보장)
        const newGlobalData = {
          nasdaqFutures: null,
          leadingSectors: [],
          usdKrw: null
        }
        if (nasdaqRes.status === 'fulfilled') {
          const d = this.extractData(nasdaqRes.value)
          newGlobalData.nasdaqFutures = (d && d.price) ? d : null
        }
        if (leadingRes.status === 'fulfilled') {
          const d = this.extractData(leadingRes.value)
          newGlobalData.leadingSectors = (Array.isArray(d) && d.length > 0) ? d : []
        }
        // USD/KRW 환율
        if (usdKrwRes.status === 'fulfilled' && usdKrwRes.value) {
          const d = this.extractData(usdKrwRes.value)
          if (d && d.currentPrice) {
            newGlobalData.usdKrw = {
              price: Number(d.currentPrice).toLocaleString('ko-KR', { minimumFractionDigits: 2 }),
              changeRate: Number(d.changeRate) || 0
            }
          }
        }
        this.globalData = newGlobalData
      } catch {
        this.sectorData = []
        this.marketData = {}
        this.globalData = {}
        this.sections.marketMap.error = true
      } finally {
        this.sections.marketMap.loading = false
      }
    },

    // Section A: AI 전략 (V2 → Java, 3초 타임아웃)
    async loadAiStrategy() {
      try {
        this.sections.aiStrategy.loading = true
        this.sections.aiStrategy.error = false
        try {
          const res = await withTimeout(aiStrategyV2API.getLatest())
          const d = this.extractData(res)
          const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
          if (hasStocks) { this.aiStrategyData = d; return }
        } catch (e) { /* V2 실패 → Java 폴백 */ }
        try {
          const res = await withTimeout(aiStrategyAPI.getLatest())
          const d = this.extractData(res)
          const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
          if (hasStocks) { this.aiStrategyData = d; return }
        } catch (e) { /* Java API도 실패 */ }
        this.sections.aiStrategy.error = true
      } catch {
        this.aiStrategyData = null
        this.sections.aiStrategy.error = true
      } finally {
        this.sections.aiStrategy.loading = false
      }
    },

    // Section C: 스마트 머니 (실시간 KIS API 우선, 폴백: V2 → Java DB)
    async loadSmartMoney() {
      try {
        this.sections.smartMoney.loading = true
        this.sections.smartMoney.error = false
        const [foreignRes, instRes, consecutiveRes, surgeRes] = await Promise.allSettled([
          withTimeout(investorAPI.getTopTradesRealtime('FOREIGN', 10).catch(() =>
            investorV2API.getTopTrades('FOREIGN', 10).catch(() =>
              investorAPI.getTopTrades('FOREIGN', 'BUY', 10))), 10000),
          withTimeout(investorAPI.getTopTradesRealtime('INSTITUTION', 10).catch(() =>
            investorV2API.getTopTrades('INSTITUTION', 10).catch(() =>
              investorAPI.getTopTrades('INSTITUTION', 'BUY', 10))), 10000),
          withTimeout(investorV2API.getAllConsecutiveBuy(3).catch(() => investorAPI.getAllConsecutiveBuy(3))),
          withTimeout(investorV2API.getAllSurgeStocks().catch(() => investorAPI.getAllSurgeStocks()))
        ])
        const fd = foreignRes.status === 'fulfilled' ? this.extractData(foreignRes.value) : null
        this.tradesData.foreign = this.hasTradeData(fd) ? fd : []
        const id = instRes.status === 'fulfilled' ? this.extractData(instRes.value) : null
        this.tradesData.institution = this.hasTradeData(id) ? id : []
        const cd = consecutiveRes.status === 'fulfilled' ? this.extractData(consecutiveRes.value) : null
        this.consecutiveData = this.flattenInvestorMap(cd)
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
        const screenerRes = await withTimeout(
          screenerV2API.getSummary().catch(() => screenerAPI.getSummary()), 15000
        ).catch(() => null)
        const sd = screenerRes ? this.extractData(screenerRes) : null
        const hasScreener = sd && (sd.magicFormula?.length || sd.lowPeg?.length || sd.turnaround?.length)
        this.screenerData = hasScreener ? sd : {}
      } catch {
        this.screenerData = {}
        this.sections.research.error = true
      } finally {
        this.sections.research.loading = false
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

/* Tab Panel */
.tab-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* Discover Sub-tabs */
.discover-tabs {
  display: flex;
  gap: 6px;
  background: rgba(255,255,255,0.04);
  padding: 4px;
  border-radius: 12px;
  margin-bottom: 4px;
}
.discover-tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 16px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-radius: 9px;
  transition: all 0.2s;
}
.discover-tab-btn:hover {
  color: rgba(255,255,255,0.7);
  background: rgba(255,255,255,0.04);
}
.discover-tab-btn.active {
  background: rgba(102,126,234,0.15);
  color: #a5b4fc;
  font-weight: 600;
}
.dtab-icon { font-size: 14px; }

/* News Panel in Market Tab */
.news-panel {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 24px;
}
.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.section-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}
.section-icon { margin-right: 6px; }
.more-link { font-size: 13px; color: #667eea; text-decoration: none; }
.more-link:hover { color: #8b9cf7; }

.news-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 4px;
  border-bottom: 1px solid rgba(255,255,255,0.04);
  gap: 12px;
}
.news-row:last-child { border-bottom: none; }
.news-title {
  flex: 1;
  font-size: 13px;
  color: rgba(255,255,255,0.8);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.news-time {
  font-size: 11px;
  color: rgba(255,255,255,0.3);
  flex-shrink: 0;
}

.empty-msg {
  text-align: center;
  color: rgba(255,255,255,0.3);
  font-size: 13px;
  padding: 20px 0;
}

@media (max-width: 768px) {
  .v2-content { padding: 12px 16px 40px; }
  .tab-panel { gap: 14px; }
  .discover-tab-btn { padding: 8px 10px; font-size: 12px; }
}
</style>
