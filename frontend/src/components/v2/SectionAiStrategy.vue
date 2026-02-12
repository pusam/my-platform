<template>
  <div class="section-card">
    <div class="section-title-row">
      <h2><span class="section-icon">🤖</span> AI 트레이딩 전략</h2>
      <router-link to="/ai-strategy" class="more-link">전체 보기 →</router-link>
    </div>

    <SkeletonLoader v-if="loading" type="score" />

    <div v-else-if="error" class="section-error">
      <span>데이터를 불러올 수 없습니다.</span>
    </div>

    <template v-else>
      <!-- 전략 점수바 -->
      <div class="strategy-scores">
        <div
          v-for="st in strategyList"
          :key="st.key"
          class="score-row"
          :class="{ active: activeTab === st.key }"
          @click="activeTab = st.key"
        >
          <span class="score-label">{{ st.icon }} {{ st.label }}</span>
          <div class="score-bar-bg">
            <div class="score-bar-fill" :class="st.key" :style="{ width: scores[st.key] + '%' }"></div>
          </div>
          <span class="score-value">{{ scores[st.key] }}</span>
        </div>
      </div>

      <!-- 종합 점수 -->
      <div class="total-row">
        <span class="total-label">종합</span>
        <span class="total-value" :class="totalClass">{{ totalScore }}</span>
        <span class="total-opinion" :class="totalClass">{{ totalOpinion }}</span>
      </div>

      <!-- TOP 3 카드 -->
      <div class="top-cards">
        <div
          v-for="(stock, i) in topStocks"
          :key="stock.stockCode"
          class="stock-card"
          @click="goToStock(stock.stockCode)"
        >
          <div class="rank" :class="'rank-' + (i + 1)">{{ i === 0 ? '👑' : '#' + (i + 1) }}</div>
          <div class="stock-main">
            <div class="stock-name-row">
              <span class="stock-name">{{ stock.stockName }}</span>
              <span class="stock-code">{{ stock.stockCode }}</span>
            </div>
            <div class="stock-price-row">
              <span class="price">{{ formatPrice(stock.currentPrice) }}</span>
              <span class="change" :class="stock.changeRate >= 0 ? 'up' : 'down'">
                {{ stock.changeRate >= 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) }}%
              </span>
            </div>
            <!-- AI 점수 뱃지 -->
            <div class="ai-row" v-if="stock.aiScore">
              <span class="ai-badge" :class="getAiClass(stock.aiScore)">AI {{ stock.aiScore }}</span>
              <span class="ai-comment" v-if="stock.aiComment">{{ stock.aiComment }}</span>
            </div>
            <!-- 테마 태그 -->
            <div class="theme-tags" v-if="stock.aiThemes">
              <span class="theme-tag" v-for="tag in parseThemes(stock.aiThemes)" :key="tag">{{ tag }}</span>
            </div>
          </div>
        </div>
        <div v-if="topStocks.length === 0" class="empty-msg">추천 종목이 없습니다.</div>
      </div>
    </template>
  </div>
</template>

<script>
import SkeletonLoader from './SkeletonLoader.vue'

export default {
  name: 'SectionAiStrategy',
  components: { SkeletonLoader },
  props: {
    data: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    error: { type: Boolean, default: false }
  },
  data() {
    return {
      activeTab: 'scalping',
      strategyList: [
        { key: 'scalping', label: '스캘핑', icon: '⚡' },
        { key: 'swing', label: '스윙', icon: '📈' },
        { key: 'turnaround', label: '턴어라운드', icon: '🔄' },
        { key: 'value', label: '가치투자', icon: '💎' }
      ]
    }
  },
  computed: {
    strategies() {
      return this.data?.strategies || {}
    },
    scores() {
      const s = {}
      for (const st of this.strategyList) {
        const key = st.key.toUpperCase()
        const list = this.strategies[key] || []
        s[st.key] = list.length > 0 ? Math.round(list.reduce((sum, x) => sum + (x.score || 0), 0) / list.length) : 0
      }
      return s
    },
    totalScore() {
      const vals = Object.values(this.scores)
      return vals.length > 0 ? Math.round(vals.reduce((a, b) => a + b, 0) / vals.length) : 0
    },
    totalOpinion() {
      if (this.totalScore >= 70) return '적극 매수'
      if (this.totalScore >= 50) return '매수'
      if (this.totalScore >= 30) return '관망'
      return '매도'
    },
    totalClass() {
      if (this.totalScore >= 70) return 'strong-buy'
      if (this.totalScore >= 50) return 'buy'
      if (this.totalScore >= 30) return 'neutral'
      return 'sell'
    },
    topStocks() {
      const key = this.activeTab.toUpperCase()
      return (this.strategies[key] || []).slice(0, 3)
    }
  },
  methods: {
    formatPrice(val) {
      if (!val) return '-'
      return Number(val).toLocaleString('ko-KR') + '원'
    },
    getAiClass(score) {
      if (score >= 70) return 'ai-high'
      if (score >= 50) return 'ai-mid'
      return 'ai-low'
    },
    parseThemes(themes) {
      if (!themes) return []
      return themes.split(',').map(t => t.trim()).filter(Boolean)
    },
    goToStock(code) {
      this.$router.push(`/stock/${code}`)
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
  margin-bottom: 20px;
}

.section-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}

.section-icon { margin-right: 6px; }

.more-link {
  font-size: 13px;
  color: #667eea;
  text-decoration: none;
  transition: color 0.2s;
}
.more-link:hover { color: #8b9cf7; }

.section-error {
  text-align: center;
  color: rgba(255,255,255,0.4);
  padding: 40px 0;
  font-size: 14px;
}

/* Strategy Scores */
.strategy-scores { margin-bottom: 12px; }

.score-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  margin-bottom: 4px;
}
.score-row:hover, .score-row.active {
  background: rgba(255,255,255,0.06);
}

.score-label {
  font-size: 13px;
  color: rgba(255,255,255,0.7);
  width: 90px;
  flex-shrink: 0;
}

.score-bar-bg {
  flex: 1;
  height: 6px;
  background: rgba(255,255,255,0.08);
  border-radius: 3px;
  overflow: hidden;
}

.score-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.6s ease;
}
.score-bar-fill.scalping { background: linear-gradient(90deg, #f59e0b, #ef4444); }
.score-bar-fill.swing { background: linear-gradient(90deg, #3b82f6, #667eea); }
.score-bar-fill.turnaround { background: linear-gradient(90deg, #10b981, #06b6d4); }
.score-bar-fill.value { background: linear-gradient(90deg, #8b5cf6, #a855f7); }

.score-value {
  font-size: 13px;
  font-weight: 700;
  color: rgba(255,255,255,0.9);
  width: 28px;
  text-align: right;
}

/* Total */
.total-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: rgba(255,255,255,0.04);
  border-radius: 10px;
  margin-bottom: 16px;
}
.total-label { font-size: 13px; color: rgba(255,255,255,0.5); }
.total-value { font-size: 22px; font-weight: 800; }
.total-opinion { font-size: 13px; font-weight: 600; margin-left: auto; }
.strong-buy { color: #ef4444; }
.buy { color: #f59e0b; }
.neutral { color: #6b7280; }
.sell { color: #3b82f6; }

/* TOP Cards */
.top-cards { display: flex; flex-direction: column; gap: 8px; }

.stock-card {
  display: flex;
  gap: 12px;
  padding: 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.stock-card:hover {
  background: rgba(255,255,255,0.07);
  border-color: rgba(255,255,255,0.12);
}

.rank {
  font-size: 14px;
  font-weight: 700;
  width: 32px;
  text-align: center;
  flex-shrink: 0;
  padding-top: 2px;
}
.rank-1 { color: #ffd700; }
.rank-2 { color: #c0c0c0; }
.rank-3 { color: #cd7f32; }

.stock-main { flex: 1; min-width: 0; }

.stock-name-row { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.stock-name { font-size: 14px; font-weight: 600; color: rgba(255,255,255,0.9); }
.stock-code { font-size: 11px; color: rgba(255,255,255,0.35); }

.stock-price-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.price { font-size: 13px; color: rgba(255,255,255,0.7); }
.change { font-size: 12px; font-weight: 600; }
.change.up { color: #ef4444; }
.change.down { color: #3b82f6; }

.ai-row { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.ai-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
}
.ai-high { background: rgba(239,68,68,0.2); color: #ef4444; }
.ai-mid { background: rgba(245,158,11,0.2); color: #f59e0b; }
.ai-low { background: rgba(107,114,128,0.2); color: #9ca3af; }
.ai-comment { font-size: 11px; color: rgba(255,255,255,0.5); }

.theme-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.theme-tag {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(102,126,234,0.15);
  color: #8b9cf7;
}

.empty-msg {
  text-align: center;
  color: rgba(255,255,255,0.3);
  font-size: 13px;
  padding: 20px 0;
}
</style>
