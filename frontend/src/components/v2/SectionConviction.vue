<template>
  <div class="section-card conviction-section">
    <div class="section-title-row">
      <h2><span class="section-icon">🎯</span> 멀티 컨빅션 시그널</h2>
      <span v-if="analysisDate" class="conviction-date">{{ analysisDate }} 기준</span>
    </div>

    <div v-if="loading" class="signal-skeleton">
      <div class="skel-row" v-for="i in 3" :key="i"><div class="skel-bar"></div></div>
    </div>

    <template v-else-if="hasData">
      <!-- 매수 시그널 -->
      <div v-if="buySignals.length" class="conviction-group">
        <div class="group-label buy-label">주목 (멀티 매수)</div>
        <div v-for="sig in buySignals" :key="'b-'+sig.stockCode" class="conviction-card buy"
             @click="$emit('stock-click', sig.stockCode)">
          <div class="conv-left">
            <span class="conv-stars">{{ '★'.repeat(sig.stars) }}</span>
            <span class="conv-name">{{ sig.stockName }}</span>
            <span class="conv-change" :class="sig.changeRate >= 0 ? 'positive' : 'negative'">
              {{ sig.changeRate >= 0 ? '+' : '' }}{{ Number(sig.changeRate).toFixed(2) }}%
            </span>
          </div>
          <div class="conv-detail">{{ sig.description }}</div>
        </div>
      </div>

      <!-- 매도 시그널 -->
      <div v-if="sellSignals.length" class="conviction-group">
        <div class="group-label sell-label">주의 (멀티 매도)</div>
        <div v-for="sig in sellSignals" :key="'s-'+sig.stockCode" class="conviction-card sell"
             @click="$emit('stock-click', sig.stockCode)">
          <div class="conv-left">
            <span class="conv-stars sell">{{ '★'.repeat(sig.stars) }}</span>
            <span class="conv-name">{{ sig.stockName }}</span>
            <span class="conv-change" :class="sig.changeRate >= 0 ? 'positive' : 'negative'">
              {{ sig.changeRate >= 0 ? '+' : '' }}{{ Number(sig.changeRate).toFixed(2) }}%
            </span>
          </div>
          <div class="conv-detail">{{ sig.description }}</div>
        </div>
      </div>

      <!-- 방향 충돌 -->
      <div v-if="conflictSignals.length" class="conviction-group">
        <div class="group-label conflict-label">방향 충돌 (관망)</div>
        <div v-for="sig in conflictSignals" :key="'c-'+sig.stockCode" class="conviction-card conflict"
             @click="$emit('stock-click', sig.stockCode)">
          <div class="conv-left">
            <span class="conv-stars conflict">⚡</span>
            <span class="conv-name">{{ sig.stockName }}</span>
          </div>
          <div class="conv-detail">{{ sig.description }}</div>
        </div>
      </div>
    </template>

    <div v-else class="empty-signal">투자자별 상세 데이터 수집 후 표시됩니다<br><small style="opacity:0.7">장 마감(16:10) 이후 자동 분석</small></div>
  </div>
</template>

<script>
import { investorAPI } from '../../utils/api'

export default {
  name: 'SectionConviction',
  emits: ['stock-click'],
  data() {
    return {
      loading: false,
      buySignals: [],
      sellSignals: [],
      conflictSignals: [],
      analysisDate: ''
    }
  },
  computed: {
    hasData() {
      return this.buySignals.length > 0 || this.sellSignals.length > 0 || this.conflictSignals.length > 0
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const res = await investorAPI.getConvictionSignals()
        const data = res?.data?.data || res?.data || {}
        this.buySignals = data.buySignals || []
        this.sellSignals = data.sellSignals || []
        this.conflictSignals = data.conflictSignals || []
        this.analysisDate = data.analysisDate || ''
      } catch (e) {
        console.debug('멀티컨빅션 조회 실패:', e.message)
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.conviction-section { }

.conviction-date {
  font-size: 11px;
  color: rgba(255,255,255,0.5);
  font-weight: 600;
}

.conviction-group {
  margin-bottom: 16px;
}
.conviction-group:last-child {
  margin-bottom: 0;
}

.group-label {
  font-size: 12px;
  font-weight: 700;
  padding: 4px 10px;
  border-radius: 6px;
  margin-bottom: 8px;
  display: inline-block;
}
.buy-label { background: rgba(34,197,94,0.12); color: #22c55e; }
.sell-label { background: rgba(239,68,68,0.12); color: #ef4444; }
.conflict-label { background: rgba(245,158,11,0.12); color: #f59e0b; }

.conviction-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  border-radius: 10px;
  cursor: pointer;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  margin-bottom: 6px;
  transition: all 0.15s;
}
.conviction-card:hover { background: rgba(255,255,255,0.06); }
.conviction-card.buy { border-left: 3px solid #22c55e; }
.conviction-card.sell { border-left: 3px solid #ef4444; }
.conviction-card.conflict { border-left: 3px solid #f59e0b; }

.conv-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-stars { color: #f59e0b; font-size: 13px; font-weight: 800; }
.conv-stars.sell { color: #ef4444; }
.conv-stars.conflict { color: #f59e0b; }

.conv-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.9); }

.conv-change { font-size: 12px; font-weight: 700; }
.conv-change.positive { color: #ef4444; }
.conv-change.negative { color: #3b82f6; }

.conv-detail {
  font-size: 12px;
  color: rgba(255,255,255,0.6);
  line-height: 1.4;
}

.signal-skeleton { display: flex; flex-direction: column; gap: 8px; padding: 8px 0; }
.skel-row { height: 48px; border-radius: 10px; background: rgba(255,255,255,0.04); }
.skel-bar { width: 60%; height: 12px; margin: 18px 16px; border-radius: 4px; background: rgba(255,255,255,0.08); animation: skeleton-pulse 1.5s infinite; }
@keyframes skeleton-pulse { 0%,100% { opacity: 0.5; } 50% { opacity: 0.2; } }

.empty-signal { text-align: center; padding: 32px 16px; color: rgba(255,255,255,0.5); font-size: 13px; }
.empty-signal::before { content: '🎯'; display: block; font-size: 28px; margin-bottom: 8px; opacity: 0.6; }
</style>
