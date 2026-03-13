<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">📊</span> AI 전략 성과</h2>
      <div class="period-btns">
        <button
          v-for="p in periods"
          :key="p.days"
          :class="['period-btn', { active: selectedDays === p.days }]"
          @click="changePeriod(p.days)"
        >{{ p.label }}</button>
      </div>
    </div>

    <div v-if="loading" class="state-box">
      <p class="state-text">성과 분석 중...</p>
    </div>

    <div v-else-if="error" class="state-box">
      <span class="state-icon">⚠️</span>
      <p class="state-text">데이터를 불러오지 못했습니다</p>
      <button class="state-btn" @click="fetchData">새로고침</button>
    </div>

    <template v-else-if="data">
      <!-- AI 전략 vs 실전 봇 비교 -->
      <div class="compare-section" v-if="botStats">
        <div class="compare-header">
          <span class="compare-title">AI 전략 vs 실전 봇</span>
        </div>
        <div class="compare-grid">
          <div class="compare-col">
            <span class="compare-col-label">📊 AI 전략</span>
            <div class="compare-item">
              <span class="compare-metric">적중률</span>
              <span class="compare-val" :class="hitRateClass(data.overall.hitRate)">{{ data.overall.hitRate }}%</span>
            </div>
            <div class="compare-item">
              <span class="compare-metric">평균 수익률</span>
              <span class="compare-val" :class="returnClass(data.overall.avgReturn)">
                {{ data.overall.avgReturn >= 0 ? '+' : '' }}{{ data.overall.avgReturn }}%
              </span>
            </div>
            <div class="compare-item">
              <span class="compare-metric">종목 수</span>
              <span class="compare-val neutral">{{ data.overall.totalPicks }}개</span>
            </div>
          </div>
          <div class="compare-divider"></div>
          <div class="compare-col">
            <span class="compare-col-label">🤖 실전 봇</span>
            <div class="compare-item">
              <span class="compare-metric">승률</span>
              <span class="compare-val" :class="hitRateClass(botStats.winRate || 0)">{{ botStats.winRate || 0 }}%</span>
            </div>
            <div class="compare-item">
              <span class="compare-metric">손익비</span>
              <span class="compare-val" :class="returnClass(botStats.profitFactor > 1 ? 1 : -1)">
                {{ botStats.profitFactor || '-' }}
              </span>
            </div>
            <div class="compare-item">
              <span class="compare-metric">거래 수</span>
              <span class="compare-val neutral">{{ botStats.totalTrades || 0 }}건</span>
            </div>
          </div>
        </div>
        <div class="compare-detail-row">
          <span class="detail-chip">{{ botStats.winCount || 0 }}승 {{ botStats.loseCount || 0 }}패</span>
          <span class="detail-chip">평균수익 {{ formatWon(botStats.avgProfitPerWin) }}</span>
          <span class="detail-chip">평균손실 {{ formatWon(botStats.avgLossPerLose) }}</span>
        </div>
      </div>

      <!-- 전체 요약 (봇 데이터 없을 때 기존 UI) -->
      <div class="overall-row" v-if="!botStats">
        <div class="stat-box">
          <span class="stat-label">전체 적중률</span>
          <span class="stat-value" :class="hitRateClass(data.overall.hitRate)">
            {{ data.overall.hitRate }}%
          </span>
        </div>
        <div class="stat-box">
          <span class="stat-label">평균 수익률</span>
          <span class="stat-value" :class="returnClass(data.overall.avgReturn)">
            {{ data.overall.avgReturn >= 0 ? '+' : '' }}{{ data.overall.avgReturn }}%
          </span>
        </div>
        <div class="stat-box">
          <span class="stat-label">추천 종목</span>
          <span class="stat-value neutral">{{ data.overall.totalPicks }}개</span>
        </div>
      </div>

      <!-- 전략별 성과 -->
      <div class="strategy-list">
        <div
          v-for="st in data.strategies"
          :key="st.strategyType"
          class="strategy-card"
          :class="{ expanded: expandedStrategy === st.strategyType }"
          @click="toggleStrategy(st.strategyType)"
        >
          <div class="strategy-header">
            <span class="strategy-icon">{{ getIcon(st.strategyType) }}</span>
            <span class="strategy-name">{{ st.label }}</span>
            <div class="strategy-stats">
              <span class="hit-badge" :class="hitRateClass(st.hitRate)">
                적중 {{ st.hitRate }}%
              </span>
              <span class="return-badge" :class="returnClass(st.avgReturn)">
                {{ st.avgReturn >= 0 ? '+' : '' }}{{ st.avgReturn }}%
              </span>
            </div>
            <span class="expand-arrow">{{ expandedStrategy === st.strategyType ? '▲' : '▼' }}</span>
          </div>

          <div class="strategy-meta">
            <span>{{ st.totalPicks }}개 추천</span>
            <span>{{ st.winCount }}승 {{ st.loseCount }}패</span>
          </div>

          <!-- 종목 상세 -->
          <div v-if="expandedStrategy === st.strategyType && st.picks && st.picks.length" class="picks-list">
            <div
              v-for="pick in st.picks"
              :key="pick.stockCode"
              class="pick-row"
              @click.stop="goToStock(pick.stockCode)"
            >
              <div class="pick-info">
                <span class="pick-name">{{ pick.stockName }}</span>
                <span class="pick-code">{{ pick.stockCode }}</span>
              </div>
              <div class="pick-prices">
                <span class="pick-rec">{{ formatPrice(pick.recommendPrice) }} →</span>
                <span class="pick-cur">{{ formatPrice(pick.currentPrice) }}</span>
              </div>
              <span class="pick-return" :class="returnClass(pick.returnRate)">
                {{ pick.returnRate >= 0 ? '+' : '' }}{{ pick.returnRate }}%
              </span>
            </div>
          </div>

          <!-- Best / Worst -->
          <div v-if="expandedStrategy === st.strategyType && st.bestStock" class="best-worst">
            <span class="bw-item best">🏆 {{ st.bestStock }} +{{ st.bestReturn }}%</span>
            <span class="bw-item worst" v-if="st.worstStock">📉 {{ st.worstStock }} {{ st.worstReturn }}%</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { aiStrategyAPI, paperTradingAPI } from '@/utils/api'

export default {
  name: 'SectionBacktest',
  inject: { openStock: { default: null } },
  data() {
    return {
      data: null,
      botStats: null,
      loading: false,
      error: false,
      selectedDays: 30,
      expandedStrategy: null,
      periods: [
        { days: 7, label: '7일' },
        { days: 14, label: '14일' },
        { days: 30, label: '30일' }
      ]
    }
  },
  mounted() {
    this.fetchData()
    this.fetchBotStats()
  },
  methods: {
    async fetchData() {
      this.loading = true
      this.error = false
      try {
        const res = await aiStrategyAPI.getPerformance(this.selectedDays)
        this.data = res.data
      } catch (e) {
        console.error('백테스트 데이터 조회 실패:', e)
        this.error = true
      } finally {
        this.loading = false
      }
    },
    async fetchBotStats() {
      try {
        const res = await paperTradingAPI.getStatistics()
        if (res.data && res.data.totalTrades > 0) {
          this.botStats = res.data
        }
      } catch (e) {
        // 봇 통계 없으면 비교 섹션 숨김
      }
    },
    changePeriod(days) {
      this.selectedDays = days
      this.fetchData()
    },
    toggleStrategy(type) {
      this.expandedStrategy = this.expandedStrategy === type ? null : type
    },
    formatPrice(val) {
      if (!val) return '-'
      return Number(val).toLocaleString('ko-KR') + '원'
    },
    getIcon(type) {
      const icons = { SCALPING: '⚡', SWING: '📈', TURNAROUND: '🔄', VALUE: '💎' }
      return icons[type] || '📊'
    },
    hitRateClass(rate) {
      if (rate >= 60) return 'high'
      if (rate >= 40) return 'mid'
      return 'low'
    },
    returnClass(rate) {
      if (rate > 0) return 'positive'
      if (rate < 0) return 'negative'
      return 'neutral'
    },
    formatWon(val) {
      if (!val) return '-'
      return Number(val).toLocaleString('ko-KR') + '원'
    },
    goToStock(code) {
      if (this.openStock) this.openStock(code)
      else this.$router.push(`/stock/${code}`)
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
  min-height: 300px;
}

.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.section-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}
.section-icon { margin-right: 6px; }

.period-btns { display: flex; gap: 4px; }
.period-btn {
  padding: 4px 12px;
  border: 1px solid rgba(255,255,255,0.1);
  background: transparent;
  border-radius: 8px;
  color: rgba(255,255,255,0.5);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.period-btn.active {
  background: rgba(102,126,234,0.2);
  border-color: #667eea;
  color: #8b9cf7;
}

.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px;
  text-align: center;
}
.state-icon { font-size: 36px; margin-bottom: 12px; opacity: 0.6; }
.state-text { font-size: 14px; color: rgba(255,255,255,0.4); margin: 0; }
.state-btn {
  margin-top: 12px;
  padding: 8px 20px;
  background: rgba(102,126,234,0.12);
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 10px;
  color: #667eea;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

/* Compare Section */
.compare-section {
  background: rgba(102,126,234,0.06);
  border: 1px solid rgba(102,126,234,0.15);
  border-radius: 14px;
  padding: 16px;
  margin-bottom: 16px;
}
.compare-header { margin-bottom: 12px; }
.compare-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(255,255,255,0.7);
}
.compare-grid {
  display: flex;
  gap: 0;
  align-items: stretch;
}
.compare-col { flex: 1; }
.compare-col-label {
  display: block;
  font-size: 12px;
  font-weight: 700;
  color: rgba(255,255,255,0.5);
  margin-bottom: 10px;
  text-align: center;
}
.compare-divider {
  width: 1px;
  background: rgba(255,255,255,0.1);
  margin: 0 12px;
  align-self: stretch;
}
.compare-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 8px;
}
.compare-metric {
  font-size: 11px;
  color: rgba(255,255,255,0.4);
}
.compare-val {
  font-size: 15px;
  font-weight: 800;
}
.compare-val.high, .compare-val.positive { color: #ef4444; }
.compare-val.mid { color: #f59e0b; }
.compare-val.low, .compare-val.negative { color: #3b82f6; }
.compare-val.neutral { color: rgba(255,255,255,0.7); }
.compare-detail-row {
  display: flex;
  gap: 6px;
  margin-top: 10px;
  flex-wrap: wrap;
  justify-content: center;
}
.detail-chip {
  font-size: 10px;
  padding: 3px 8px;
  border-radius: 6px;
  background: rgba(255,255,255,0.05);
  color: rgba(255,255,255,0.45);
}

/* Overall */
.overall-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.stat-box {
  flex: 1;
  padding: 12px;
  background: rgba(255,255,255,0.04);
  border-radius: 12px;
  text-align: center;
}
.stat-label {
  display: block;
  font-size: 11px;
  color: rgba(255,255,255,0.4);
  margin-bottom: 4px;
}
.stat-value {
  font-size: 18px;
  font-weight: 800;
}
.stat-value.high, .stat-value.positive { color: #ef4444; }
.stat-value.mid { color: #f59e0b; }
.stat-value.low, .stat-value.negative { color: #3b82f6; }
.stat-value.neutral { color: rgba(255,255,255,0.7); }

/* Strategy Cards */
.strategy-list { display: flex; flex-direction: column; gap: 8px; }
.strategy-card {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 12px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.strategy-card:hover { background: rgba(255,255,255,0.06); }

.strategy-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.strategy-icon { font-size: 16px; }
.strategy-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.9); }
.strategy-stats { margin-left: auto; display: flex; gap: 6px; }
.expand-arrow { font-size: 10px; color: rgba(255,255,255,0.3); margin-left: 4px; }

.hit-badge, .return-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
}
.hit-badge.high { background: rgba(239,68,68,0.15); color: #ef4444; }
.hit-badge.mid { background: rgba(245,158,11,0.15); color: #f59e0b; }
.hit-badge.low { background: rgba(107,114,128,0.15); color: #9ca3af; }
.return-badge.positive { background: rgba(239,68,68,0.15); color: #ef4444; }
.return-badge.negative { background: rgba(59,130,246,0.15); color: #3b82f6; }
.return-badge.neutral { background: rgba(107,114,128,0.15); color: #9ca3af; }

.strategy-meta {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  font-size: 11px;
  color: rgba(255,255,255,0.35);
}

/* Picks */
.picks-list {
  margin-top: 10px;
  border-top: 1px solid rgba(255,255,255,0.06);
  padding-top: 8px;
}
.pick-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}
.pick-row:hover { background: rgba(255,255,255,0.05); }
.pick-info { flex: 1; min-width: 0; }
.pick-name { font-size: 13px; color: rgba(255,255,255,0.8); }
.pick-code { font-size: 10px; color: rgba(255,255,255,0.3); margin-left: 4px; }
.pick-prices { font-size: 11px; color: rgba(255,255,255,0.4); }
.pick-rec { margin-right: 2px; }
.pick-cur { color: rgba(255,255,255,0.6); }
.pick-return { font-size: 12px; font-weight: 700; width: 60px; text-align: right; flex-shrink: 0; }
.pick-return.positive { color: #ef4444; }
.pick-return.negative { color: #3b82f6; }

/* Best / Worst */
.best-worst {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(255,255,255,0.06);
}
.bw-item { font-size: 11px; }
.bw-item.best { color: #ef4444; }
.bw-item.worst { color: #3b82f6; }

@media (max-width: 768px) {
  .section-card { padding: 16px; border-radius: 14px; }
  .section-title-row { flex-direction: column; align-items: flex-start; gap: 10px; }
  .overall-row { flex-direction: column; gap: 6px; }
  .stat-box { padding: 10px; }
  .stat-value { font-size: 16px; }
  .strategy-header { flex-wrap: wrap; }
  .strategy-stats { margin-left: 0; }
  .pick-row { flex-wrap: wrap; gap: 4px; }
  .pick-prices { font-size: 10px; }
  .best-worst { flex-direction: column; gap: 4px; }
  .compare-section { padding: 12px; }
  .compare-val { font-size: 13px; }
  .compare-detail-row { gap: 4px; }
}
</style>
