<template>
  <div class="v2-dashboard">
    <GlobalNav />
    <div class="v2-content">
      <!-- 헤더 (4탭: 개요/분석/뉴스/매매) -->
      <DashboardHeader
        :activeTab="activeGnbTab"
        @open-search="showSearch = true"
        @tab-change="activeGnbTab = $event"
      />

      <!-- ═══ Tab 1·2: 장전 + 장중 (공유 패널) ═══ -->
      <!-- ═══ Tab: 트레이드 (장전+장중 통합 — 시장 시간대로 위젯 자동 토글) ═══ -->
      <div v-if="activeGnbTab === 'premarket'" class="tab-panel">

        <!-- ⓪ 오늘의 브리핑 (장전 시간대 한눈 요약) -->
        <SectionBriefing
          v-if="currentPhaseKey === 'pre'"
          :marketData="marketData"
          :globalData="globalData"
          :topRecommendations="topRecommendations"
          :supplyPanelData="supplyPanelData"
          :watchlistItems="watchlistItems"
          :watchlistRisks="watchlistRisks"
        />

        <!-- ① 시장 상태 바 (장전·장중 공통) -->
        <div id="briefing-section-market" class="market-status-bar" v-if="marketData">
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
            <span class="msb-value">{{ marketData.adr != null ? marketData.adr : (marketData.combinedAdr || '-') }}</span>
          </div>
          <div class="msb-divider" v-if="globalData?.nasdaqFutures"></div>
          <div class="msb-item" v-if="globalData?.nasdaqFutures" :class="getChangeClass(globalData.nasdaqFutures.changeRate)">
            <span class="msb-label">나스닥</span>
            <span class="msb-value">{{ formatChange(globalData.nasdaqFutures.changeRate) }}%</span>
          </div>
        </div>
        <div v-else class="market-status-bar skeleton"><span>시장 데이터 로딩 중...</span></div>

        <!-- ② 종합 추천 TOP 10 (트레이드 탭 상시 표시 — 5카테고리: 실적·수급·기술·섹터·가치) -->
        <div id="briefing-section-rec" class="top-rec section-card">
          <div class="section-title-row">
            <h2><span class="section-icon">🏆</span> 종합 추천 TOP 10</h2>
            <span v-if="topRecDataTime" class="rec-data-time" :class="{ 'is-cached': !topRecRealtime }">
              {{ topRecRealtime ? '🟢' : '🟡' }} {{ topRecDataTime }}
            </span>
          </div>
          <div v-if="topRecLoading" class="signal-skeleton">
            <div class="skel-row" v-for="i in 3" :key="'rec-sk-'+i"><div class="skel-bar"></div></div>
          </div>
          <div v-else-if="topRecommendations.length" class="rec-list">
            <div
              v-for="(rec, i) in topRecommendations"
              :key="'rec-' + i"
              class="rec-card"
              @click="goToStock(rec.stockCode)"
            >
              <span class="rec-rank">
                #{{ i + 1 }}
                <span v-if="i < 5 && currentPhaseKey === 'during'" class="rec-live-dot" title="장중 실시간 추적"></span>
              </span>
              <div class="rec-info">
                <span class="rec-name">{{ rec.stockName }}</span>
                <div class="rec-tags">
                  <span v-for="(tag, ti) in (rec.tags || []).slice(0, 3)" :key="'t-' + i + '-' + ti" class="rec-tag">{{ tag }}</span>
                </div>
              </div>
              <div class="rec-score-area">
                <div class="rec-score-head">
                  <span class="rec-score-num">{{ rec.totalScore }}</span>
                  <span class="rec-score-basis">{{ rec.validCount }}/5항목</span>
                  <span v-if="topRecScoreMap[rec.stockCode]" class="rec-score-detail"
                        title="상세 페이지 단기 트레이딩 / 중장기 펀더멘털 점수">
                    단기 {{ topRecScoreMap[rec.stockCode].tradingScore }} · 중장기 {{ topRecScoreMap[rec.stockCode].fundamentalScore }}
                  </span>
                  <span v-if="topRecDelta[rec.stockCode] != null" class="rec-delta"
                        :class="topRecDelta[rec.stockCode] > 0 ? 'positive' : topRecDelta[rec.stockCode] < 0 ? 'negative' : ''">
                    {{ topRecDelta[rec.stockCode] > 0 ? '+' : '' }}{{ topRecDelta[rec.stockCode] }}
                  </span>
                  <span class="rec-grade" :class="getRecGradeClass(rec.totalScore, rec.validCount)">
                    {{ getRecGradeLabel(rec.totalScore, rec.validCount) }}
                  </span>
                </div>
                <!-- 세부 항목별 점수 바 -->
                <div class="rec-detail-bars">
                  <div v-for="item in getScoreBreakdown(rec)" :key="item.key" class="rec-detail-row">
                    <span class="rec-detail-label">{{ item.label }}</span>
                    <template v-if="item.isNA">
                      <div class="rec-detail-track na"><div class="rec-detail-na-line"></div></div>
                      <span class="rec-detail-score na-text">N/A</span>
                    </template>
                    <template v-else>
                      <div class="rec-detail-track">
                        <div class="rec-detail-fill" :style="{ width: (item.score / 20 * 100) + '%', background: item.color }"></div>
                      </div>
                      <span class="rec-detail-score" :style="{ color: item.score >= 14 ? item.color : 'rgba(255,255,255,0.4)' }">{{ item.score }}</span>
                    </template>
                  </div>
                </div>
                <div class="rec-price-area">
                  <span v-if="rec.currentPrice" class="rec-current-price">{{ Number(rec.currentPrice).toLocaleString('ko-KR') }}원</span>
                  <span v-if="rec.changeRate != null" class="rec-change"
                        :class="Number(rec.changeRate) >= 0 ? 'positive' : 'negative'">
                    {{ Number(rec.changeRate) >= 0 ? '+' : '' }}{{ Number(rec.changeRate).toFixed(2) }}%
                  </span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-signal">장 마감 후 또는 데이터 수집 중입니다<br><small style="opacity:0.7">장중에는 자동으로 갱신됩니다</small></div>
          <!-- ⑥ 등급 기준선 범례 -->
          <div class="rec-legend" v-if="topRecommendations.length">
            <span class="rec-legend-item"><span class="legend-dot grade-strong"></span>75↑ 강력매수</span>
            <span class="rec-legend-item"><span class="legend-dot grade-buy"></span>60~74 매수고려</span>
            <span class="rec-legend-item"><span class="legend-dot grade-hold"></span>40~59 관망</span>
            <span class="rec-legend-item"><span class="legend-dot grade-exclude"></span>40↓ 제외</span>
          </div>
        </div>

        <!-- ②-b 수급 현황 패널 (장전·장중 공통) -->
        <div id="briefing-section-supply" class="supply-panel section-card" v-if="supplyPanelData">
          <div class="section-title-row">
            <h2><span class="section-icon">💰</span> 외국인·기관 수급 현황</h2>
          </div>
          <!-- 당일 순매수 (코스피/코스닥) -->
          <div class="supply-summary">
            <div class="supply-item" v-for="inv in supplyPanelData.daily" :key="inv.type">
              <div class="supply-item-head">
                <span class="supply-label">{{ inv.label }}</span>
                <span class="supply-amount" :class="inv.amount >= 0 ? 'positive' : 'negative'">
                  {{ inv.amount >= 0 ? '+' : '' }}{{ inv.amount.toLocaleString() }}억
                </span>
              </div>
            </div>
          </div>
          <!-- 시장 분위기 인디케이터 -->
          <div v-if="supplyPanelData.rallySignal" class="rally-indicator" :class="supplyPanelData.rallySignal.type">
            <span class="rally-icon">{{ supplyPanelData.rallySignal.type === 'real' ? '🟢' : '🔴' }}</span>
            <span class="rally-text">{{ supplyPanelData.rallySignal.message }}</span>
          </div>
          <!-- 연속매수 종목 -->
          <div v-if="supplyPanelData.consecutive.length" class="supply-consecutive">
            <div class="supply-sub-title">연속 순매수 종목</div>
            <div v-for="item in supplyPanelData.consecutive.slice(0, 5)" :key="item.stockCode + item.investorType"
                 class="supply-stock-row" @click="goToStock(item.stockCode)">
              <span class="supply-investor-badge" :class="item.investorType === 'FOREIGN' ? 'foreign' : 'inst'">
                {{ item.investorType === 'FOREIGN' ? '외' : '기' }}
              </span>
              <span class="supply-stock-name">{{ item.stockName }}</span>
              <span class="supply-days">{{ item.consecutiveDays }}일</span>
              <span class="supply-signal" :class="item.isRealRally ? 'real' : 'fake'">
                {{ item.isRealRally ? '진짜반등' : '주의' }}
              </span>
            </div>
          </div>
          <div v-else class="empty-signal" style="padding:12px">수급 데이터 로딩 중...</div>
        </div>

        <!-- ②-f 섹터별 거래대금 (장중 시간대 — 5분/30분 파워, 5분 폴링) -->
        <div v-if="currentPhaseKey === 'during'" class="embedded-content">
          <SectorTradingPage :embedded="true" />
        </div>

        <!-- ③ 시간대별 신호 (장전·장후 전용 — 장중엔 LiveSurge가 상위호환) -->
        <div class="today-signals section-card" v-if="currentPhaseKey !== 'during'">
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

        <!-- ③-b 실시간 수급 급증 (장중 시간대) -->
        <SectionLiveSurge
          v-if="currentPhaseKey === 'during'"
          :active="currentPhaseKey === 'during'"
        />

        <!-- ③ 관심종목 현황 (장전 시간대 전용) -->
        <div id="briefing-section-watchlist" class="watchlist-summary section-card" v-if="currentPhaseKey === 'pre' && watchlistItems.length">
          <div class="section-title-row">
            <h2><span class="section-icon">⭐</span> 관심종목</h2>
            <a href="javascript:void(0)" class="more-link" @click="activeGnbTab = 'research'">전체 보기 →</a>
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

        <!-- AI 전략 TOP 픽 카드는 종합 추천 TOP10에 흡수되어 제거됨.
             aiTopPicks 데이터는 phaseSignals(장전·장후)의 신호 카드 일부로 계속 사용됨. -->

        <!-- ⑤ 섹터 히트맵 (트레이드 탭 상시) -->
        <SectionMarketMap
          :sectorData="sectorData"
          :marketData="marketData"
          :globalData="globalData"
          :loading="sections.marketMap.loading"
          :error="sections.marketMap.error"
          @retry="loadMarketMap"
        />

        <!-- ⑥ 페이퍼 트레이딩 (장중 시간대 · 관리자 전용) -->
        <div v-if="currentPhaseKey === 'during' && isAdmin" class="embedded-content">
          <PaperTradingPage :embedded="true" />
        </div>
      </div>

      <!-- ═══ Tab 2: 연구 (분석 + 뉴스) ═══ -->
      <div v-if="activeGnbTab === 'research'" class="tab-panel">
        <div class="sub-tabs">
          <button v-for="st in researchTabs" :key="st.key"
            :class="['sub-tab-btn', { active: activeAnalysisTab === st.key }]"
            @click="activeAnalysisTab = st.key">
            {{ st.label }}
          </button>
        </div>
        <div class="embedded-content">
          <AiStrategyDashboardPage v-if="activeAnalysisTab === 'ai-strategy'" :embedded="true" />
          <SectionBacktest v-if="activeAnalysisTab === 'backtest'" />
          <EarningsScreenerPage v-if="activeAnalysisTab === 'screener'" :embedded="true" />
          <SectionQuantTa v-if="activeAnalysisTab === 'quant-ta'" />
          <InvestorAnalysisPage v-if="activeAnalysisTab === 'investor'" :embedded="true" />
          <MarketTimingPage v-if="activeAnalysisTab === 'timing'" :embedded="true" />
          <NewsPage v-if="activeAnalysisTab === 'news'" :embedded="true" />
        </div>
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
import SectionMarketMap from '../components/v2/SectionMarketMap.vue'
import SectionQuantTa from '../components/v2/SectionQuantTa.vue'
import SectionBriefing from '../components/v2/SectionBriefing.vue'
import SectionLiveSurge from '../components/v2/SectionLiveSurge.vue'
import SectionBacktest from '../components/v2/SectionBacktest.vue'
import StockSearchModal from '../components/v2/StockSearchModal.vue'
// 분석 탭 (ResearchPage에서 흡수)
import AiStrategyDashboardPage from './AiStrategyDashboardPage.vue'
import EarningsScreenerPage from './EarningsScreenerPage.vue'
import SectorTradingPage from './SectorTradingPage.vue'
import InvestorAnalysisPage from './InvestorAnalysisPage.vue'
import MarketTimingPage from './MarketTimingPage.vue'
// 뉴스 탭
import NewsPage from './NewsPage.vue'
// 매매 탭 (PaperTradingPage 흡수)
import PaperTradingPage from './PaperTradingPage.vue'
import {
  aiStrategyAPI, sectorAPI, marketAPI, tradingIndicatorAPI,
  investorAPI, screenerAPI, newsAPI,
  // v2 API 제거 — 모두 v1으로 통합 (v2 서버 없으면 503 에러 방지)
  globalFuturesAPI, radarAPI, watchlistAPI, earningsAPI, paperTradingAPI,
  recommendationAPI, stockDetailAPI
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
    SectionMarketMap,
    SectionQuantTa,
    SectionBriefing,
    SectionLiveSurge,
    SectionBacktest,
    StockSearchModal,
    AiStrategyDashboardPage,
    EarningsScreenerPage,
    SectorTradingPage,
    InvestorAnalysisPage,
    MarketTimingPage,
    NewsPage,
    PaperTradingPage
  },
  provide() {
    return {
      openStock: (code) => this.$router.push(`/stock/${code}`)
    }
  },
  data() {
    return {
      // mount 시 1회만 읽음 — computed에서 매 렌더마다 localStorage 접근 회피
      isAdmin: localStorage.getItem('role') === 'ADMIN',
      activeGnbTab: this.resolveInitialTab(),
      activeAnalysisTab: this.resolveInitialSubTab(),
      researchTabs: [
        { key: 'ai-strategy', label: 'AI전략' },
        { key: 'backtest', label: '백테스트' },
        { key: 'screener', label: '스크리너' },
        { key: 'quant-ta', label: '퀀트(TA)' },
        { key: 'investor', label: '투자자' },
        { key: 'timing', label: '시장타이밍' },
        { key: 'news', label: '뉴스' }
      ],
      showSearch: false,
      dataLoaded: { market: false },
      sections: {
        marketMap: { loading: true, error: false },
        aiStrategy: { loading: false, error: false },
        smartMoney: { loading: false, error: false },
        research: { loading: false, error: false }
      },
      aiStrategyData: null,  // phaseSignals에서 사용
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
      // AI 종합 추천
      topRecommendations: [],
      topRecLoading: false,
      topRecDataTime: '',
      topRecRealtime: true,
      topRecDelta: {},
      topRecScoreMap: {},  // { stockCode: { tradingScore, fundamentalScore, ... } } — 트래커 단기/중장기 부가 표시용
      supplyPanelData: null,
      // 시간대별 신호
      phaseLoading: false,
      preMarketData: [],   // 장 전
      postMarketData: [],  // 장 후
      investorTop5: []     // 외국인/기관 TOP 5
    }
  },
  inject: { toast: { default: () => ({ success(){}, error(){}, warning(){}, info(){} }) } },
  watch: {
    activeGnbTab(tab) {
      // 글로벌 탭은 별도 페이지(/global-futures)로 라우팅 — 코드 스플리팅(207KB chunk) 보존
      if (tab === 'global') {
        this.$router.push('/global-futures')
        return
      }
      this.loadTabData(tab)
      // 탭 전환 시 스크롤 초기화
      window.scrollTo({ top: 0, behavior: 'smooth' })
    },
    '$route.query.tab'(tab) {
      if (!tab) return
      const mapped = this.mapLegacyTab(tab)
      if (mapped !== this.activeGnbTab) this.activeGnbTab = mapped
    }
  },
  mounted() {
    // 초기 로드 스태거 — 한꺼번에 같은 KIS 창구로 몰려 EGW00201 을 유발하던 것 완화.
    // 백엔드 KisApiRateLimiter 가 400ms 간격으로 직렬화하지만, 큐가 과부하일 땐
    // 뒤쪽 요청이 10초 타임아웃을 맞을 수 있어 프론트에서 미리 간격을 둔다.
    this.loadTabData(this.activeGnbTab)   // 즉시 (시장맵/AI전략/수급 — 내부적으로도 스태거됨)
    setTimeout(() => this.loadTodaySummary(), 400)  // 관심종목 + AI TOP5 등
    setTimeout(() => this.loadNews(), 1200)         // 뉴스는 비KIS 경로라 가장 늦어도 OK
    this.setupKeyboardShortcut()
    // 60초마다 트레이드 탭 데이터 자동 갱신 — 장중 시간대엔 추가로 실시간 신호도 갱신
    this._refreshTimer = setInterval(() => {
      if (this.activeGnbTab !== 'premarket') return
      this.loadMarketMap()
      this.refreshRecommendations()
      this.loadSupplyPanel()
      if (this.currentPhaseKey === 'during') {
        this.refreshLiveSignals()
      }
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
      if (mins < 480) return 'pre'        // ~08:00
      if (mins < 1200) return 'during'   // 08:00~20:00 (프리+정규+애프터)
      return 'post'                       // 20:00~
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
    // ---- 탭 키 호환 매핑 (장전+장중 통합 — 'live'/'trading'/'market'/'premarket' 모두 'premarket'(트레이드)로) ----
    mapLegacyTab(tab) {
      const map = {
        market: 'premarket', premarket: 'premarket', live: 'premarket', trading: 'premarket',
        analysis: 'research', news: 'research'
      }
      return map[tab] || tab
    },
    // ---- 초기 탭 자동 선택 ----
    resolveInitialTab() {
      const requested = this.$route?.query?.tab
      if (requested) return this.mapLegacyTab(requested)
      // ?tab= 없으면 시각 기반: 장 마감 이후엔 연구 탭, 그 외엔 트레이드 탭
      const h = new Date().getHours()
      return h < 16 ? 'premarket' : 'research'
    },
    resolveInitialSubTab() {
      const sub = this.$route?.query?.sub
      const requested = this.$route?.query?.tab
      // ?tab=news → research/news
      if (requested === 'news') return 'news'
      return sub || 'ai-strategy'
    },
    // ---- AI 추천 TOP 5 갱신 (장중 트래커에서 60초마다 호출) ----
    async refreshRecommendations() {
      try {
        const res = await recommendationAPI.getTop5()
        const body = res?.data || res
        this.topRecommendations = (body?.data) || []
        this.topRecDataTime = body?.dataTime || ''
        this.topRecRealtime = body?.realtime !== false
        this.topRecDelta = body?.delta || {}
        this.refreshTopRecScores()
      } catch { /* 갱신 실패 시 기존 값 유지 */ }
    },
    // 트래커 단기/중장기 점수 보강 — 추천 totalScore와 상세 페이지 단기/중장기 산식이 달라
    // 같은 종목인데 점수가 달라 보이는 인지 부조화를 해소하기 위해 같이 표시.
    // batchScores 는 무거운 호출이라 실패해도 트래커는 totalScore 만으로 정상 동작.
    async refreshTopRecScores() {
      const codes = (this.topRecommendations || [])
        .slice(0, 5)
        .map(r => r.stockCode)
        .filter(Boolean)
      if (!codes.length) { this.topRecScoreMap = {}; return }
      try {
        const res = await stockDetailAPI.batchScores(codes)
        const data = res?.data?.data || res?.data || {}
        if (data && typeof data === 'object') this.topRecScoreMap = data
      } catch { /* 부가 표시 실패는 무시 */ }
    },

    // ---- 장중 시간대별 신호 갱신 (수급 급증 HOT + 정책 레이더) ----
    async refreshLiveSignals() {
      try {
        const res = await investorAPI.getAllSurgeStocks()
        const sd = this.extractData(res)
        this.surgeData = this.flattenInvestorMap(sd)
      } catch { /* keep existing */ }
      try {
        const res = await radarAPI.getPolicyNews()
        const list = this.extractData(res)
        if (Array.isArray(list)) this.radarSignals = list.slice(0, 5)
      } catch { /* keep existing */ }
    },

    // ---- 탭별 데이터 로딩 ----
    loadTabData(tab) {
      // 트레이드 탭은 시장 데이터(시장상태바·수급·시간대별 신호) 공유
      if (tab === 'premarket' && !this.dataLoaded.market) {
        // 스태거 발사 — 같은 KIS 창구로 몰리지 않게 500ms 간격.
        // 시장맵(섹터 시세 Batch)이 가장 무거우므로 가장 먼저, 나머지는 뒤로.
        this.loadMarketMap()
        setTimeout(() => this.loadAiStrategy(), 500)   // 시간대별 신호용
        setTimeout(() => this.loadSmartMoney(), 1000)  // 수급급증 신호용
        this.dataLoaded.market = true
      }
      // 장중 시간대 매수 후보 트래커 — 즉시 1회 호출 (60초 폴링 첫 발화까지 빈 화면 방지)
      if (tab === 'premarket' && this.currentPhaseKey === 'during') {
        this.refreshRecommendations()
      }
    },

    // ---- 뉴스 로딩 (공용) ----
    async loadNews() {
      try {
        let nd = null
        try {
          const res = await withTimeout(newsAPI.getTodayNews(), 10000)
          nd = this.extractData(res)
        } catch { /* 실패 */ }
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

      // AI 종합 추천 TOP 5
      this.topRecLoading = true
      await this.refreshRecommendations()
      this.topRecLoading = false

      // 수급 현황 패널
      this.loadSupplyPanel()

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
            const res = await withTimeout(investorAPI.getAllSurgeStocks(), 10000)
            sd = this.extractData(res)
          } catch { /* 실패 */ }
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
    async loadSupplyPanel() {
      // 패널 즉시 노출 (v-if 통과) — 데이터는 비동기 도착 후 갱신.
      // 기존 직렬 await 3회 누적 지연 동안 v-if 가 false 라 메뉴 자체가 늦게 떴음.
      if (!this.supplyPanelData) {
        this.supplyPanelData = { daily: [], consecutive: [], rallySignal: null }
      }
      try {
        // 3개 호출 병렬화 — 직렬 누적 시간 제거 (consecutive-buy 가 캐시 콜드면 특히 느렸음)
        const [consRes, fRes, iRes] = await Promise.allSettled([
          investorAPI.getAllConsecutiveBuy(2),
          investorAPI.getTopTradesRealtime('FOREIGN', 10)
            .catch(() => investorAPI.getTopTrades('FOREIGN', 'BUY', 10)),
          investorAPI.getTopTradesRealtime('INSTITUTION', 10)
            .catch(() => investorAPI.getTopTrades('INSTITUTION', 'BUY', 10))
        ])

        // consecutive 응답은 Map<String, List<...>> 형태일 수 있어 flatten 필요.
        // 기존엔 raw 를 array 로 가정하고 .map() 호출 → 객체일 때 빈 catch 진입해 "로딩 중..." 표시.
        let consecutive = []
        if (consRes.status === 'fulfilled') {
          const cd = this.extractData(consRes.value)
          consecutive = this.flattenInvestorMap(cd)
        }

        // 당일 순매수 금액 — realtime(Redis L2 + KIS 워머) 우선, 미스 시 DB 폴백.
        // KisInvestorDataCollector 일배치는 16시 cron 이라 장중 DB 는 비거나 어제자.
        let foreignNet = 0, instNet = 0
        if (fRes.status === 'fulfilled') {
          const fData = this.extractData(fRes.value)
          if (Array.isArray(fData)) {
            foreignNet = fData.reduce((sum, t) => sum + (Number(t.netBuyAmount) || 0), 0)
          }
        }
        if (iRes.status === 'fulfilled') {
          const iData = this.extractData(iRes.value)
          if (Array.isArray(iData)) {
            instNet = iData.reduce((sum, t) => sum + (Number(t.netBuyAmount) || 0), 0)
          }
        }

        // 진짜 반등 판단: 외국인 3일+ 연속 + 양의 등락률 + 순매수 양
        const foreignConsecutive = consecutive.filter(c => c.investorType === 'FOREIGN')
        const has3DayForeignBuy = foreignConsecutive.some(c => (c.consecutiveDays || 0) >= 3)
        const foreignBuying = foreignNet > 0

        let rallySignal = null
        if (has3DayForeignBuy && foreignBuying) {
          rallySignal = { type: 'real', message: '외국인 3일+ 연속 순매수 — 진짜 반등 가능성' }
        } else if (!foreignBuying && instNet <= 0) {
          rallySignal = { type: 'fake', message: '외국인·기관 모두 순매도 — 주의' }
        } else if (!foreignBuying) {
          rallySignal = { type: 'fake', message: '외국인 순매도 + 기관만 매수 — 주의' }
        }

        const enriched = consecutive.map(item => ({
          ...item,
          isRealRally: (item.consecutiveDays || 0) >= 3 && (Number(item.changeRate) || 0) > 0
        }))

        this.supplyPanelData = {
          daily: [
            { type: 'foreign', label: '외국인', amount: Math.round(foreignNet) },
            { type: 'inst', label: '기관', amount: Math.round(instNet) }
          ],
          consecutive: enriched,
          rallySignal
        }
      } catch (e) {
        this.supplyPanelData = { daily: [], consecutive: [], rallySignal: null }
      }
    },
    getScoreBreakdown(rec) {
      // 5카테고리 (AI전략은 totalScore 산식에서 제외 — 후보 발굴/태그 용도로만 사용)
      const items = [
        { key: 'earn', label: '실적', raw: rec.earnings, color: '#22c55e' },
        { key: 'supply', label: '수급', raw: rec.supplyDemand, color: '#3b82f6' },
        { key: 'tech', label: '기술적', raw: rec.technical, color: '#f59e0b' },
        { key: 'sector', label: '섹터', raw: rec.sectorMomentum, color: '#ef4444' },
        { key: 'value', label: '가치', raw: rec.valueStability, color: '#06b6d4' },
      ]
      return items.map(item => ({
        ...item,
        score: item.raw != null && item.raw >= 0 ? item.raw : -1,
        isNA: item.raw == null || item.raw < 0
      }))
    },
    getRecGradeClass(score, validCount) {
      if (validCount != null && validCount < 3) return 'grade-low-confidence'
      if (score >= 75) return 'grade-strong'
      if (score >= 60) return 'grade-buy'
      if (score >= 40) return 'grade-hold'
      return 'grade-exclude'
    },
    getRecGradeLabel(score, validCount) {
      if (validCount != null && validCount < 3) return '⚠ 데이터부족'
      if (score >= 75) return '🔴 강력매수'
      if (score >= 60) return '🟡 매수고려'
      if (score >= 40) return '⚪ 관망'
      return '🔵 제외'
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
          withTimeout(sectorAPI.getSectorTrading('TODAY'), 15000).catch(() => null),
          withTimeout(marketAPI.getStatus(), 10000),
          withTimeout(tradingIndicatorAPI.getLeadingSectors(), 10000),
          withTimeout(tradingIndicatorAPI.getNasdaqFutures(), 10000),
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
          const res = await withTimeout(aiStrategyAPI.getLatest(), 15000)
          const d = this.extractData(res)
          const hasStocks = d?.strategies && Object.values(d.strategies).some(arr => arr && arr.length > 0)
          if (hasStocks) { this.aiStrategyData = d; return }
        } catch (e) { /* API 실패 */ }
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
          withTimeout(investorAPI.getTopTradesRealtime('FOREIGN', 10)
            .catch(() => investorAPI.getTopTrades('FOREIGN', 'BUY', 10)), 10000),
          withTimeout(investorAPI.getTopTradesRealtime('INSTITUTION', 10)
            .catch(() => investorAPI.getTopTrades('INSTITUTION', 'BUY', 10)), 10000),
          withTimeout(investorAPI.getAllConsecutiveBuy(3), 10000),
          withTimeout(investorAPI.getAllSurgeStocks(), 10000)
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
          screenerAPI.getSummary(), 15000
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

/* ===== 분석 서브탭 ===== */
.sub-tabs {
  display: flex;
  gap: 4px;
  background: rgba(255,255,255,0.04);
  padding: 4px;
  border-radius: 12px;
  margin-bottom: 20px;
  overflow-x: auto;
}
.sub-tab-btn {
  padding: 8px 16px;
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
.sub-tab-btn:hover {
  color: rgba(255,255,255,0.75);
  background: rgba(255,255,255,0.04);
}
.sub-tab-btn.active {
  background: rgba(102,126,234,0.18);
  color: #a5b4fc;
  font-weight: 600;
}
.embedded-content {
  min-height: 400px;
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
.market-status-bar.skeleton { justify-content: center; color: rgba(255,255,255,0.5); font-size: 13px; }
.msb-item { display: flex; align-items: center; gap: 6px; }
.msb-label { font-size: 12px; color: rgba(255,255,255,0.6); font-weight: 600; }
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
.sig-reason { font-size: 12px; color: rgba(255,255,255,0.6); }
.sig-right { font-size: 13px; font-weight: 700; }
.empty-signal { text-align: center; padding: 32px 16px; color: rgba(255,255,255,0.5); font-size: 13px; }
.empty-signal::before { content: '📭'; display: block; font-size: 28px; margin-bottom: 8px; opacity: 0.6; }

/* ===== 섹터 기회 발굴 (수급 주도 섹터 × 유망 종목) ===== */
.so-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}
.so-sector-card {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 12px;
}
.so-sector-head {
  display: flex; justify-content: space-between; align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
  margin-bottom: 8px;
}
.so-sector-name { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.9); }
.so-sector-rate { font-size: 13px; font-weight: 700; }
.so-picks { display: flex; flex-direction: column; gap: 6px; }
.so-pick-row {
  display: flex; gap: 10px; align-items: flex-start;
  padding: 8px; border-radius: 8px;
  background: rgba(255,255,255,0.02);
  cursor: pointer;
  transition: background 0.15s;
}
.so-pick-row:hover { background: rgba(255,255,255,0.06); }
.so-pick-rank {
  font-size: 11px; font-weight: 700; color: rgba(255,255,255,0.4);
  padding-top: 2px; min-width: 20px;
}
.so-pick-main { flex: 1; min-width: 0; }
.so-pick-name-row {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 2px;
}
.so-pick-name { font-size: 13px; font-weight: 600; color: rgba(255,255,255,0.9); }
.so-pick-score {
  font-size: 11px; font-weight: 700;
  color: #4ade80;
  background: rgba(74,222,128,0.12);
  padding: 1px 6px; border-radius: 8px;
}
.so-pick-meta {
  display: flex; gap: 8px; align-items: baseline;
  font-size: 11px; color: rgba(255,255,255,0.5);
  margin-bottom: 4px;
}
.so-pick-price { color: rgba(255,255,255,0.65); }
.so-pick-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.so-tag {
  font-size: 10px;
  padding: 1px 6px; border-radius: 6px;
  background: rgba(255,255,255,0.06);
  color: rgba(255,255,255,0.6);
}

/* ===== AI 종합 추천 ===== */
.rec-data-time {
  font-size: 11px; font-weight: 600; color: rgba(255,255,255,0.5);
  padding: 2px 8px; border-radius: 4px;
  background: rgba(34,197,94,0.1);
}
.rec-data-time.is-cached {
  background: rgba(245,158,11,0.1); color: #f59e0b;
}
.rec-list { display: flex; flex-direction: column; gap: 6px; }
.rec-card {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; border-radius: 10px; cursor: pointer;
  background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06);
  transition: all 0.15s;
}
.rec-card:hover { background: rgba(255,255,255,0.06); border-color: rgba(255,255,255,0.12); }
.rec-rank {
  font-size: 14px; font-weight: 800; color: #f59e0b;
  min-width: 28px; text-align: center;
  position: relative;
}
.rec-card:first-child .rec-rank { color: #ef4444; }
/* 장중 실시간 추적 인디케이터 — 트래커 위젯 흡수 */
.rec-live-dot {
  display: inline-block;
  width: 6px; height: 6px; border-radius: 50%;
  background: #4ade80;
  margin-left: 3px;
  vertical-align: middle;
  animation: rec-pulse 2s infinite;
}
@keyframes rec-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.85); }
}
/* 단기/중장기 부가 점수 — 상세 페이지와 매칭 */
.rec-score-detail {
  font-size: 10px; color: rgba(255,255,255,0.5);
  font-weight: 500; margin-left: 4px; white-space: nowrap;
}
.rec-info { flex: 1; min-width: 0; }
.rec-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.9); display: block; }
.rec-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 3px; }
.rec-tag {
  font-size: 10px; padding: 1px 6px; border-radius: 4px;
  background: rgba(102,126,234,0.12); color: #8b9cf7; font-weight: 600;
}
.rec-score-area { min-width: 160px; text-align: right; }
.rec-score-head { display: flex; align-items: baseline; justify-content: flex-end; gap: 3px; margin-bottom: 4px; flex-wrap: wrap; }
.rec-delta { font-size: 11px; font-weight: 700; padding: 0 3px; border-radius: 3px; }
.rec-delta.positive { color: #ef4444; background: rgba(239,68,68,0.1); }
.rec-delta.negative { color: #3b82f6; background: rgba(59,130,246,0.1); }
/* 등급 기준선 범례 */
.rec-legend { display: flex; justify-content: center; gap: 12px; padding: 8px 0 4px; border-top: 1px solid rgba(255,255,255,0.06); margin-top: 8px; }
.rec-legend-item { font-size: 10px; color: rgba(255,255,255,0.55); display: flex; align-items: center; gap: 4px; }
.legend-dot { width: 8px; height: 8px; border-radius: 2px; }
.legend-dot.grade-strong { background: #ef4444; }
.legend-dot.grade-buy { background: #f59e0b; }
.legend-dot.grade-hold { background: rgba(255,255,255,0.3); }
.legend-dot.grade-exclude { background: rgba(59,130,246,0.3); }
.rec-score-num { font-size: 20px; font-weight: 800; color: rgba(255,255,255,0.9); }
.rec-score-basis { font-size: 10px; color: rgba(255,255,255,0.55); margin-left: 2px; }
.rec-grade { font-size: 10px; font-weight: 700; margin-left: 4px; }
.rec-grade.grade-strong { color: #ef4444; }
.rec-grade.grade-buy { color: #f59e0b; }
.rec-grade.grade-hold { color: rgba(255,255,255,0.5); }
.rec-grade.grade-exclude { color: #3b82f6; }
.rec-grade.grade-low-confidence { color: #f59e0b; font-size: 9px; }
.rec-bar-track { height: 4px; background: rgba(255,255,255,0.06); border-radius: 2px; margin-bottom: 4px; }
.rec-bar-fill { height: 100%; border-radius: 2px; transition: width 0.4s ease; }
.rec-bar-fill.grade-strong { background: linear-gradient(90deg, #ef4444, #f87171); }
.rec-bar-fill.grade-buy { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
.rec-bar-fill.grade-hold { background: rgba(255,255,255,0.2); }
.rec-bar-fill.grade-exclude { background: rgba(59,130,246,0.3); }
/* 세부 항목별 바 */
.rec-detail-bars { display: flex; flex-direction: column; gap: 2px; margin-bottom: 4px; }
.rec-detail-row { display: flex; align-items: center; gap: 4px; }
.rec-detail-label { font-size: 9px; color: rgba(255,255,255,0.55); width: 32px; text-align: right; flex-shrink: 0; }
.rec-detail-track { flex: 1; height: 3px; background: rgba(255,255,255,0.06); border-radius: 2px; min-width: 40px; }
.rec-detail-fill { height: 100%; border-radius: 2px; transition: width 0.4s ease; }
.rec-detail-score { font-size: 9px; font-weight: 700; width: 20px; text-align: right; flex-shrink: 0; }
.rec-detail-track.na { opacity: 0.3; }
.rec-detail-na-line { height: 1px; margin-top: 1px; background: repeating-linear-gradient(90deg, rgba(255,255,255,0.2) 0, rgba(255,255,255,0.2) 3px, transparent 3px, transparent 6px); }
.na-text { color: rgba(255,255,255,0.45); font-size: 9px; }
.rec-price-area { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.rec-current-price { font-size: 12px; color: rgba(255,255,255,0.7); font-weight: 500; }
.rec-change { font-size: 12px; font-weight: 600; display: block; text-align: right; }

/* ===== 수급 현황 패널 ===== */
.supply-summary { display: flex; gap: 12px; margin-bottom: 10px; }
.supply-item { flex: 1; display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-radius: 8px; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06); }
.supply-label { font-size: 13px; color: rgba(255,255,255,0.5); font-weight: 600; }
.supply-amount { font-size: 16px; font-weight: 800; }
.supply-amount.positive { color: #ef4444; }
.supply-amount.negative { color: #3b82f6; }
.supply-sub-title { font-size: 11px; color: rgba(255,255,255,0.6); margin-bottom: 6px; font-weight: 600; }
.supply-consecutive { margin-top: 4px; }
.supply-stock-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 8px; cursor: pointer; transition: background 0.15s; }
.supply-stock-row:hover { background: rgba(255,255,255,0.04); }
.supply-investor-badge { font-size: 10px; font-weight: 800; width: 20px; height: 20px; border-radius: 4px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.supply-investor-badge.foreign { background: rgba(239,68,68,0.15); color: #ef4444; }
.supply-investor-badge.inst { background: rgba(59,130,246,0.15); color: #3b82f6; }
.supply-stock-name { flex: 1; font-size: 13px; font-weight: 600; color: rgba(255,255,255,0.85); }
.supply-days { font-size: 11px; color: rgba(255,255,255,0.6); font-weight: 600; }
.supply-signal { font-size: 10px; font-weight: 700; padding: 2px 6px; border-radius: 4px; }
.supply-signal.real { background: rgba(34,197,94,0.15); color: #22c55e; }
.supply-signal.fake { background: rgba(245,158,11,0.15); color: #f59e0b; }
.supply-item-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
/* 시장 반등 인디케이터 */
.rally-indicator { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 8px; margin-bottom: 8px; font-size: 12px; font-weight: 600; }
.rally-indicator.real { background: rgba(34,197,94,0.08); border: 1px solid rgba(34,197,94,0.2); color: #22c55e; }
.rally-indicator.fake { background: rgba(239,68,68,0.08); border: 1px solid rgba(239,68,68,0.2); color: #ef4444; }
.rally-icon { font-size: 14px; }
.rally-text { flex: 1; }
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
.pick-strategy { font-size: 11px; color: rgba(255,255,255,0.6); margin-bottom: 4px; }
.pick-name { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.9); margin-bottom: 4px; }
.pick-score { font-size: 18px; font-weight: 800; color: #ef4444; margin-bottom: 4px; }
.pick-tags { display: flex; gap: 4px; justify-content: center; flex-wrap: wrap; }
.pick-tag { font-size: 10px; padding: 1px 6px; border-radius: 4px; background: rgba(102,126,234,0.12); color: #8b9cf7; }

.positive { color: #ef4444 !important; }
.positive::before { content: '▲ '; font-size: 0.75em; }
.negative { color: #3b82f6 !important; }
.negative::before { content: '▼ '; font-size: 0.75em; }

@media (max-width: 768px) {
  .market-status-bar { gap: 4px; padding: 8px 10px; }
  .msb-label { font-size: 10px; } .msb-value { font-size: 12px; }
  .top-picks-grid { grid-template-columns: repeat(2, 1fr); }
  .pick-score { font-size: 16px; }
}

/* ───── 모바일 대응: 480~600px ───── */
@media (max-width: 600px) {
  /* AI 추천 TOP5 — 점수 영역 공간 압축 */
  .rec-card { padding: 8px 10px; gap: 8px; }
  .rec-rank { min-width: 22px; font-size: 13px; }
  .rec-name { font-size: 13px; }
  .rec-score-area { min-width: 110px; }
  .rec-score-num { font-size: 17px; }
  .rec-score-basis { display: none; }       /* 5/5항목 라벨 모바일 숨김 */
  .rec-detail-label { font-size: 8.5px; width: 26px; }
  .rec-detail-score { font-size: 8.5px; width: 16px; }
  .rec-detail-track { min-width: 24px; }
  .rec-tags { display: none; }              /* 태그 숨김 — 점수에 집중 */
  .rec-current-price { font-size: 11px; }
  .rec-change { font-size: 11px; }
  .rec-grade { font-size: 9px; }
  .rec-legend { gap: 8px; }
  .rec-legend-item { font-size: 9px; }

  /* 수급 패널 */
  .supply-summary { gap: 8px; }
  .supply-item { padding: 8px 10px; }
  .supply-amount { font-size: 14px; }
  .supply-stock-row { padding: 6px 8px; gap: 6px; }
  .supply-stock-name { font-size: 12px; }

  /* 시간대별 신호 */
  .signal-card { padding: 10px; gap: 8px; }
  .sig-name { font-size: 13px; }
  .sig-reason { font-size: 11px; }

  /* 섹터 기회 — 모바일은 1열 */
  .so-grid { grid-template-columns: 1fr; }

  /* 관심종목 */
  .wl-row { padding: 7px 8px; gap: 6px; }
  .wl-name { font-size: 12px; }
  .wl-price { font-size: 12px; }
  .wl-change { font-size: 11px; width: 50px; }

  /* 분석 sub-tabs — 화면 가득 채우지 말고 가로 스크롤 */
  .sub-tab-btn { padding: 7px 12px; font-size: 12px; }
}

/* ───── 매우 작은 화면(아이폰 mini 등): 380px 이하 ───── */
@media (max-width: 380px) {
  .rec-detail-bars { gap: 1px; }
  .rec-score-area { min-width: 95px; }
  .rec-grade { display: none; }             /* 강력매수/매수고려 라벨 숨김 — 색깔로 구분 */
}

/* Tab Panel */
.tab-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* News Panel in Market Tab */
.news-panel {
  background: var(--border-light);
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
.more-link { font-size: 13px; color: var(--primary-start); text-decoration: none; }
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
}
</style>

<!-- 브리핑→섹션 스크롤 도착 강조 (scoped 외부) -->
<style>
.briefing-scroll-flash {
  animation: briefing-flash 1.4s ease-out;
}
@keyframes briefing-flash {
  0%   { box-shadow: 0 0 0 0 rgba(124, 58, 237, 0.55); }
  20%  { box-shadow: 0 0 0 6px rgba(124, 58, 237, 0.35); }
  100% { box-shadow: 0 0 0 0 rgba(124, 58, 237, 0); }
}
</style>
