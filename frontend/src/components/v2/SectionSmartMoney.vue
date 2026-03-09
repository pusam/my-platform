<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">💰</span> 스마트 머니</h2>
      <router-link to="/investor-trades" class="more-link">더 보기 →</router-link>
    </div>

    <SkeletonLoader v-if="loading" type="table" :rows="5" />

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

      <!-- 매매 동향 -->
      <div v-if="activeTab === 'trades'" class="trades-list">
        <div class="investor-toggle">
          <button
            :class="['toggle-btn', { active: investorType === 'FOREIGN' }]"
            @click="investorType = 'FOREIGN'"
          >외국인</button>
          <button
            :class="['toggle-btn', { active: investorType === 'INSTITUTION' }]"
            @click="investorType = 'INSTITUTION'"
          >기관</button>
        </div>
        <div class="trade-table">
          <div
            v-for="(item, i) in filteredTrades"
            :key="item.stockCode + '-' + i"
            class="trade-row"
            @click="goToStock(item.stockCode)"
          >
            <span class="trade-rank">{{ i + 1 }}</span>
            <span class="trade-name">{{ item.stockName }}</span>
            <span class="trade-amount" :class="item.netBuyAmount >= 0 ? 'buy' : 'sell'">
              {{ formatAmount(item.netBuyAmount) }}
            </span>
            <span v-if="item.rankChange != null && item.rankChange !== 0" class="rank-change" :class="item.rankChange > 0 ? 'rank-up' : 'rank-down'">
              {{ item.rankChange > 0 ? '▲' : '▼' }}{{ Math.abs(item.rankChange) }}
            </span>
            <span v-else-if="item.rankChange === 0" class="rank-change rank-same">-</span>
          </div>
        </div>
        <div v-if="filteredTrades.length === 0" class="empty-msg">매매 데이터 없음</div>
      </div>

      <!-- 연속 매수 -->
      <div v-if="activeTab === 'consecutive'" class="consecutive-list">
        <div
          v-for="(item, i) in consecutiveData.slice(0, 10)"
          :key="item.stockCode + '-c-' + i"
          class="trade-row"
          @click="goToConsecutiveDetail(item.stockCode)"
        >
          <span class="trade-rank">{{ i + 1 }}</span>
          <span class="trade-name">{{ item.stockName }}</span>
          <div class="consecutive-info">
            <span class="days-badge">{{ item.consecutiveDays || item.days }}일</span>
            <span class="investor-badge" v-if="item.investorType">
              {{ item.investorType === 'FOREIGN' ? '외인' : '기관' }}
            </span>
          </div>
        </div>
        <div v-if="consecutiveData.length === 0" class="empty-msg">연속 매수 종목 없음</div>
        <div class="more-links">
          <router-link to="/consecutive-buy">전체 목록 →</router-link>
        </div>
      </div>

      <!-- 수급 급증 -->
      <div v-if="activeTab === 'surge'" class="surge-list">
        <div
          v-for="(item, i) in surgeData.slice(0, 10)"
          :key="item.stockCode + '-s-' + i"
          class="trade-row"
          @click="goToStock(item.stockCode)"
        >
          <span class="trade-rank">{{ i + 1 }}</span>
          <span class="trade-name">{{ item.stockName }}</span>
          <span class="surge-ratio" v-if="item.surgeRatio">{{ item.surgeRatio }}%</span>
          <span class="surge-change" :class="(item.amountChange || 0) >= 0 ? 'up' : 'down'">
            {{ formatSurgeAmount(item.amountChange) }}
          </span>
        </div>
        <div v-if="surgeData.length === 0" class="empty-msg">수급 급증 종목 없음</div>
        <div class="more-links">
          <router-link to="/investor-surge">전체 목록 →</router-link>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import SkeletonLoader from './SkeletonLoader.vue'

export default {
  name: 'SectionSmartMoney',
  components: { SkeletonLoader },
  inject: { openStock: { default: null } },
  props: {
    tradesData: { type: Object, default: () => ({ foreign: [], institution: [] }) },
    consecutiveData: { type: Array, default: () => [] },
    surgeData: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    error: { type: Boolean, default: false }
  },
  emits: ['retry'],
  data() {
    return {
      activeTab: 'trades',
      investorType: 'FOREIGN',
      tabs: [
        { key: 'trades', label: '매매 동향 (당일)' },
        { key: 'consecutive', label: '연속 매수 (최근 30일)' },
        { key: 'surge', label: '수급 급증 (장중)' }
      ]
    }
  },
  computed: {
    filteredTrades() {
      const data = this.investorType === 'FOREIGN'
        ? this.tradesData.foreign
        : this.tradesData.institution
      return (data || []).slice(0, 10)
    }
  },
  methods: {
    formatAmount(val) {
      if (!val) return '-'
      // 백엔드가 억원 단위로 반환 (예: 1.5 = 1.5억원)
      if (Math.abs(val) >= 1) return (val >= 0 ? '+' : '') + val.toFixed(0) + '억'
      const million = val * 100  // 억 → 백만 변환 (1억 = 100백만)
      return (million >= 0 ? '+' : '') + million.toFixed(0) + '백만'
    },
    formatSurgeAmount(val) {
      if (val == null || val === 0) return '-'
      const abs = Math.abs(val)
      const sign = val >= 0 ? '+' : '-'
      if (abs >= 1) return sign + abs.toFixed(1) + '억'
      return sign + (abs * 10000 / 10).toFixed(0) + '만'
    },
    goToStock(code) {
      if (this.openStock) this.openStock(code)
      else this.$router.push(`/stock/${code}`)
    },
    goToConsecutiveDetail(code) {
      if (this.openStock) this.openStock(code)
      else this.$router.push(`/investor-stock/${code}`)
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
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

/* Investor Toggle */
.investor-toggle {
  display: flex; gap: 4px; margin-bottom: 10px;
}
.toggle-btn {
  padding: 4px 12px; border: 1px solid rgba(255,255,255,0.1); background: transparent;
  color: rgba(255,255,255,0.5); font-size: 12px; cursor: pointer; border-radius: 6px;
  transition: all 0.2s;
}
.toggle-btn.active {
  background: rgba(102,126,234,0.2); border-color: #667eea; color: #8b9cf7;
}

/* Trade Table */
.trade-table { max-height: 300px; overflow-y: auto; }

.trade-row {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 8px; border-bottom: 1px solid rgba(255,255,255,0.04);
  cursor: pointer; transition: background 0.2s;
}
.trade-row:hover { background: rgba(255,255,255,0.04); }
.trade-row:last-child { border-bottom: none; }

.trade-rank { font-size: 12px; color: rgba(255,255,255,0.35); width: 20px; text-align: center; }
.trade-name { flex: 1; font-size: 13px; color: rgba(255,255,255,0.85); }

.trade-amount { font-size: 12px; font-weight: 600; }
.trade-amount.buy { color: #ef4444; }
.trade-amount.sell { color: #3b82f6; }

.consecutive-info { display: flex; gap: 4px; align-items: center; }
.days-badge {
  font-size: 11px; font-weight: 700; padding: 2px 6px;
  border-radius: 4px; background: rgba(245,158,11,0.2); color: #f59e0b;
}
.investor-badge {
  font-size: 10px; padding: 2px 5px; border-radius: 4px;
  background: rgba(102,126,234,0.15); color: #8b9cf7;
}

/* Rank Change */
.rank-change {
  font-size: 11px; font-weight: 700; min-width: 28px; text-align: center;
}
.rank-up { color: #ef4444; }
.rank-down { color: #3b82f6; }
.rank-same { color: rgba(255,255,255,0.25); font-size: 10px; }

/* Surge */
.surge-ratio {
  font-size: 10px; font-weight: 600; padding: 2px 5px; border-radius: 4px;
  background: rgba(245,158,11,0.15); color: #f59e0b;
}
.surge-change { font-size: 12px; font-weight: 600; }
.surge-change.up { color: #ef4444; }
.surge-change.down { color: #3b82f6; }

.more-links { margin-top: 8px; }
.more-links a { font-size: 12px; color: rgba(255,255,255,0.4); text-decoration: none; }
.more-links a:hover { color: #667eea; }

.empty-msg { text-align: center; color: rgba(255,255,255,0.3); font-size: 13px; padding: 20px 0; }

@media (max-width: 768px) {
  .section-card { padding: 16px; border-radius: 14px; }
  .section-title-row h2 { font-size: 14px; }
  .stock-card { padding: 8px; gap: 8px; }
  .stock-name { font-size: 13px; }
}
</style>
