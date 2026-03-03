<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">🔬</span> AI 리서치</h2>
      <router-link to="/earnings-screener" class="more-link">더 보기 →</router-link>
    </div>

    <SkeletonLoader v-if="loading" type="card" />

    <div v-else-if="error" class="state-box">
      <span class="state-icon">⚠️</span>
      <p class="state-text">데이터를 불러오지 못했습니다</p>
      <button class="state-btn" @click="$emit('retry')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 11-9-9"/><polyline points="21 3 21 9 15 9"/></svg>
        새로고침
      </button>
    </div>

    <template v-else>
      <!-- 내부 탭 -->
      <div class="inner-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['tab-btn', { active: activeTab === tab.key }]"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- 실적 스크리너 -->
      <div v-if="activeTab === 'screener'" class="screener-area">
        <!-- 마법의 공식 TOP 3 -->
        <div class="screener-group" v-if="screenerData.magicFormula && screenerData.magicFormula.length > 0">
          <h4>마법의 공식</h4>
          <div
            v-for="(item, i) in screenerData.magicFormula.slice(0, 3)"
            :key="'mf-' + i"
            class="screener-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="s-rank">{{ i + 1 }}</span>
            <span class="s-name">
              {{ item.stockName }}
              <template v-if="scoreMap[item.stockCode]">
                <span class="supply-icon" :class="scoreMap[item.stockCode].foreignBuying ? 'buying' : 'selling'">{{ scoreMap[item.stockCode].foreignBuying ? '▲' : '▼' }}</span>
                <span class="supply-icon" :class="scoreMap[item.stockCode].instBuying ? 'buying' : 'selling'">{{ scoreMap[item.stockCode].instBuying ? '▲' : '▼' }}</span>
              </template>
            </span>
            <div class="s-metrics">
              <span v-if="item.per" class="badge-per">PER {{ Number(item.per).toFixed(1) }}</span>
              <span v-if="item.roe" class="badge-roe">ROE {{ Number(item.roe).toFixed(1) }}%</span>
              <template v-if="scoreMap[item.stockCode]">
                <span :class="scoreBadgeClass(scoreMap[item.stockCode].tradingScore)">단기 {{ scoreMap[item.stockCode].tradingScore }}</span>
                <span :class="scoreBadgeClass(scoreMap[item.stockCode].fundamentalScore)">중장기 {{ scoreMap[item.stockCode].fundamentalScore }}</span>
              </template>
              <span v-else-if="scoresLoading" class="badge-shimmer">...</span>
            </div>
          </div>
        </div>

        <!-- PEG TOP 3 -->
        <div class="screener-group" v-if="screenerData.lowPeg && screenerData.lowPeg.length > 0">
          <h4>저PEG 성장주</h4>
          <div
            v-for="(item, i) in screenerData.lowPeg.slice(0, 3)"
            :key="'peg-' + i"
            class="screener-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="s-rank">{{ i + 1 }}</span>
            <span class="s-name">
              {{ item.stockName }}
              <template v-if="scoreMap[item.stockCode]">
                <span class="supply-icon" :class="scoreMap[item.stockCode].foreignBuying ? 'buying' : 'selling'">{{ scoreMap[item.stockCode].foreignBuying ? '▲' : '▼' }}</span>
                <span class="supply-icon" :class="scoreMap[item.stockCode].instBuying ? 'buying' : 'selling'">{{ scoreMap[item.stockCode].instBuying ? '▲' : '▼' }}</span>
              </template>
            </span>
            <div class="s-metrics">
              <span v-if="item.peg" class="badge-peg">PEG {{ Number(item.peg).toFixed(2) }}</span>
              <span v-if="item.roe" class="badge-roe">ROE {{ Number(item.roe).toFixed(1) }}%</span>
              <template v-if="scoreMap[item.stockCode]">
                <span :class="scoreBadgeClass(scoreMap[item.stockCode].tradingScore)">단기 {{ scoreMap[item.stockCode].tradingScore }}</span>
                <span :class="scoreBadgeClass(scoreMap[item.stockCode].fundamentalScore)">중장기 {{ scoreMap[item.stockCode].fundamentalScore }}</span>
              </template>
              <span v-else-if="scoresLoading" class="badge-shimmer">...</span>
            </div>
          </div>
        </div>

        <!-- 턴어라운드 TOP 3 -->
        <div class="screener-group" v-if="screenerData.turnaround && screenerData.turnaround.length > 0">
          <h4>턴어라운드</h4>
          <div
            v-for="(item, i) in screenerData.turnaround.slice(0, 3)"
            :key="'ta-' + i"
            class="screener-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="s-rank">{{ i + 1 }}</span>
            <span class="s-name">
              {{ item.stockName }}
              <template v-if="scoreMap[item.stockCode]">
                <span class="supply-icon" :class="scoreMap[item.stockCode].foreignBuying ? 'buying' : 'selling'">{{ scoreMap[item.stockCode].foreignBuying ? '▲' : '▼' }}</span>
                <span class="supply-icon" :class="scoreMap[item.stockCode].instBuying ? 'buying' : 'selling'">{{ scoreMap[item.stockCode].instBuying ? '▲' : '▼' }}</span>
              </template>
            </span>
            <div class="s-metrics">
              <span class="turnaround-type">
                {{ item.turnaroundType === 'LOSS_TO_PROFIT' ? '흑자전환' : '이익급증' }}
              </span>
              <template v-if="scoreMap[item.stockCode]">
                <span :class="scoreBadgeClass(scoreMap[item.stockCode].tradingScore)">단기 {{ scoreMap[item.stockCode].tradingScore }}</span>
                <span :class="scoreBadgeClass(scoreMap[item.stockCode].fundamentalScore)">중장기 {{ scoreMap[item.stockCode].fundamentalScore }}</span>
              </template>
              <span v-else-if="scoresLoading" class="badge-shimmer">...</span>
            </div>
          </div>
        </div>

        <div
          v-if="!screenerData.magicFormula?.length && !screenerData.lowPeg?.length && !screenerData.turnaround?.length"
          class="empty-msg"
        >스크리너 데이터 없음</div>
      </div>

      <!-- AI 쌍끌이 -->
      <div v-if="activeTab === 'dual'" class="screener-area">
        <div v-if="scoresLoading" class="empty-msg">점수 로딩 중...</div>
        <template v-else-if="dualHighStocks.length > 0">
          <div class="screener-group">
            <h4>단기 + 중장기 80점 이상</h4>
            <div
              v-for="(item, i) in dualHighStocks"
              :key="'dual-' + i"
              class="screener-row"
              @click="goToStock(item.stockCode)"
            >
              <span class="s-rank">{{ i + 1 }}</span>
              <span class="s-name">
                {{ item.stockName }}
                <span class="supply-icon" :class="item.foreignBuying ? 'buying' : 'selling'">{{ item.foreignBuying ? '▲' : '▼' }}</span>
                <span class="supply-icon" :class="item.instBuying ? 'buying' : 'selling'">{{ item.instBuying ? '▲' : '▼' }}</span>
              </span>
              <div class="s-metrics">
                <span :class="scoreBadgeClass(item.tradingScore)">단기 {{ item.tradingScore }}</span>
                <span :class="scoreBadgeClass(item.fundamentalScore)">중장기 {{ item.fundamentalScore }}</span>
                <span class="badge-total">합산 {{ item.tradingScore + item.fundamentalScore }}</span>
              </div>
            </div>
          </div>
        </template>
        <div v-else class="empty-msg">조건을 충족하는 종목이 없습니다</div>
      </div>

      <!-- 경제 뉴스 -->
      <div v-if="activeTab === 'news'" class="news-area">
        <div
          v-for="(item, i) in enrichedNewsData.slice(0, 5)"
          :key="'news-' + i"
          :class="['news-item', { 'news-item--hot': item._isHot }]"
        >
          <div class="news-title-row">
            <span v-if="item._sector" class="mini-sector-tag">{{ item._sector }}</span>
            <span v-if="item._isHot" class="mini-hot-badge">HOT</span>
          </div>
          <div class="news-title">{{ item.title }}</div>
          <div class="news-summary" v-if="item.summary">{{ item.summary }}</div>
          <div class="news-meta">
            <span class="news-source" v-if="item.source || item.sourceName">{{ item.source || item.sourceName }}</span>
            <span class="news-time" v-if="item.publishedAt || item.summarizedAt">{{ formatNewsTime(item.publishedAt || item.summarizedAt) }}</span>
            <span class="news-sentiment" v-if="item.sentiment || item.sentimentLabel" :class="getSentimentClass(item.sentiment || item.sentimentLabel)">
              {{ item.sentimentLabel || item.sentiment }}
            </span>
          </div>
        </div>
        <div v-if="newsData.length === 0" class="empty-msg">뉴스를 불러오는 중...</div>
        <div class="more-links">
          <router-link to="/news">전체 뉴스 →</router-link>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import SkeletonLoader from './SkeletonLoader.vue'
import { stockDetailAPI } from '@/utils/api'

export default {
  name: 'SectionResearch',
  components: { SkeletonLoader },
  inject: { openStock: { default: null } },
  props: {
    screenerData: { type: Object, default: () => ({}) },
    newsData: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    error: { type: Boolean, default: false }
  },
  emits: ['retry'],
  data() {
    return {
      activeTab: 'screener',
      tabs: [
        { key: 'screener', label: '실적 스크리너' },
        { key: 'dual', label: 'AI 쌍끌이' },
        { key: 'news', label: '경제 뉴스' }
      ],
      scoreMap: {},
      scoresLoading: false
    }
  },
  computed: {
    enrichedNewsData() {
      const HOT_KW = ['수주','실적','계약','급등','급락','사상최고','역대급','호실적','인수','합병','M&A','IPO','상장','흑자전환','적자전환']
      const SECTORS = [
        { kw: ['반도체','HBM','파운드리','DRAM'], tag: '반도체' },
        { kw: ['2차전지','배터리','리튬','양극재'], tag: '2차전지' },
        { kw: ['AI','인공지능','GPT','LLM'], tag: 'AI' },
        { kw: ['나스닥','S&P','다우','뉴욕증시','미국증시'], tag: '미국증시' },
        { kw: ['코스피','코스닥','한국증시'], tag: '국내증시' },
        { kw: ['금리','기준금리','연준','Fed'], tag: '금리·통화' },
        { kw: ['환율','달러','원화'], tag: '환율' },
        { kw: ['유가','원유','OPEC'], tag: '원자재' },
        { kw: ['바이오','제약','신약'], tag: '바이오' },
        { kw: ['자동차','전기차','EV'], tag: '자동차' },
      ]
      return this.newsData.map(item => {
        const title = item.title || ''
        const _isHot = HOT_KW.some(k => title.includes(k))
        let _sector = null
        for (const s of SECTORS) {
          if (s.kw.some(k => title.includes(k))) { _sector = s.tag; break }
        }
        return { ...item, _isHot, _sector }
      })
    },
    allStockCodes() {
      const codes = new Set()
      const d = this.screenerData
      if (d.magicFormula) d.magicFormula.slice(0, 3).forEach(s => codes.add(s.stockCode))
      if (d.lowPeg) d.lowPeg.slice(0, 3).forEach(s => codes.add(s.stockCode))
      if (d.turnaround) d.turnaround.slice(0, 3).forEach(s => codes.add(s.stockCode))
      return [...codes]
    },
    allStockMap() {
      const map = {}
      const d = this.screenerData
      const lists = [d.magicFormula, d.lowPeg, d.turnaround]
      lists.forEach(list => {
        if (!list) return
        list.slice(0, 3).forEach(item => {
          if (!map[item.stockCode]) map[item.stockCode] = item
        })
      })
      return map
    },
    dualHighStocks() {
      const items = []
      for (const [code, scores] of Object.entries(this.scoreMap)) {
        if (scores.tradingScore >= 80 && scores.fundamentalScore >= 80) {
          const stock = this.allStockMap[code]
          items.push({
            stockCode: code,
            stockName: stock ? stock.stockName : code,
            tradingScore: scores.tradingScore,
            fundamentalScore: scores.fundamentalScore,
            foreignBuying: scores.foreignBuying,
            instBuying: scores.instBuying
          })
        }
      }
      items.sort((a, b) => (b.tradingScore + b.fundamentalScore) - (a.tradingScore + a.fundamentalScore))
      return items
    }
  },
  watch: {
    screenerData: {
      handler() {
        this.fetchBatchScores()
      },
      deep: true
    }
  },
  methods: {
    goToStock(code) {
      if (!code) return
      if (this.openStock) this.openStock(code)
      else this.$router.push(`/stock/${code}`)
    },
    async fetchBatchScores() {
      const codes = this.allStockCodes
      if (codes.length === 0) return
      this.scoresLoading = true
      try {
        const res = await stockDetailAPI.batchScores(codes)
        if (res.data && res.data.success) {
          this.scoreMap = res.data.data || {}
        }
      } catch (e) {
        console.warn('배치 점수 로딩 실패:', e)
      } finally {
        this.scoresLoading = false
      }
    },
    scoreBadgeClass(score) {
      if (score >= 80) return 'badge-score high'
      if (score <= 40) return 'badge-score low'
      return 'badge-score mid'
    },
    formatNewsTime(dateStr) {
      if (!dateStr) return ''
      try {
        const d = new Date(dateStr)
        return d.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' })
      } catch { return '' }
    },
    getSentimentClass(sentiment) {
      if (!sentiment) return ''
      const s = sentiment.toLowerCase()
      if (s.includes('positive') || s.includes('긍정')) return 'positive'
      if (s.includes('negative') || s.includes('부정')) return 'negative'
      return 'neutral'
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 24px;
  min-height: 380px;
}

.section-title-row {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px;
}
.section-title-row h2 { margin: 0; font-size: 16px; font-weight: 700; color: rgba(255,255,255,0.95); }
.section-icon { margin-right: 6px; }
.more-link { font-size: 13px; color: #667eea; text-decoration: none; }
.more-link:hover { color: #8b9cf7; }
.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 20px;
  text-align: center;
}
.state-icon { font-size: 36px; margin-bottom: 12px; opacity: 0.6; }
.state-text { font-size: 14px; color: rgba(255,255,255,0.4); margin: 0 0 16px 0; }
.state-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  background: rgba(102,126,234,0.12);
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 10px;
  color: #667eea;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.state-btn:hover {
  background: rgba(102,126,234,0.22);
  border-color: #667eea;
}

/* Tabs */
.inner-tabs {
  display: flex; gap: 4px; margin-bottom: 14px;
  background: rgba(255,255,255,0.04); padding: 3px; border-radius: 10px;
}
.tab-btn {
  flex: 1; padding: 6px 12px; border: none; background: transparent;
  color: rgba(255,255,255,0.5); font-size: 12px; font-weight: 500;
  cursor: pointer; border-radius: 8px; transition: all 0.2s;
}
.tab-btn:hover { color: rgba(255,255,255,0.7); }
.tab-btn.active { background: rgba(255,255,255,0.1); color: white; font-weight: 600; }

/* Screener */
.screener-group { margin-bottom: 14px; }
.screener-group h4 {
  margin: 0 0 6px 0; font-size: 12px; color: rgba(255,255,255,0.4);
  text-transform: uppercase; letter-spacing: 0.5px;
}

.screener-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; cursor: pointer; border-radius: 6px;
  transition: background 0.2s;
}
.screener-row:hover { background: rgba(255,255,255,0.04); }

.s-rank { font-size: 12px; color: rgba(255,255,255,0.35); width: 18px; text-align: center; }
.s-name { flex: 1; font-size: 13px; color: rgba(255,255,255,0.85); white-space: nowrap; }
.s-metrics { display: flex; gap: 4px; flex-wrap: wrap; }
.s-metrics span {
  font-size: 10px; padding: 2px 6px; border-radius: 4px; font-weight: 600;
  background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.5);
}
.badge-per { background: rgba(59,130,246,0.15) !important; color: #60a5fa !important; }
.badge-pbr { background: rgba(139,92,246,0.15) !important; color: #a78bfa !important; }
.badge-roe { background: rgba(239,68,68,0.15) !important; color: #f87171 !important; }
.badge-margin { background: rgba(245,158,11,0.15) !important; color: #fbbf24 !important; }
.badge-peg { background: rgba(16,185,129,0.15) !important; color: #34d399 !important; }
.badge-growth { background: rgba(239,68,68,0.12) !important; color: #ef4444 !important; }
.turnaround-type {
  background: rgba(16,185,129,0.15) !important;
  color: #10b981 !important;
}

/* Supply/Demand Icons */
.supply-icon {
  font-size: 10px; margin-left: 2px; font-weight: 700;
}
.supply-icon.buying { color: #ef4444; }
.supply-icon.selling { color: #3b82f6; }

/* Score Badges */
.badge-score {
  font-weight: 700 !important;
}
.badge-score.high {
  background: rgba(239,68,68,0.18) !important; color: #ef4444 !important;
}
.badge-score.low {
  background: rgba(59,130,246,0.18) !important; color: #60a5fa !important;
}
.badge-score.mid {
  background: rgba(255,255,255,0.08) !important; color: rgba(255,255,255,0.55) !important;
}
.badge-total {
  background: rgba(245,158,11,0.18) !important; color: #fbbf24 !important; font-weight: 700 !important;
}
.badge-shimmer {
  background: rgba(255,255,255,0.06) !important; color: rgba(255,255,255,0.2) !important;
  animation: shimmer 1.2s infinite;
}
@keyframes shimmer {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 0.8; }
}

/* News */
.news-item {
  padding: 10px 0;
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.news-item:last-child { border-bottom: none; }
.news-item--hot {
  border-left: 3px solid #ef4444;
  padding-left: 8px;
  margin-left: -8px;
}

.news-title-row {
  display: flex; gap: 4px; margin-bottom: 3px; flex-wrap: wrap;
}
.mini-sector-tag {
  font-size: 9px; padding: 1px 6px; border-radius: 3px;
  background: rgba(99,102,241,0.2); color: #a5b4fc; font-weight: 700;
}
.mini-hot-badge {
  font-size: 9px; padding: 1px 5px; border-radius: 3px;
  background: rgba(239,68,68,0.25); color: #fca5a5; font-weight: 800;
}

.news-title {
  font-size: 13px; color: rgba(255,255,255,0.85); font-weight: 500;
  margin-bottom: 4px; line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

.news-summary {
  font-size: 12px; color: rgba(255,255,255,0.4); line-height: 1.4; margin-bottom: 4px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

.news-meta { display: flex; gap: 8px; align-items: center; }
.news-source { font-size: 11px; color: rgba(255,255,255,0.3); }
.news-time { font-size: 11px; color: rgba(255,255,255,0.25); }
.news-sentiment {
  font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: 600;
}
.news-sentiment.positive { background: rgba(239,68,68,0.15); color: #ef4444; }
.news-sentiment.negative { background: rgba(59,130,246,0.15); color: #3b82f6; }
.news-sentiment.neutral { background: rgba(107,114,128,0.15); color: #9ca3af; }

.more-links { margin-top: 8px; }
.more-links a { font-size: 12px; color: rgba(255,255,255,0.4); text-decoration: none; }
.more-links a:hover { color: #667eea; }

.empty-msg { text-align: center; color: rgba(255,255,255,0.3); font-size: 13px; padding: 20px 0; }

@media (max-width: 768px) {
  .section-card { padding: 16px; border-radius: 14px; }
  .section-title-row h2 { font-size: 14px; }
  .stock-card { padding: 10px; gap: 8px; }
  .stock-name { font-size: 13px; }
}
</style>
