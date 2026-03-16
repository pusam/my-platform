<template>
  <div class="radar-section">
    <!-- 서브 탭 -->
    <div class="radar-tabs">
      <button v-for="tab in tabs" :key="tab.key"
              :class="['radar-tab', { active: activeTab === tab.key }]"
              @click="activeTab = tab.key">
        {{ tab.icon }} {{ tab.label }}
      </button>
    </div>

    <div v-if="loading" class="radar-loading">데이터 조회 중...</div>

    <!-- ① 정책/테마 뉴스 -->
    <div v-if="activeTab === 'policy'" class="radar-content">
      <div v-if="policyNews.length" class="radar-list">
        <div v-for="(news, i) in policyNews" :key="i" class="radar-card policy-card">
          <div class="card-header">
            <span class="sentiment-badge" :class="news.sentiment?.toLowerCase()">
              {{ getSentimentLabel(news.sentiment) }}
            </span>
            <span class="card-time">{{ formatTime(news.publishedAt) }}</span>
          </div>
          <div class="card-title">{{ news.title }}</div>
          <div class="card-summary" v-if="news.summary">{{ news.summary }}</div>
          <div class="card-tags">
            <span v-for="s in news.matchedSectors" :key="s" class="tag sector-tag">{{ s }}</span>
            <span v-for="k in news.matchedKeywords" :key="k" class="tag keyword-tag">{{ k }}</span>
          </div>
        </div>
      </div>
      <div v-else class="radar-empty">최근 24시간 정책/테마 뉴스가 없습니다.</div>
    </div>

    <!-- ② 신고가 돌파 직전 -->
    <div v-if="activeTab === 'nearHigh'" class="radar-content">
      <div v-if="nearHighStocks.length" class="radar-list">
        <div v-for="stock in nearHighStocks" :key="stock.stockCode"
             class="radar-card near-high-card" @click="goToStock(stock.stockCode)">
          <div class="card-header">
            <div class="stock-info">
              <span class="stock-name">{{ stock.stockName }}</span>
              <span class="stock-code">{{ stock.stockCode }}</span>
            </div>
            <span class="gap-badge">고점 -{{ stock.gapPercent }}%</span>
          </div>
          <div class="card-prices">
            <div class="price-item">
              <span class="price-label">현재가</span>
              <span class="price-value">{{ formatPrice(stock.currentPrice) }}</span>
            </div>
            <div class="price-item">
              <span class="price-label">당일 고가</span>
              <span class="price-value high">{{ formatPrice(stock.highPrice) }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="radar-empty">당일 고가 -3% 이내 종목이 없습니다.</div>
    </div>

    <!-- ③ 대량 취득 공시 -->
    <div v-if="activeTab === 'holdings'" class="radar-content">
      <div v-if="largeHoldings.length" class="radar-list">
        <div v-for="(h, i) in largeHoldings" :key="i" class="radar-card holding-card"
             @click="h.stockCode && goToStock(h.stockCode)">
          <div class="card-header">
            <span class="corp-name">{{ h.corpName }}</span>
            <span class="report-date">{{ h.reportDate }}</span>
          </div>
          <div class="card-report">{{ h.reportName }}</div>
          <div class="card-submitter" v-if="h.submitter">제출: {{ h.submitter }}</div>
        </div>
      </div>
      <div v-else class="radar-empty">최근 7일 대량보유(5%+) 공시가 없습니다.</div>
    </div>

    <!-- ④ 어닝 서프라이즈 예측 -->
    <div v-if="activeTab === 'earnings'" class="radar-content">
      <div v-if="earningsPredictions.length" class="radar-list">
        <div v-for="ep in earningsPredictions" :key="ep.stockCode"
             class="radar-card earnings-card" @click="goToStock(ep.stockCode)">
          <div class="card-header">
            <div class="stock-info">
              <span class="stock-name">{{ ep.stockName }}</span>
              <span class="stock-code">{{ ep.stockCode }}</span>
            </div>
            <span class="surprise-badge" :class="ep.surpriseType?.toLowerCase()">
              {{ ep.surpriseType === 'TURNAROUND' ? '흑자전환' : '실적개선' }}
            </span>
          </div>
          <div class="card-metrics">
            <div class="metric" v-if="ep.operatingProfitChangeRate">
              <span class="metric-label">영업이익</span>
              <span class="metric-value positive">+{{ Number(ep.operatingProfitChangeRate).toFixed(1) }}%</span>
            </div>
            <div class="metric" v-if="ep.netIncomeChangeRate">
              <span class="metric-label">순이익</span>
              <span class="metric-value" :class="ep.netIncomeChangeRate > 0 ? 'positive' : 'negative'">
                {{ ep.netIncomeChangeRate > 0 ? '+' : '' }}{{ Number(ep.netIncomeChangeRate).toFixed(1) }}%
              </span>
            </div>
          </div>
          <div class="card-summary" v-if="ep.summary">{{ ep.summary }}</div>
        </div>
      </div>
      <div v-else class="radar-empty">실적 개선 추세 종목이 없습니다.</div>
    </div>
  </div>
</template>

<script>
import { radarAPI } from '@/utils/api'

export default {
  name: 'SectionRadar',
  inject: { openStock: { default: null } },
  data() {
    return {
      activeTab: 'policy',
      loading: false,
      policyNews: [],
      nearHighStocks: [],
      largeHoldings: [],
      earningsPredictions: [],
      tabs: [
        { key: 'policy', icon: '📰', label: '정책/테마' },
        { key: 'nearHigh', icon: '📈', label: '신고가 직전' },
        { key: 'holdings', icon: '🏛️', label: '대량 취득' },
        { key: 'earnings', icon: '💰', label: '실적 예측' }
      ],
      loadedTabs: {}
    }
  },
  watch: {
    activeTab(tab) {
      if (!this.loadedTabs[tab]) this.fetchTabData(tab)
    }
  },
  mounted() {
    this.fetchTabData('policy')
  },
  methods: {
    async fetchTabData(tab) {
      this.loading = true
      try {
        let res
        switch (tab) {
          case 'policy':
            res = await radarAPI.getPolicyNews()
            if (res.data.success) this.policyNews = res.data.data
            break
          case 'nearHigh':
            res = await radarAPI.getNearHigh()
            if (res.data.success) this.nearHighStocks = res.data.data
            break
          case 'holdings':
            res = await radarAPI.getLargeHoldings()
            if (res.data.success) this.largeHoldings = res.data.data
            break
          case 'earnings':
            res = await radarAPI.getEarningsPredictions()
            if (res.data.success) this.earningsPredictions = res.data.data
            break
        }
        this.loadedTabs[tab] = true
      } catch (e) {
        console.error('레이더 데이터 조회 실패:', e)
      } finally {
        this.loading = false
      }
    },
    getSentimentLabel(s) {
      if (s === 'POSITIVE') return '긍정'
      if (s === 'NEGATIVE') return '부정'
      return '중립'
    },
    formatPrice(val) {
      if (!val) return '-'
      return Number(val).toLocaleString('ko-KR') + '원'
    },
    formatTime(dt) {
      if (!dt) return ''
      const d = new Date(dt)
      return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    },
    goToStock(code) {
      if (this.openStock) this.openStock(code)
      else this.$router.push(`/stock/${code}`)
    }
  }
}
</script>

<style scoped>
.radar-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.radar-tab {
  padding: 8px 14px;
  border: 1px solid rgba(255,255,255,0.08);
  background: rgba(255,255,255,0.03);
  color: rgba(255,255,255,0.5);
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: 0.2s;
}

.radar-tab.active {
  background: rgba(245,158,11,0.15);
  border-color: rgba(245,158,11,0.4);
  color: #f59e0b;
}

.radar-tab:hover:not(.active) { background: rgba(255,255,255,0.06); }

.radar-loading {
  text-align: center;
  padding: 40px;
  color: rgba(255,255,255,0.4);
  font-size: 14px;
}

.radar-empty {
  text-align: center;
  padding: 40px 20px;
  color: rgba(255,255,255,0.3);
  font-size: 14px;
}

.radar-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.radar-card {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 12px;
  padding: 14px;
  transition: 0.2s;
}

.radar-card:hover { background: rgba(255,255,255,0.06); }
.near-high-card, .holding-card, .earnings-card { cursor: pointer; }

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: rgba(255,255,255,0.9);
  line-height: 1.4;
  margin-bottom: 6px;
}

.card-summary {
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  line-height: 1.5;
  margin-bottom: 8px;
}

.card-time {
  font-size: 11px;
  color: rgba(255,255,255,0.3);
}

.card-tags { display: flex; gap: 4px; flex-wrap: wrap; }

.tag {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}

.sector-tag { background: rgba(102,126,234,0.15); color: #8b9cf7; }
.keyword-tag { background: rgba(245,158,11,0.12); color: #f59e0b; }

.sentiment-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
}

.sentiment-badge.positive { background: rgba(239,68,68,0.15); color: #ef4444; }
.sentiment-badge.negative { background: rgba(59,130,246,0.15); color: #3b82f6; }
.sentiment-badge.neutral { background: rgba(107,114,128,0.15); color: #9ca3af; }

.stock-info { display: flex; flex-direction: column; }
.stock-name { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.9); }
.stock-code { font-size: 11px; color: rgba(255,255,255,0.35); font-family: monospace; }

.gap-badge {
  font-size: 12px;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: 8px;
  background: rgba(239,68,68,0.15);
  color: #ef4444;
}

.card-prices {
  display: flex;
  gap: 20px;
}

.price-item { display: flex; flex-direction: column; }
.price-label { font-size: 11px; color: rgba(255,255,255,0.35); }
.price-value { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.8); }
.price-value.high { color: #ef4444; }

.corp-name { font-size: 14px; font-weight: 700; color: rgba(255,255,255,0.9); }
.report-date { font-size: 11px; color: rgba(255,255,255,0.35); }
.card-report { font-size: 13px; color: rgba(255,255,255,0.6); margin-bottom: 4px; }
.card-submitter { font-size: 11px; color: rgba(255,255,255,0.35); }

.surprise-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
}

.surprise-badge.positive { background: rgba(239,68,68,0.15); color: #ef4444; }
.surprise-badge.turnaround { background: rgba(168,85,247,0.15); color: #a855f7; }

.card-metrics { display: flex; gap: 16px; margin-bottom: 6px; }
.metric { display: flex; flex-direction: column; }
.metric-label { font-size: 11px; color: rgba(255,255,255,0.35); }
.metric-value { font-size: 14px; font-weight: 700; }
.metric-value.positive { color: #ef4444; }
.metric-value.negative { color: #3b82f6; }

@media (max-width: 768px) {
  .radar-tabs { gap: 4px; }
  .radar-tab { padding: 6px 10px; font-size: 12px; }
  .card-prices { flex-direction: column; gap: 4px; }
}
</style>
