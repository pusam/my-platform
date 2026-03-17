<template>
  <div class="v2-dashboard">
    <GlobalNav />
    <div class="v2-content">
      <!-- 헤더 (GNB 3탭 통합) -->
      <DashboardHeader
        :activeTab="activeGnbTab"
        @open-search="showSearch = true"
        @tab-change="activeGnbTab = $event"
      />

      <!-- ═══ Tab 1: 오늘의 핵심 요약 ═══ -->
      <div v-if="activeGnbTab === 'market'" class="tab-panel">

        <!-- ① 시장 상태 바 -->
        <div class="market-status-bar" v-if="marketData">
          <div class="msb-item" :class="getChangeClass(marketData.kospiChangeRate)">
            <span class="msb-label">KOSPI</span>
            <span class="msb-value">{{ formatChange(marketData.kospiChangeRate) }}%</span>
          </div>
          <div class="msb-divider"></div>
          <div class="msb-item" :class="getChangeClass(marketData.kosdaqChangeRate)">
            <span class="msb-label">KOSDAQ</span>
            <span class="msb-value">{{ formatChange(marketData.kosdaqChangeRate) }}%</span>
          </div>
          <div class="msb-divider"></div>
          <div class="msb-item">
            <span class="msb-label">ADR</span>
            <span class="msb-value">{{ marketData.adr || marketData.combinedAdr || '-' }}</span>
          </div>
          <div class="msb-divider" v-if="globalData?.nasdaqFutures"></div>
          <div class="msb-item" v-if="globalData?.nasdaqFutures" :class="getChangeClass(globalData.nasdaqFutures.changeRate)">
            <span class="msb-label">나스닥</span>
            <span class="msb-value">{{ formatChange(globalData.nasdaqFutures.changeRate) }}%</span>
          </div>
        </div>
        <div v-else class="market-status-bar skeleton"><span>시장 데이터 로딩 중...</span></div>

        <!-- ② 시간대별 신호 (자동 전환) -->
        <div class="today-signals section-card">
          <div class="section-title-row">
            <h2>
              <span class="section-icon">{{ marketPhase.icon }}</span>
              {{ marketPhase.title }}
            </h2>
            <span class="phase-badge" :class="marketPhase.class">{{ marketPhase.label }}</span>
          </div>

          <!-- 로딩 -->
          <div v-if="phaseLoading" class="signal-skeleton">
            <div class="skel-row" v-for="i in 4" :key="i"><div class="skel-bar"></div></div>
          </div>

          <!-- 신호 목록 -->
          <div v-else-if="phaseSignals.length" class="signal-list">
            <div
              v-for="(sig, i) in phaseSignals"
              :key="'sig-' + i"
              class="signal-card"
              :class="sig.type"
              @click="sig.stockCode && goToStock(sig.stockCode)"
            >
              <div class="sig-badge">{{ sig.badge }}</div>
              <div class="sig-info">
                <span class="sig-name">{{ sig.stockName }}</span>
                <span class="sig-reason">{{ sig.reason }}</span>
              </div>
              <div class="sig-right" v-if="sig.changeRate != null">
                <span :class="Number(sig.changeRate) >= 0 ? 'positive' : 'negative'">
                  {{ Number(sig.changeRate) >= 0 ? '+' : '' }}{{ Number(sig.changeRate).toFixed(2) }}%
                </span>
              </div>
            </div>
          </div>
          <div v-else class="empty-signal">{{ marketPhase.empty }}</div>
        </div>

        <!-- ③ 관심종목 현황 -->
        <div class="watchlist-summary section-card" v-if="watchlistItems.length">
          <div class="section-title-row">
            <h2><span class="section-icon">⭐</span> 관심종목</h2>
            <button class="more-link" @click="activeGnbTab = 'discover'; discoverTab = 'watchlist'">전체 보기 →</button>
          </div>
          <div class="wl-list">
            <div
              v-for="item in watchlistItems.slice(0, 5)"
              :key="'wl-' + item.id"
              class="wl-row"
              @click="goToStock(item.stockCode)"
            >
              <span class="wl-risk" v-if="watchlistRisks[item.stockCode]"
                    :class="watchlistRisks[item.stockCode].riskLevel === 'DANGER' ? 'danger' : 'warning'">
                {{ watchlistRisks[item.stockCode].riskLevel === 'DANGER' ? '🔴' : '🟡' }}
              </span>
              <span class="wl-name">{{ item.stockName }}</span>
              <span class="wl-price" v-if="item.currentPrice">{{ Number(item.currentPrice).toLocaleString() }}</span>
              <span class="wl-change" v-if="item.changeRate != null"
                    :class="item.changeRate >= 0 ? 'positive' : 'negative'">
                {{ item.changeRate >= 0 ? '+' : '' }}{{ Number(item.changeRate).toFixed(2) }}%
              </span>
            </div>
          </div>
        </div>

        <!-- ④ AI 전략 TOP 픽 -->
        <div class="ai-top-picks section-card" v-if="aiTopPicks.length">
          <div class="section-title-row">
            <h2><span class="section-icon">🤖</span> AI 전략 TOP 픽</h2>
          </div>
          <div class="top-picks-grid">
            <div
              v-for="pick in aiTopPicks"
              :key="'pick-' + pick.stockCode"
              class="pick-card"
              @click="goToStock(pick.stockCode)"
            >
              <div class="pick-strategy">{{ pick.strategyLabel }}</div>
              <div class="pick-name">{{ pick.stockName }}</div>
              <div class="pick-score">{{ pick.aiScore || pick.score }}점</div>
              <div class="pick-tags" v-if="pick.aiThemes">
                <span v-for="t in pick.aiThemes.split(',').slice(0, 2)" :key="t" class="pick-tag">{{ t.trim() }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- ⑤ 섹터 히트맵 (기존 유지) -->
        <SectionMarketMap
          :sectorData="sectorData"
          :marketData="marketData"
          :globalData="globalData"
          :loading="sections.marketMap.loading"
          :error="sections.marketMap.error"
          @retry="loadMarketMap"
        />
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
import GlobalNav from '../components/GlobalNav.vue'
import DashboardHeader from '../components/v2/DashboardHeader.vue'
import SectionAiStrategy from '../components/v2/SectionAiStrategy.vue'
import SectionMarketMap from '../components/v2/SectionMarketMap.vue'
import SectionSmartMoney from '../components/v2/SectionSmartMoney.vue'
import SectionResearch from '../components/v2/SectionResearch.vue'
import SectionWatchlist from '../components/v2/SectionWatchlist.vue'
import SectionBacktest from '../components/v2/SectionBacktest.vue'
import SectionEarnings from '../components/v2/SectionEarnings.vue'
import StockSearchModal from '../components/v2/StockSearchModal.vue'
import {
  aiStrategyAPI, sectorAPI, marketAPI, tradingIndicatorAPI,
  investorAPI, screenerAPI, newsAPI,
  aiStrategyV2API, marketV2API, investorV2API, screenerV2API, newsV2API,
  globalFuturesAPI, radarAPI, watchlistAPI, earningsAPI, paperTradingAPI
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
    GlobalNav,
    DashboardHeader,
    SectionAiStrategy,
    SectionMarketMap,
    SectionSmartMoney,
    SectionResearch,
    SectionWatchlist,
    SectionBacktest,
    SectionEarnings,
    StockSearchModal
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
      newsData: [],
      // 오늘의 핵심 요약
      watchlistItems: [],
      watchlistRisks: {},
      radarSignals: [],
      // 시간대별 신호
      phaseLoading: false,
      preMarketData: [],   // 장 전
      postMarketData: [],  // 장 후
      investorTop5: []     // 외국인/기관 TOP 5
    }
  },
  watch: {
    activeGnbTab(tab) {
      this.loadTabData(tab)
    }
  },
  mounted() {
    this.loadTabData('market')
    this.loadNews()
    this.loadTodaySummary() // 오늘의 핵심 요약
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
  computed: {
    currentPhaseKey() {
      const now = new Date()
      const day = now.getDay()
      const h = now.getHours()
      const m = now.getMinutes()
      const mins = h * 60 + m
      // 주말 → 장 후
      if (day === 0 || day === 6) return 'post'
      if (mins < 540) return 'pre'        // ~09:00
      if (mins < 930) return 'during'     // 09:00~15:30
      return 'post'                       // 15:30~
    },
    marketPhase() {
      const phases = {
        pre: { icon: '🌅', title: '오늘 장 준비', label: '장 전', class: 'phase-pre', empty: '장 전 데이터를 로딩 중입니다' },
        during: { icon: '📈', title: '실시간 신호', label: '장 진행 중', class: 'phase-during', empty: '오늘 감지된 신호가 없습니다' },
        post: { icon: '📊', title: '오늘 결산', label: '장 마감', class: 'phase-post', empty: '결산 데이터를 로딩 중입니다' }
      }
      return phases[this.currentPhaseKey]
    },
    phaseSignals() {
      const phase = this.currentPhaseKey
      if (phase === 'pre') return this.preMarketSignals
      if (phase === 'during') return this.duringMarketSignals
      return this.postMarketSignals
    },
    preMarketSignals() {
      const signals = []
      // 나스닥 선물 방향
      if (this.globalData?.nasdaqFutures) {
        const nq = this.globalData.nasdaqFutures
        signals.push({
          type: 'global', badge: '🌙 야간', stockName: '나스닥 선물',
          reason: `${Number(nq.currentPrice).toLocaleString()} (${Number(nq.changeRate) >= 0 ? '+' : ''}${Number(nq.changeRate).toFixed(2)}%)`,
          stockCode: null, changeRate: nq.changeRate
        })
      }
      // AI 전략 TOP 픽
      this.aiTopPicks.slice(0, 2).forEach(p => {
        signals.push({
          type: 'ai', badge: p.strategyLabel, stockCode: p.stockCode,
          stockName: p.stockName, reason: `AI ${p.aiScore || p.score}점`,
          changeRate: p.changeRate
        })
      })
      // 전일 외국인 TOP
      this.investorTop5.slice(0, 2).forEach(t => {
        signals.push({
          type: 'investor', badge: '🌍 외국인', stockCode: t.stockCode,
          stockName: t.stockName, reason: `순매수 ${t.netBuyAmount}억`,
          changeRate: t.changeRate
        })
      })
      return signals.slice(0, 6)
    },
    duringMarketSignals() {
      const signals = []
      if (this.surgeData?.length) {
        this.surgeData.filter(s => s.surgeLevel === 'HOT').slice(0, 3).forEach(s => {
          signals.push({
            type: 'hot', badge: '🔥 HOT', stockCode: s.stockCode,
            stockName: s.stockName, reason: '수급 급증', changeRate: s.changeRate
          })
        })
      }
      if (this.radarSignals?.length) {
        this.radarSignals.slice(0, 3).forEach(r => {
          signals.push({
            type: 'radar', badge: '📰 정책',
            stockName: r.title?.substring(0, 30), reason: (r.matchedSectors || []).join(' · '),
            stockCode: null, changeRate: null
          })
        })
      }
      return signals.slice(0, 6)
    },
    postMarketSignals() {
      const signals = []
      // 봇 성과
      this.postMarketData.forEach(d => signals.push(d))
      // 외국인 TOP
      this.investorTop5.slice(0, 3).forEach(t => {
        signals.push({
          type: 'investor', badge: '🌍 외국인', stockCode: t.stockCode,
          stockName: t.stockName, reason: `순매수 ${t.netBuyAmount}억`,
          changeRate: t.changeRate
        })
      })
      // AI 전략
      this.aiTopPicks.slice(0, 2).forEach(p => {
        signals.push({
          type: 'ai', badge: '🤖 내일 주목', stockCode: p.stockCode,
          stockName: p.stockName, reason: `AI ${p.aiScore || p.score}점`,
          changeRate: p.changeRate
        })
      })
      return signals.slice(0, 6)
    },
    aiTopPicks() {
      if (!this.aiStrategyData?.strategies) return []
      const picks = []
      const labels = { SCALPING: '⚡ 스캘핑', SWING: '📈 스윙', TURNAROUND: '🔄 턴어라운드', VALUE: '💎 밸류' }
      for (const [type, list] of Object.entries(this.aiStrategyData.strategies)) {
        if (Array.isArray(list) && list.length > 0) {
          picks.push({ ...list[0], strategyLabel: labels[type] || type })
        }
      }
      return picks.slice(0, 4)
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

    // ---- 오늘의 핵심 요약 로드 ----
    async loadTodaySummary() {
      // 관심종목
      try {
        const res = await watchlistAPI.getList()
        const list = this.extractData(res)
        this.watchlistItems = Array.isArray(list) ? list : []
        // 리스크 상태
        if (this.watchlistItems.length) {
          const codes = this.watchlistItems.map(w => w.stockCode)
          try {
            const riskRes = await watchlistAPI.getRiskStatus(codes)
            this.watchlistRisks = this.extractData(riskRes) || {}
          } catch { this.watchlistRisks = {} }
        }
      } catch { this.watchlistItems = [] }

      // 선점 레이더 신호
      try {
        const res = await radarAPI.getPolicyNews()
        this.radarSignals = (this.extractData(res) || []).slice(0, 5)
      } catch { this.radarSignals = [] }

      // 수급급증 데이터 (시장 뷰에서도 필요)
      if (!this.surgeData?.length) {
        try {
          let sd = null
          try {
            const res = await withTimeout(investorV2API.getAllSurgeStocks(), 10000)
            sd = this.extractData(res)
          } catch { /* V2 실패 */ }
          if (!sd) {
            const res = await investorAPI.getAllSurgeStocks()
            sd = this.extractData(res)
          }
          this.surgeData = this.flattenInvestorMap(sd)
        } catch { /* ignore */ }
      }

      // 시간대별 추가 데이터
      this.phaseLoading = true
      try {
        await this.loadPhaseData()
      } catch { /* ignore */ }
      this.phaseLoading = false
    },

    async loadPhaseData() {
      // 외국인 TOP 5 (장 전 + 장 후 공통)
      try {
        const res = await investorAPI.getTopTrades('FOREIGN', 'BUY', 5)
        this.investorTop5 = this.extractData(res) || []
      } catch { this.investorTop5 = [] }

      const phase = this.currentPhaseKey
      if (phase === 'post') {
        // 장 후: 봇 성과
        try {
          const res = await paperTradingAPI.getStatistics()
          const stats = this.extractData(res)
          if (stats && stats.totalTrades > 0) {
            this.postMarketData = [{
              type: 'bot', badge: '🤖 봇 성과',
              stockName: `${stats.winCount}승 ${stats.loseCount}패 (승률 ${stats.winRate || 0}%)`,
              reason: `손익비 ${stats.profitFactor || '-'}`,
              stockCode: null, changeRate: null
            }]
          }
        } catch { this.postMarketData = [] }
      }
    },

    goToStock(code) {
      if (code) this.$router.push(`/stock/${code}`)
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
    getChangeClass(rate) {
      if (rate == null) return ''
      return Number(rate) >= 0 ? 'positive' : 'negative'
    },
    formatChange(rate) {
      if (rate == null) return '-'
      const n = Number(rate)
      return (n >= 0 ? '+' : '') + n.toFixed(2)
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

/* ===== 시장 상태 바 ===== */
.market-status-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 16px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 10px;
  flex-wrap: wrap;
}
.market-status-bar.skeleton { justify-content: center; color: rgba(255,255,255,0.3); font-size: 13px; }
.msb-item { display: flex; align-items: center; gap: 6px; }
.msb-label { font-size: 12px; color: rgba(255,255,255,0.4); font-weight: 600; }
.msb-value { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.8); }
.msb-item.positive .msb-value { color: #ef4444; }
.msb-item.negative .msb-value { color: #3b82f6; }
.msb-divider { width: 1px; height: 16px; background: rgba(255,255,255,0.1); }

/* ===== 오늘의 신호 ===== */
.signal-list { display: flex; flex-direction: column; gap: 8px; }
.signal-card {
  display: flex; align-items: center; gap: 12px;
  padding: 12px; border-radius: 10px; cursor: pointer;
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06);
  transition: all 0.15s;
}
.signal-card:hover { background: rgba(255,255,255,0.06); }
.signal-card.hot { border-left: 3px solid #ef4444; }
.signal-card.radar { border-left: 3px solid #f59e0b; }
.sig-badge { font-size: 11px; font-weight: 700; white-space: nowrap; }
.sig-info { flex: 1; min-width: 0; }
.sig-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.9); display: block; }
.sig-reason { font-size: 12px; color: rgba(255,255,255,0.4); }
.sig-right { font-size: 13px; font-weight: 700; }
.empty-signal { text-align: center; padding: 24px; color: rgba(255,255,255,0.3); font-size: 13px; }
.phase-badge { font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 6px; }
.phase-pre { background: rgba(245,158,11,0.15); color: #f59e0b; }
.phase-during { background: rgba(34,197,94,0.15); color: #22c55e; }
.phase-post { background: rgba(102,126,234,0.15); color: #8b9cf7; }
.signal-card.global { border-left: 3px solid #8b5cf6; }
.signal-card.ai { border-left: 3px solid #3b82f6; }
.signal-card.investor { border-left: 3px solid #10b981; }
.signal-card.bot { border-left: 3px solid #6366f1; }
.signal-skeleton { display: flex; flex-direction: column; gap: 8px; padding: 8px 0; }
.skel-row { height: 48px; border-radius: 10px; background: rgba(255,255,255,0.04); }
.skel-bar { width: 60%; height: 12px; margin: 18px 16px; border-radius: 4px; background: rgba(255,255,255,0.08); animation: skeleton-pulse 1.5s infinite; }
@keyframes skeleton-pulse { 0%,100% { opacity: 0.5; } 50% { opacity: 0.2; } }

/* ===== 관심종목 요약 ===== */
.wl-list { display: flex; flex-direction: column; gap: 4px; }
.wl-row {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: 8px; cursor: pointer;
  transition: background 0.15s;
}
.wl-row:hover { background: rgba(255,255,255,0.04); }
.wl-risk { font-size: 12px; }
.wl-risk.danger { } .wl-risk.warning { }
.wl-name { flex: 1; font-size: 13px; font-weight: 600; color: rgba(255,255,255,0.85); }
.wl-price { font-size: 13px; color: rgba(255,255,255,0.6); font-family: monospace; }
.wl-change { font-size: 12px; font-weight: 700; width: 55px; text-align: right; }

/* ===== AI TOP 픽 ===== */
.top-picks-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.pick-card {
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06);
  border-radius: 10px; padding: 12px; cursor: pointer; text-align: center;
  transition: all 0.15s;
}
.pick-card:hover { background: rgba(255,255,255,0.06); border-color: rgba(255,255,255,0.12); }
.pick-strategy { font-size: 11px; color: rgba(255,255,255,0.4); margin-bottom: 4px; }
.pick-name { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.9); margin-bottom: 4px; }
.pick-score { font-size: 18px; font-weight: 800; color: #ef4444; margin-bottom: 4px; }
.pick-tags { display: flex; gap: 4px; justify-content: center; flex-wrap: wrap; }
.pick-tag { font-size: 10px; padding: 1px 6px; border-radius: 4px; background: rgba(102,126,234,0.12); color: #8b9cf7; }

.positive { color: #ef4444 !important; }
.negative { color: #3b82f6 !important; }

@media (max-width: 768px) {
  .market-status-bar { gap: 4px; padding: 8px 10px; }
  .msb-label { font-size: 10px; } .msb-value { font-size: 12px; }
  .top-picks-grid { grid-template-columns: repeat(2, 1fr); }
  .pick-score { font-size: 16px; }
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
