<template>
  <teleport to="body">
    <!-- Overlay -->
    <transition name="fade">
      <div v-if="visible" class="drawer-overlay" @click="$emit('close')"></div>
    </transition>

    <!-- Drawer Panel -->
    <div :class="['stock-drawer', { open: visible }]">
      <!-- Loading -->
      <div v-if="loading" class="drawer-loading">
        <div class="spinner"></div>
        <p>데이터 로딩 중...</p>
      </div>

      <template v-else-if="stockData">
        <!-- Header -->
        <div class="drawer-header">
          <div class="drawer-stock-info">
            <h2 class="drawer-stock-name">{{ stockData.stockName || stockCode }}</h2>
            <span class="drawer-stock-code">{{ stockCode }}</span>
          </div>
          <button class="drawer-close" @click="$emit('close')">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>

        <!-- Price -->
        <div class="drawer-price-section">
          <span class="drawer-current-price">{{ formatPrice(stockData.currentPrice) }}원</span>
          <span class="drawer-change" :class="changeClass">
            {{ stockData.changeRate >= 0 ? '+' : '' }}{{ stockData.changeRate?.toFixed(2) }}%
            <small>({{ stockData.changePrice >= 0 ? '+' : '' }}{{ formatPrice(stockData.changePrice) }})</small>
          </span>
        </div>

        <!-- AI Scores -->
        <div class="drawer-scores" v-if="stockData.tradingScore || stockData.fundamentalScore">
          <div class="score-pill" :class="getScoreClass(stockData.tradingScore)">
            <span class="score-label">단기</span>
            <span class="score-val">{{ stockData.tradingScore || '-' }}</span>
          </div>
          <div class="score-pill" :class="getScoreClass(stockData.fundamentalScore)">
            <span class="score-label">중장기</span>
            <span class="score-val">{{ stockData.fundamentalScore || '-' }}</span>
          </div>
          <span class="score-rec" v-if="stockData.recommendation">{{ getRecLabel(stockData.recommendation) }}</span>
        </div>

        <!-- Key Financials -->
        <div class="drawer-section">
          <h3 class="drawer-section-title">핵심 재무</h3>
          <div class="fin-grid">
            <div class="fin-item" v-if="stockData.per">
              <span class="fin-label">PER</span>
              <span class="fin-value">{{ Number(stockData.per).toFixed(1) }}배</span>
            </div>
            <div class="fin-item" v-if="stockData.pbr">
              <span class="fin-label">PBR</span>
              <span class="fin-value">{{ Number(stockData.pbr).toFixed(2) }}배</span>
            </div>
            <div class="fin-item" v-if="stockData.eps">
              <span class="fin-label">EPS</span>
              <span class="fin-value">{{ formatPrice(stockData.eps) }}원</span>
            </div>
            <div class="fin-item" v-if="stockData.marketCap">
              <span class="fin-label">시가총액</span>
              <span class="fin-value">{{ formatMarketCap(stockData.marketCap) }}</span>
            </div>
            <div class="fin-item" v-if="stockData.roe">
              <span class="fin-label">ROE</span>
              <span class="fin-value">{{ Number(stockData.roe).toFixed(1) }}%</span>
            </div>
            <div class="fin-item" v-if="stockData.operatingMargin">
              <span class="fin-label">영업이익률</span>
              <span class="fin-value">{{ Number(stockData.operatingMargin).toFixed(1) }}%</span>
            </div>
          </div>
        </div>

        <!-- Supply/Demand -->
        <div class="drawer-section" v-if="stockData.foreignNetBuy != null || stockData.instNetBuy != null">
          <h3 class="drawer-section-title">투자자별 수급</h3>
          <div class="supply-bars">
            <div class="supply-row" v-if="stockData.foreignNetBuy != null">
              <span class="supply-label">외국인</span>
              <div class="supply-bar-track">
                <div
                  class="supply-bar-fill"
                  :class="stockData.foreignNetBuy >= 0 ? 'positive' : 'negative'"
                  :style="{ width: getBarWidth(stockData.foreignNetBuy) + '%' }"
                ></div>
              </div>
              <span class="supply-value" :class="stockData.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ stockData.foreignNetBuy >= 0 ? '+' : '' }}{{ stockData.foreignNetBuy?.toFixed(0) || 0 }}억
              </span>
            </div>
            <div class="supply-row" v-if="stockData.instNetBuy != null">
              <span class="supply-label">기관</span>
              <div class="supply-bar-track">
                <div
                  class="supply-bar-fill"
                  :class="stockData.instNetBuy >= 0 ? 'positive' : 'negative'"
                  :style="{ width: getBarWidth(stockData.instNetBuy) + '%' }"
                ></div>
              </div>
              <span class="supply-value" :class="stockData.instNetBuy >= 0 ? 'positive' : 'negative'">
                {{ stockData.instNetBuy >= 0 ? '+' : '' }}{{ stockData.instNetBuy?.toFixed(0) || 0 }}억
              </span>
            </div>
            <div class="supply-row" v-if="stockData.programNetBuy != null">
              <span class="supply-label">프로그램</span>
              <div class="supply-bar-track">
                <div
                  class="supply-bar-fill"
                  :class="stockData.programNetBuy >= 0 ? 'positive' : 'negative'"
                  :style="{ width: getBarWidth(stockData.programNetBuy) + '%' }"
                ></div>
              </div>
              <span class="supply-value" :class="stockData.programNetBuy >= 0 ? 'positive' : 'negative'">
                {{ stockData.programNetBuy >= 0 ? '+' : '' }}{{ stockData.programNetBuy?.toFixed(0) || 0 }}억
              </span>
            </div>
          </div>
        </div>

        <!-- Volume Power -->
        <div class="drawer-section" v-if="stockData.volumePower">
          <h3 class="drawer-section-title">체결강도</h3>
          <div class="volume-power-row">
            <div class="volume-bar-track">
              <div
                class="volume-bar-fill"
                :class="volumePowerClass"
                :style="{ width: Math.min(stockData.volumePower, 200) / 2 + '%' }"
              ></div>
            </div>
            <span class="volume-power-value" :class="volumePowerClass">
              {{ stockData.volumePower?.toFixed(0) }}%
            </span>
          </div>
        </div>

        <!-- Risk Keywords -->
        <div class="drawer-section" v-if="stockData.riskKeywords && stockData.riskKeywords.length">
          <h3 class="drawer-section-title">리스크 키워드</h3>
          <div class="risk-tags">
            <span v-for="kw in stockData.riskKeywords" :key="kw" class="risk-tag">{{ kw }}</span>
          </div>
        </div>

        <!-- AI Comment -->
        <div class="drawer-section" v-if="stockData.aiComment">
          <h3 class="drawer-section-title">AI 코멘트</h3>
          <p class="ai-comment-text">{{ stockData.aiComment }}</p>
        </div>

        <!-- Full Detail Link -->
        <div class="drawer-footer">
          <button class="detail-link-btn" @click="goToDetail">
            상세 분석 보기
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </button>
        </div>
      </template>

      <!-- Error -->
      <div v-else-if="error" class="drawer-error">
        <p>데이터를 불러올 수 없습니다</p>
        <button @click="loadStockData(stockCode)">재시도</button>
      </div>
    </div>
  </teleport>
</template>

<script>
import { stockDetailAPI } from '@/utils/api'

export default {
  name: 'StockDrawer',
  props: {
    visible: { type: Boolean, default: false },
    stockCode: { type: String, default: null }
  },
  emits: ['close'],
  data() {
    return {
      loading: false,
      error: false,
      stockData: null
    }
  },
  computed: {
    changeClass() {
      if (!this.stockData) return ''
      return this.stockData.changeRate >= 0 ? 'up' : 'down'
    },
    volumePowerClass() {
      if (!this.stockData?.volumePower) return ''
      const vp = this.stockData.volumePower
      if (vp >= 120) return 'strong'
      if (vp >= 80) return 'normal'
      return 'weak'
    }
  },
  watch: {
    stockCode(code) {
      if (code && this.visible) this.loadStockData(code)
    },
    visible(v) {
      if (v && this.stockCode) this.loadStockData(this.stockCode)
      if (v) {
        this._onEsc = (e) => { if (e.key === 'Escape') this.$emit('close') }
        window.addEventListener('keydown', this._onEsc)
      } else {
        if (this._onEsc) window.removeEventListener('keydown', this._onEsc)
      }
    }
  },
  beforeUnmount() {
    if (this._onEsc) window.removeEventListener('keydown', this._onEsc)
  },
  methods: {
    async loadStockData(code) {
      if (!code) return
      this.loading = true
      this.error = false
      this.stockData = null
      try {
        const res = await stockDetailAPI.getSummary(code)
        const d = res?.data?.data || res?.data
        if (d) {
          this.stockData = this.transformSummary(d)
        } else {
          this.error = true
        }
      } catch (e) {
        console.warn('[StockDrawer] 데이터 로딩 실패:', e.message)
        this.error = true
      } finally {
        this.loading = false
      }
    },

    transformSummary(d) {
      // StockDetailDto 필드: price, financial, supplyDemand, aiAnalysis, risk
      const price = d.price || d.priceInfo || d
      const financial = d.financial || d.financialInfo || d
      const supply = d.supplyDemand || d
      const ai = d.aiAnalysis || d
      const risk = d.risk || d.riskInfo || d

      return {
        stockName: d.stockName || price.stockName,
        currentPrice: price.currentPrice,
        changeRate: Number(price.changeRate) || 0,
        changePrice: Number(price.changePrice) || 0,
        // Financial
        per: financial.per,
        pbr: financial.pbr,
        eps: financial.eps,
        marketCap: financial.marketCap,
        roe: financial.roe,
        operatingMargin: financial.operatingMargin,
        // Supply
        foreignNetBuy: supply.foreignNetBuy,
        instNetBuy: supply.instNetBuy,
        programNetBuy: supply.programNetBuy,
        volumePower: supply.volumePower,
        // AI
        tradingScore: ai.overallScore || ai.tradingScore,
        fundamentalScore: d.diagnosisData?.overallScore || ai.fundamentalScore,
        recommendation: ai.recommendation,
        aiComment: ai.strategy || ai.comment || ai.aiComment,
        // Risk
        riskKeywords: risk.riskTags || risk.riskKeywords || []
      }
    },

    formatPrice(val) {
      if (!val && val !== 0) return '-'
      return Number(val).toLocaleString('ko-KR')
    },

    formatMarketCap(val) {
      if (!val) return '-'
      const v = Number(val)
      if (v >= 10000) return (v / 10000).toFixed(1) + '조'
      return v.toLocaleString('ko-KR') + '억'
    },

    getScoreClass(score) {
      if (!score) return ''
      if (score >= 70) return 'score-high'
      if (score >= 50) return 'score-mid'
      return 'score-low'
    },

    getRecLabel(rec) {
      const map = {
        'STRONG_BUY': '적극 매수',
        'BUY': '매수',
        'HOLD': '관망',
        'SELL': '매도',
        'STRONG_SELL': '적극 매도'
      }
      return map[rec] || rec || ''
    },

    getBarWidth(val) {
      if (!val) return 0
      const maxVal = 100
      return Math.min(Math.abs(val) / maxVal * 100, 100)
    },

    goToDetail() {
      this.$emit('close')
      this.$router.push(`/stock/${this.stockCode}`)
    }
  }
}
</script>

<style scoped>
/* Overlay */
.drawer-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0,0,0,0.45);
}

.fade-enter-active, .fade-leave-active { transition: opacity 0.3s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* Drawer */
.stock-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 45%;
  min-width: 400px;
  max-width: 700px;
  background: linear-gradient(180deg, #0f0f1a 0%, #1a1a2e 60%, #16213e 100%);
  border-left: 1px solid rgba(255,255,255,0.1);
  transform: translateX(100%);
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow-y: auto;
  z-index: 1001;
  padding: 24px;
  color: white;
}
.stock-drawer.open {
  transform: translateX(0);
}

/* Loading */
.drawer-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 300px;
  color: rgba(255,255,255,0.5);
}
.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid rgba(255,255,255,0.1);
  border-top: 3px solid #667eea;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 12px;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Header */
.drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
.drawer-stock-name {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: rgba(255,255,255,0.95);
}
.drawer-stock-code {
  font-size: 12px;
  color: rgba(255,255,255,0.4);
  margin-top: 2px;
}
.drawer-close {
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px;
  color: rgba(255,255,255,0.5);
  cursor: pointer;
  padding: 6px;
  display: flex;
  transition: all 0.2s;
}
.drawer-close:hover {
  background: rgba(255,255,255,0.12);
  color: white;
}

/* Price */
.drawer-price-section {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.drawer-current-price {
  font-size: 28px;
  font-weight: 800;
  color: rgba(255,255,255,0.95);
}
.drawer-change {
  font-size: 15px;
  font-weight: 600;
}
.drawer-change.up { color: #ef4444; }
.drawer-change.down { color: #3b82f6; }
.drawer-change small {
  font-size: 12px;
  opacity: 0.7;
  margin-left: 4px;
}

/* Scores */
.drawer-scores {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
}
.score-pill {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 20px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.08);
}
.score-pill.score-high { background: rgba(239,68,68,0.12); border-color: rgba(239,68,68,0.25); }
.score-pill.score-mid { background: rgba(245,158,11,0.12); border-color: rgba(245,158,11,0.25); }
.score-pill.score-low { background: rgba(107,114,128,0.12); border-color: rgba(107,114,128,0.25); }
.score-label { font-size: 11px; color: rgba(255,255,255,0.5); }
.score-val { font-size: 16px; font-weight: 800; color: rgba(255,255,255,0.9); }
.score-pill.score-high .score-val { color: #ef4444; }
.score-pill.score-mid .score-val { color: #f59e0b; }
.score-pill.score-low .score-val { color: #9ca3af; }
.score-rec {
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,0.6);
  margin-left: auto;
}

/* Section */
.drawer-section {
  margin-bottom: 20px;
  padding-top: 16px;
  border-top: 1px solid rgba(255,255,255,0.06);
}
.drawer-section-title {
  margin: 0 0 12px 0;
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,0.5);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* Financial Grid */
.fin-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.fin-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 10px;
  background: rgba(255,255,255,0.03);
  border-radius: 8px;
}
.fin-label {
  font-size: 12px;
  color: rgba(255,255,255,0.4);
}
.fin-value {
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,0.85);
}

/* Supply Bars */
.supply-bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.supply-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.supply-label {
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  width: 50px;
  flex-shrink: 0;
}
.supply-bar-track {
  flex: 1;
  height: 8px;
  background: rgba(255,255,255,0.06);
  border-radius: 4px;
  overflow: hidden;
}
.supply-bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s ease;
}
.supply-bar-fill.positive { background: linear-gradient(90deg, #ef444480, #ef4444); }
.supply-bar-fill.negative { background: linear-gradient(90deg, #3b82f680, #3b82f6); }
.supply-value {
  font-size: 12px;
  font-weight: 600;
  width: 60px;
  text-align: right;
  flex-shrink: 0;
}
.supply-value.positive { color: #ef4444; }
.supply-value.negative { color: #3b82f6; }

/* Volume Power */
.volume-power-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.volume-bar-track {
  flex: 1;
  height: 10px;
  background: rgba(255,255,255,0.06);
  border-radius: 5px;
  overflow: hidden;
}
.volume-bar-fill {
  height: 100%;
  border-radius: 5px;
  transition: width 0.5s ease;
}
.volume-bar-fill.strong { background: linear-gradient(90deg, #f59e0b, #ef4444); }
.volume-bar-fill.normal { background: linear-gradient(90deg, #667eea, #3b82f6); }
.volume-bar-fill.weak { background: linear-gradient(90deg, #6b7280, #4b5563); }
.volume-power-value {
  font-size: 16px;
  font-weight: 700;
  width: 50px;
  text-align: right;
}
.volume-power-value.strong { color: #ef4444; }
.volume-power-value.normal { color: #3b82f6; }
.volume-power-value.weak { color: #6b7280; }

/* Risk Tags */
.risk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.risk-tag {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(239,68,68,0.12);
  color: #fca5a5;
  border: 1px solid rgba(239,68,68,0.2);
}

/* AI Comment */
.ai-comment-text {
  font-size: 13px;
  color: rgba(255,255,255,0.65);
  line-height: 1.6;
  margin: 0;
}

/* Footer */
.drawer-footer {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid rgba(255,255,255,0.06);
}
.detail-link-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  padding: 12px;
  background: rgba(102,126,234,0.12);
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 12px;
  color: #a5b4fc;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.detail-link-btn:hover {
  background: rgba(102,126,234,0.22);
  border-color: #667eea;
}

/* Error */
.drawer-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 200px;
  gap: 12px;
}
.drawer-error p {
  color: rgba(255,255,255,0.4);
  font-size: 14px;
}
.drawer-error button {
  padding: 8px 20px;
  background: rgba(102,126,234,0.12);
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 8px;
  color: #667eea;
  font-size: 13px;
  cursor: pointer;
}

@media (max-width: 768px) {
  .stock-drawer {
    width: 100%;
    min-width: unset;
  }
}
</style>
