<template>
  <div class="section-card live-surge">
    <div class="section-title-row">
      <h2><span class="section-icon">⚡</span> 실시간 수급 급증</h2>
      <span class="meta">
        <span class="auto-refresh-badge" :class="{ active: isAutoRefresh }">
          <span class="status-dot"></span>
          <span v-if="isAutoRefresh">실시간 ({{ nextRefresh }}s)</span>
          <span v-else class="inactive-text">장 외</span>
        </span>
        <button class="refresh-btn" :disabled="loading" @click="fetchSurge" title="수동 갱신">
          {{ loading ? '⏳' : '↻' }}
        </button>
      </span>
    </div>

    <div class="sub-controls">
      <div class="filter-item">
        <label>최소 변화량</label>
        <select v-model="minChange" @change="fetchSurge">
          <option :value="30">30억 이상</option>
          <option :value="50">50억 이상</option>
          <option :value="100">100억 이상</option>
          <option :value="200">200억 이상</option>
        </select>
      </div>
      <span class="last-update" v-if="lastUpdate">마지막: {{ lastUpdate }}</span>
    </div>

    <div class="investor-tabs">
      <button v-for="type in investorTypes" :key="type.value"
              :class="['tab-btn', { active: investor === type.value }]"
              @click="investor = type.value">
        {{ type.icon }} {{ type.label }}
      </button>
    </div>

    <div v-if="loading && !currentStocks.length" class="loading-state">
      <div v-for="i in 4" :key="'sk-'+i" class="skel-row"></div>
    </div>

    <div v-else-if="currentStocks.length > 0" class="stocks-grid">
      <div v-for="stock in currentStocks" :key="stock.stockCode"
           :class="['stock-card', 'surge-card', stock.surgeLevel?.toLowerCase(), getTrendClass(stock.trendStatus), { common: investor === 'COMMON', outdated: stock.outdated }]"
           @click="goStock(stock.stockCode)">
        <div class="surge-badge-label" :class="stock.surgeLevel?.toLowerCase()"
             v-if="shouldShowHotBadge(stock)">
          {{ getSurgeLevelText(stock.surgeLevel) }}
        </div>
        <div class="trend-badge" :class="getTrendClass(stock.trendStatus)"
             v-if="stock.trendStatus && stock.trendStatus !== 'NORMAL' && stock.trendStatusName">
          {{ getTrendIcon(stock.trendStatus) }} {{ stock.trendStatusName }}
        </div>
        <div class="stock-header">
          <div class="stock-info">
            <span class="card-stock-name">{{ stock.stockName }}</span>
            <span class="card-stock-code">{{ stock.stockCode }}</span>
          </div>
          <div class="rank-info">
            <span class="current-rank">#{{ stock.currentRank }}</span>
            <span class="rank-change-badge" :class="getRankChangeClass(stock.rankChange)" v-if="stock.rankChange">
              {{ formatRankChange(stock.rankChange) }}
            </span>
          </div>
        </div>
        <div class="stock-details">
          <div class="detail-row highlight">
            <span class="label">{{ investor === 'COMMON' ? '합산 순매수' : '누적 순매수' }}</span>
            <span class="value amount" :class="getAmountClass(stock.netBuyAmount)">
              {{ stock.formattedNetBuyAmount || '-' }}
            </span>
          </div>
          <template v-if="investor === 'COMMON'">
            <div class="detail-row foreign-row">
              <span class="label">🌍 외국인</span>
              <span class="value" :class="getAmountClass(stock.foreignNetBuy)">
                {{ stock.formattedForeignNetBuy || '-' }}
              </span>
            </div>
            <div class="detail-row institution-row">
              <span class="label">🏢 기관</span>
              <span class="value" :class="getAmountClass(stock.institutionNetBuy)">
                {{ stock.formattedInstitutionNetBuy || '-' }}
              </span>
            </div>
          </template>
          <div class="detail-row" v-if="hasFormattedChange(stock.formattedChangeAmount) && investor !== 'COMMON'">
            <span class="label">변화량</span>
            <span class="value" :class="getAmountClass(stock.amountChange)">
              {{ stock.formattedChangeAmount }}
            </span>
          </div>
          <div class="detail-row" v-if="stock.currentPrice">
            <span class="label">현재가</span>
            <span class="value">{{ formatNumber(stock.currentPrice) }}원</span>
          </div>
          <div class="detail-row" v-if="stock.changeRate">
            <span class="label">등락률</span>
            <span class="value rate" :class="getRateClass(stock.changeRate)">
              {{ formatRate(stock.changeRate) }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="empty-msg">
      <p>수급 급증 종목이 없습니다.</p>
      <p class="hint">장중(09:10~15:20)에 자동 수집됩니다.</p>
    </div>
  </div>
</template>

<script>
import { investorAPI } from '../../utils/api'

const REFRESH_MS = 30000

export default {
  name: 'SectionLiveSurge',
  props: {
    active: { type: Boolean, default: true }
  },
  data() {
    return {
      loading: false,
      investor: 'FOREIGN',
      minChange: 50,
      allStocks: {},
      lastUpdate: '',
      isAutoRefresh: false,
      nextRefresh: 30,
      _autoTimer: null,
      _countdownTimer: null,
      investorTypes: [
        { value: 'FOREIGN', label: '외국인', icon: '🌍' },
        { value: 'INSTITUTION', label: '기관', icon: '🏢' },
        { value: 'COMMON', label: '공통', icon: '🤝' }
      ]
    }
  },
  computed: {
    currentStocks() {
      return this.allStocks[this.investor] || []
    }
  },
  watch: {
    active: {
      immediate: true,
      handler(v) {
        if (v) {
          this.fetchSurge()
          this.startAutoRefresh()
        } else {
          this.stopAutoRefresh()
        }
      }
    }
  },
  beforeUnmount() {
    this.stopAutoRefresh()
  },
  methods: {
    isMarketOpen() {
      const now = new Date()
      const day = now.getDay()
      if (day === 0 || day === 6) return false
      const mins = now.getHours() * 60 + now.getMinutes()
      return mins >= 540 && mins <= 930
    },
    async fetchSurge() {
      if (this.loading) return
      this.loading = true
      try {
        const res = await investorAPI.getAllSurgeStocks(this.minChange)
        const body = res?.data || res
        if (body?.success) {
          this.allStocks = body.data || {}
          this.lastUpdate = new Date().toLocaleTimeString('ko-KR')
        }
      } catch (e) {
        console.warn('[LiveSurge] 로딩 실패', e?.message)
      } finally {
        this.loading = false
      }
    },
    startAutoRefresh() {
      this.stopAutoRefresh()
      if (!this.isMarketOpen()) {
        this.isAutoRefresh = false
        return
      }
      this.isAutoRefresh = true
      this.nextRefresh = 30
      this._countdownTimer = setInterval(() => {
        this.nextRefresh--
        if (this.nextRefresh <= 0) this.nextRefresh = 30
      }, 1000)
      this._autoTimer = setInterval(async () => {
        if (!this.isMarketOpen()) {
          this.stopAutoRefresh()
          return
        }
        await this.fetchSurge()
        this.nextRefresh = 30
      }, REFRESH_MS)
    },
    stopAutoRefresh() {
      if (this._autoTimer) { clearInterval(this._autoTimer); this._autoTimer = null }
      if (this._countdownTimer) { clearInterval(this._countdownTimer); this._countdownTimer = null }
      this.isAutoRefresh = false
    },
    goStock(code) {
      if (code) this.$router.push(`/stock/${code}`)
    },
    shouldShowHotBadge(stock) {
      if (!stock.surgeLevel || stock.surgeLevel === 'NORMAL') return false
      if (stock.trendStatus === 'PROFIT_TAKING') return false
      return (stock.amountChange && Number(stock.amountChange) > 0) || stock.trendStatus === 'ACCUMULATING'
    },
    getSurgeLevelText(level) {
      if (level === 'HOT') return '🔥 HOT'
      if (level === 'WARM') return '⚡ WARM'
      return ''
    },
    getTrendClass(status) {
      if (status === 'ACCUMULATING') return 'trend-accumulating'
      if (status === 'PROFIT_TAKING') return 'trend-profit-taking'
      return 'trend-normal'
    },
    getTrendIcon(status) {
      if (status === 'ACCUMULATING') return '🚀'
      if (status === 'PROFIT_TAKING') return '💰'
      return ''
    },
    formatRankChange(change) {
      if (!change) return ''
      if (change > 0) return `▲${change}`
      if (change < 0) return `▼${Math.abs(change)}`
      return '-'
    },
    getRankChangeClass(change) {
      if (!change) return ''
      return change > 0 ? 'rank-up' : change < 0 ? 'rank-down' : ''
    },
    hasFormattedChange(val) { return val && val !== '-' },
    formatNumber(value) {
      if (!value) return '0'
      return Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 })
    },
    formatRate(value) {
      if (!value) return '0.00%'
      const sign = value > 0 ? '+' : ''
      return `${sign}${Number(value).toFixed(2)}%`
    },
    getRateClass(value) {
      if (!value) return ''
      return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : ''
    },
    getAmountClass(value) {
      if (!value) return ''
      return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : ''
    }
  }
}
</script>

<style scoped>
.section-card {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20px;
  padding: 18px 20px;
  color: #fff;
}
.live-surge { background: linear-gradient(135deg, rgba(245,158,11,0.06), rgba(239,68,68,0.04)); }

.section-title-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
}
.section-title-row h2 {
  font-size: 17px; margin: 0; display: flex; align-items: center; gap: 8px;
}
.meta { display: flex; align-items: center; gap: 8px; }

.auto-refresh-badge {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px;
  border-radius: 10px;
  font-size: 11px; font-weight: 600;
  background: rgba(255,255,255,0.05);
  color: rgba(255,255,255,0.45);
}
.auto-refresh-badge .status-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: rgba(255,255,255,0.3);
}
.auto-refresh-badge.active {
  background: rgba(34,197,94,0.18);
  color: #4ade80;
}
.auto-refresh-badge.active .status-dot {
  background: #4ade80;
  animation: pulse 2s infinite;
}
.auto-refresh-badge .inactive-text { font-size: 10.5px; }
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.refresh-btn {
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.7);
  width: 22px; height: 22px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
  padding: 0;
}
.refresh-btn:hover:not(:disabled) {
  background: rgba(255,255,255,0.14);
  color: #fff;
}
.refresh-btn:disabled { opacity: 0.5; cursor: wait; }

.sub-controls {
  display: flex; align-items: center; justify-content: space-between;
  flex-wrap: wrap; gap: 10px;
  margin-bottom: 12px;
}
.filter-item {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px; color: rgba(255,255,255,0.6);
}
.filter-item select {
  padding: 5px 10px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  color: #fff;
  border-radius: 7px;
  font-size: 12px;
}
.last-update {
  font-size: 11px; color: rgba(255,255,255,0.4);
}

.investor-tabs {
  display: flex; gap: 6px;
  background: rgba(0,0,0,0.18);
  padding: 4px;
  border-radius: 10px;
  margin-bottom: 12px;
}
.tab-btn {
  flex: 1;
  padding: 6px 12px;
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.55);
  font-size: 12.5px;
  font-weight: 600;
  border-radius: 7px;
  cursor: pointer;
  transition: all 0.15s;
}
.tab-btn:hover { color: rgba(255,255,255,0.85); }
.tab-btn.active { background: rgba(255,255,255,0.08); color: #fff; }

.loading-state { display: flex; flex-direction: column; gap: 8px; }
.skel-row {
  height: 80px; border-radius: 12px;
  background: rgba(255,255,255,0.04);
  animation: skel 1.5s infinite;
}
@keyframes skel {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 0.2; }
}

.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.stock-card {
  position: relative;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
  padding: 14px;
  cursor: pointer;
  transition: all 0.18s;
}
.stock-card:hover {
  background: rgba(255,255,255,0.07);
  transform: translateY(-1px);
}
.stock-card.outdated { opacity: 0.55; }
.surge-card.hot { border-color: rgba(239,68,68,0.4); background: rgba(239,68,68,0.06); }
.surge-card.warm { border-color: rgba(245,158,11,0.4); background: rgba(245,158,11,0.05); }
.surge-card.common { border-color: rgba(168,85,247,0.4); background: rgba(168,85,247,0.06); }
.surge-card.trend-accumulating { box-shadow: 0 0 0 1px rgba(34,197,94,0.25) inset; }
.surge-card.trend-profit-taking { box-shadow: 0 0 0 1px rgba(59,130,246,0.25) inset; }

.surge-badge-label {
  position: absolute; top: -8px; left: 12px;
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 10.5px; font-weight: 700;
  background: var(--surface-solid, #1a1a2e);
}
.surge-badge-label.hot { color: #f87171; border: 1px solid rgba(239,68,68,0.5); }
.surge-badge-label.warm { color: #fbbf24; border: 1px solid rgba(245,158,11,0.5); }

.trend-badge {
  position: absolute; top: -8px; right: 12px;
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 10.5px; font-weight: 700;
  background: var(--surface-solid, #1a1a2e);
}
.trend-badge.trend-accumulating { color: #4ade80; border: 1px solid rgba(34,197,94,0.5); }
.trend-badge.trend-profit-taking { color: #60a5fa; border: 1px solid rgba(59,130,246,0.5); }

.stock-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-bottom: 10px;
}
.stock-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.card-stock-name {
  font-size: 14px; font-weight: 600; color: #fff;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.card-stock-code {
  font-family: monospace; font-size: 10.5px;
  color: rgba(255,255,255,0.4);
}
.rank-info {
  display: flex; flex-direction: column; align-items: flex-end; gap: 2px;
  flex-shrink: 0;
}
.current-rank {
  font-size: 13px; font-weight: 700;
  color: rgba(255,255,255,0.7);
}
.rank-change-badge {
  font-size: 10px; font-weight: 700;
  padding: 1px 5px; border-radius: 5px;
  background: rgba(255,255,255,0.06);
  color: rgba(255,255,255,0.5);
}
.rank-change-badge.rank-up { background: rgba(239,68,68,0.18); color: #f87171; }
.rank-change-badge.rank-down { background: rgba(59,130,246,0.18); color: #60a5fa; }

.stock-details { display: flex; flex-direction: column; gap: 4px; }
.detail-row {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px;
}
.detail-row .label { color: rgba(255,255,255,0.5); }
.detail-row .value { color: rgba(255,255,255,0.9); font-weight: 600; }
.detail-row.highlight .value { font-size: 13.5px; }
.detail-row .value.positive { color: #f87171; }
.detail-row .value.negative { color: #60a5fa; }
.foreign-row .label, .institution-row .label { font-size: 11.5px; }
.value.rate.positive { color: #f87171; }
.value.rate.negative { color: #60a5fa; }

.empty-msg {
  text-align: center; padding: 24px;
  color: rgba(255,255,255,0.4); font-size: 12.5px;
}
.empty-msg .hint { margin-top: 6px; font-size: 11px; opacity: 0.8; }

@media (max-width: 600px) {
  .section-card { padding: 14px 16px; }
  .section-title-row h2 { font-size: 15px; }
  .stocks-grid { grid-template-columns: 1fr; }
  .card-stock-name { font-size: 13px; }
  .detail-row { font-size: 11.5px; }
}
</style>
