<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>트레이딩 지표</h1>
      </header>

      <!-- 글로벌 시장 섹션 -->
      <section class="indicator-section">
        <div class="section-header">
          <span class="section-icon">🌍</span>
          <h2>글로벌 시장</h2>
          <button @click="loadGlobalMarket" class="refresh-btn" :disabled="loading.global">
            <span v-if="loading.global">로딩...</span>
            <span v-else>새로고침</span>
          </button>
        </div>

        <div class="global-market-grid">
          <!-- 나스닥 선물 -->
          <div class="market-card" :class="getSignalClass(nasdaqFutures?.signal)">
            <div class="market-header">
              <h3>나스닥 100 선물</h3>
              <span class="symbol">NQ=F</span>
            </div>
            <div v-if="nasdaqFutures" class="market-content">
              <div class="price">{{ formatNumber(nasdaqFutures.price) }}</div>
              <div class="change" :class="nasdaqFutures.changeRate >= 0 ? 'positive' : 'negative'">
                {{ nasdaqFutures.changeRate >= 0 ? '+' : '' }}{{ nasdaqFutures.changeRate?.toFixed(2) }}%
              </div>
              <div class="signal-badge" :class="getSignalClass(nasdaqFutures.signal)">
                {{ getSignalText(nasdaqFutures.signal) }}
              </div>
              <p class="interpretation">{{ nasdaqFutures.interpretation }}</p>
              <div v-if="nasdaqFutures.isTradingHalt" class="halt-warning">
                🚫 매수 보류 권장
              </div>
            </div>
            <div v-else class="no-data">데이터 없음</div>
          </div>

          <!-- S&P 500 선물 -->
          <div class="market-card" :class="getSignalClass(sp500Futures?.signal)">
            <div class="market-header">
              <h3>S&P 500 선물</h3>
              <span class="symbol">ES=F</span>
            </div>
            <div v-if="sp500Futures" class="market-content">
              <div class="price">{{ formatNumber(sp500Futures.price) }}</div>
              <div class="change" :class="sp500Futures.changeRate >= 0 ? 'positive' : 'negative'">
                {{ sp500Futures.changeRate >= 0 ? '+' : '' }}{{ sp500Futures.changeRate?.toFixed(2) }}%
              </div>
              <div class="signal-badge" :class="getSignalClass(sp500Futures.signal)">
                {{ getSignalText(sp500Futures.signal) }}
              </div>
            </div>
            <div v-else class="no-data">데이터 없음</div>
          </div>

          <!-- 글로벌 악재 필터 -->
          <div class="market-card halt-check" :class="haltCheck?.shouldHaltBuying ? 'danger' : 'safe'">
            <div class="market-header">
              <h3>글로벌 악재 필터</h3>
            </div>
            <div class="market-content">
              <div class="halt-status">
                <span v-if="haltCheck?.shouldHaltBuying" class="status-danger">⚠️ 매수 보류</span>
                <span v-else class="status-safe">✅ 매수 가능</span>
              </div>
              <p class="halt-message">{{ haltCheck?.message }}</p>
            </div>
          </div>
        </div>
      </section>

      <!-- 주도 섹터 섹션 -->
      <section class="indicator-section">
        <div class="section-header">
          <span class="section-icon">📊</span>
          <h2>주도 섹터 랭킹</h2>
          <button @click="loadLeadingSectors" class="refresh-btn" :disabled="loading.sectors">
            <span v-if="loading.sectors">로딩...</span>
            <span v-else>새로고침</span>
          </button>
        </div>

        <div v-if="leadingSectors" class="sectors-container">
          <p class="sector-interpretation">{{ leadingSectors.interpretation }}</p>

          <div class="sectors-grid">
            <!-- 상위 섹터 -->
            <div class="sector-column top-sectors">
              <h4>📈 상위 섹터</h4>
              <div v-for="sector in leadingSectors.topSectors" :key="sector.sectorCode" class="sector-card top">
                <div class="sector-rank">#{{ sector.rank }}</div>
                <div class="sector-info">
                  <div class="sector-name">{{ sector.sectorName }}</div>
                  <div class="sector-change positive">+{{ sector.averageChangeRate?.toFixed(2) }}%</div>
                </div>
                <div class="leading-stock" v-if="sector.leadingStockName">
                  <span class="label">대장주:</span>
                  <span class="stock-name">{{ sector.leadingStockName }}</span>
                  <span class="stock-change" :class="sector.leadingStockChange >= 0 ? 'positive' : 'negative'">
                    {{ sector.leadingStockChange >= 0 ? '+' : '' }}{{ sector.leadingStockChange?.toFixed(2) }}%
                  </span>
                </div>
              </div>
            </div>

            <!-- 하위 섹터 -->
            <div class="sector-column bottom-sectors">
              <h4>📉 하위 섹터</h4>
              <div v-for="sector in leadingSectors.bottomSectors" :key="sector.sectorCode" class="sector-card bottom">
                <div class="sector-rank">#{{ sector.rank }}</div>
                <div class="sector-info">
                  <div class="sector-name">{{ sector.sectorName }}</div>
                  <div class="sector-change negative">{{ sector.averageChangeRate?.toFixed(2) }}%</div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="no-data-section">섹터 데이터를 불러오는 중...</div>
      </section>

      <!-- VWAP 분석 섹션 -->
      <section class="indicator-section">
        <div class="section-header">
          <span class="section-icon">📈</span>
          <h2>VWAP 분석</h2>
        </div>

        <div class="vwap-search">
          <input
            v-model="vwapStockCode"
            placeholder="종목코드 입력 (예: 005930)"
            @keyup.enter="loadVwap"
            class="stock-input"
          />
          <button @click="loadVwap" class="search-btn" :disabled="loading.vwap || !vwapStockCode">
            {{ loading.vwap ? '분석 중...' : 'VWAP 분석' }}
          </button>
        </div>

        <div v-if="vwapResult" class="vwap-result" :class="getVwapSignalClass(vwapResult.signal)">
          <div class="vwap-header">
            <h3>{{ vwapResult.stockName || vwapResult.stockCode }}</h3>
            <div class="vwap-signal" :class="getVwapSignalClass(vwapResult.signal)">
              {{ getVwapSignalText(vwapResult.signal) }}
            </div>
          </div>
          <div class="vwap-content">
            <div class="vwap-stat">
              <span class="label">현재가</span>
              <span class="value">{{ formatNumber(vwapResult.currentPrice) }}원</span>
            </div>
            <div class="vwap-stat">
              <span class="label">VWAP</span>
              <span class="value">{{ formatNumber(vwapResult.vwap) }}원</span>
            </div>
            <div class="vwap-stat">
              <span class="label">괴리율</span>
              <span class="value" :class="vwapResult.deviation >= 0 ? 'positive' : 'negative'">
                {{ vwapResult.deviation >= 0 ? '+' : '' }}{{ vwapResult.deviation?.toFixed(2) }}%
              </span>
            </div>
          </div>
          <p class="vwap-interpretation">{{ vwapResult.interpretation }}</p>
          <div v-if="!vwapResult.isReliable" class="reliability-warning">
            ⚠️ 장 초반 데이터로 신뢰도가 낮습니다
          </div>
        </div>
      </section>

      <!-- RSI 다이버전스 섹션 -->
      <section class="indicator-section">
        <div class="section-header">
          <span class="section-icon">📉</span>
          <h2>RSI 다이버전스 탐지</h2>
        </div>

        <div class="divergence-info">
          <p><strong>하락 다이버전스:</strong> 주가 신고가 + RSI 전고점 하회 → 매도 신호</p>
          <p><strong>상승 다이버전스:</strong> 주가 신저가 + RSI 전저점 상회 → 매수 신호</p>
        </div>

        <div class="divergence-search">
          <input
            v-model="divergenceStockCode"
            placeholder="종목코드 입력 (예: 005930)"
            class="stock-input"
          />
          <select v-model="divergenceLookback" class="lookback-select">
            <option :value="20">20일</option>
            <option :value="40">40일 (권장)</option>
            <option :value="60">60일</option>
          </select>
          <button @click="loadDivergence" class="search-btn" :disabled="loading.divergence || !divergenceStockCode">
            {{ loading.divergence ? '분석 중...' : '다이버전스 탐지' }}
          </button>
        </div>

        <div v-if="divergenceResult" class="divergence-result" :class="getDivergenceClass(divergenceResult.signal)">
          <div class="divergence-header">
            <h3>분석 결과</h3>
            <div class="divergence-signal" :class="getDivergenceClass(divergenceResult.signal)">
              {{ getDivergenceSignalText(divergenceResult.signal) }}
            </div>
          </div>
          <div class="divergence-content">
            <div class="divergence-stat">
              <span class="label">다이버전스 유형</span>
              <span class="value">{{ getDivergenceTypeText(divergenceResult.type) }}</span>
            </div>
            <div class="divergence-stat">
              <span class="label">현재 RSI</span>
              <span class="value" :class="getRsiClass(divergenceResult.currentRsi)">
                {{ divergenceResult.currentRsi?.toFixed(2) }}
              </span>
            </div>
            <div class="divergence-stat" v-if="divergenceResult.strength">
              <span class="label">신호 강도</span>
              <span class="value">{{ divergenceResult.strength }}</span>
            </div>
          </div>
          <p class="divergence-interpretation">{{ divergenceResult.interpretation }}</p>
        </div>
      </section>

      <!-- 종합 분석 섹션 -->
      <section class="indicator-section">
        <div class="section-header">
          <span class="section-icon">🎯</span>
          <h2>종합 분석</h2>
        </div>

        <div class="comprehensive-search">
          <input
            v-model="comprehensiveStockCode"
            placeholder="종목코드 입력 (예: 005930)"
            @keyup.enter="loadComprehensive"
            class="stock-input"
          />
          <button @click="loadComprehensive" class="search-btn" :disabled="loading.comprehensive || !comprehensiveStockCode">
            {{ loading.comprehensive ? '분석 중...' : '종합 분석' }}
          </button>
        </div>

        <div v-if="comprehensiveResult" class="comprehensive-result">
          <div class="score-section">
            <div class="score-circle" :class="getScoreClass(comprehensiveResult.overallScore)">
              <span class="score-value">{{ comprehensiveResult.overallScore }}</span>
              <span class="score-label">점</span>
            </div>
            <p class="recommendation">{{ comprehensiveResult.recommendation }}</p>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import { tradingIndicatorAPI } from '../utils/api'

export default {
  name: 'TradingIndicatorsPage',
  data() {
    return {
      loading: {
        global: false,
        sectors: false,
        vwap: false,
        divergence: false,
        comprehensive: false
      },
      nasdaqFutures: null,
      sp500Futures: null,
      haltCheck: null,
      leadingSectors: null,
      vwapStockCode: '',
      vwapResult: null,
      divergenceStockCode: '',
      divergenceLookback: 40,
      divergenceResult: null,
      comprehensiveStockCode: '',
      comprehensiveResult: null
    }
  },
  mounted() {
    this.loadGlobalMarket()
    this.loadLeadingSectors()
  },
  methods: {
    goBack() {
      this.$router.back()
    },
    async loadGlobalMarket() {
      this.loading.global = true
      try {
        const [nasdaqRes, sp500Res, haltRes] = await Promise.all([
          tradingIndicatorAPI.getNasdaqFutures(),
          tradingIndicatorAPI.getSP500Futures(),
          tradingIndicatorAPI.checkGlobalHalt()
        ])

        if (nasdaqRes.data.success) {
          this.nasdaqFutures = nasdaqRes.data.data
        }
        if (sp500Res.data.success) {
          this.sp500Futures = sp500Res.data.data
        }
        if (haltRes.data.success) {
          this.haltCheck = haltRes.data.data
        }
      } catch (error) {
        console.error('글로벌 시장 데이터 로드 실패:', error)
      } finally {
        this.loading.global = false
      }
    },
    async loadLeadingSectors() {
      this.loading.sectors = true
      try {
        const response = await tradingIndicatorAPI.getLeadingSectors()
        if (response.data.success) {
          this.leadingSectors = response.data.data
        }
      } catch (error) {
        console.error('주도 섹터 데이터 로드 실패:', error)
      } finally {
        this.loading.sectors = false
      }
    },
    async loadVwap() {
      if (!this.vwapStockCode) return
      this.loading.vwap = true
      try {
        const response = await tradingIndicatorAPI.getVwap(this.vwapStockCode)
        if (response.data.success) {
          this.vwapResult = response.data.data
        }
      } catch (error) {
        console.error('VWAP 분석 실패:', error)
      } finally {
        this.loading.vwap = false
      }
    },
    async loadDivergence() {
      if (!this.divergenceStockCode) return
      this.loading.divergence = true
      try {
        const response = await tradingIndicatorAPI.detectDivergenceByStock(
          this.divergenceStockCode,
          this.divergenceLookback
        )
        if (response.data.success) {
          this.divergenceResult = response.data.data
        }
      } catch (error) {
        console.error('RSI 다이버전스 분석 실패:', error)
      } finally {
        this.loading.divergence = false
      }
    },
    async loadComprehensive() {
      if (!this.comprehensiveStockCode) return
      this.loading.comprehensive = true
      try {
        const response = await tradingIndicatorAPI.getComprehensive(this.comprehensiveStockCode)
        if (response.data.success) {
          this.comprehensiveResult = response.data.data
        }
      } catch (error) {
        console.error('종합 분석 실패:', error)
      } finally {
        this.loading.comprehensive = false
      }
    },
    formatNumber(value) {
      if (value == null) return '-'
      return new Intl.NumberFormat('ko-KR').format(value)
    },
    getSignalClass(signal) {
      if (!signal) return ''
      switch (signal) {
        case 'POSITIVE': return 'signal-positive'
        case 'NEUTRAL': return 'signal-neutral'
        case 'CAUTION': return 'signal-caution'
        case 'NEGATIVE': return 'signal-negative'
        default: return ''
      }
    },
    getSignalText(signal) {
      if (!signal) return '-'
      switch (signal) {
        case 'POSITIVE': return '긍정'
        case 'NEUTRAL': return '중립'
        case 'CAUTION': return '주의'
        case 'NEGATIVE': return '부정'
        default: return signal
      }
    },
    getVwapSignalClass(signal) {
      if (!signal) return ''
      switch (signal) {
        case 'STRONG_BUY': return 'signal-strong-buy'
        case 'BUY': return 'signal-buy'
        case 'NEUTRAL': return 'signal-neutral'
        case 'SELL': return 'signal-sell'
        case 'STRONG_SELL': return 'signal-strong-sell'
        default: return ''
      }
    },
    getVwapSignalText(signal) {
      if (!signal) return '-'
      switch (signal) {
        case 'STRONG_BUY': return '강력 매수'
        case 'BUY': return '매수'
        case 'NEUTRAL': return '중립'
        case 'SELL': return '매도'
        case 'STRONG_SELL': return '강력 매도'
        default: return signal
      }
    },
    getScoreClass(score) {
      if (score >= 50) return 'score-high'
      if (score >= 20) return 'score-mid-high'
      if (score >= -20) return 'score-neutral'
      if (score >= -50) return 'score-mid-low'
      return 'score-low'
    },
    getDivergenceClass(signal) {
      if (!signal) return ''
      switch (signal) {
        case 'BEARISH': return 'divergence-bearish'
        case 'BULLISH': return 'divergence-bullish'
        case 'NONE': return 'divergence-none'
        default: return ''
      }
    },
    getDivergenceSignalText(signal) {
      if (!signal) return '-'
      switch (signal) {
        case 'BEARISH': return '하락 다이버전스 (매도)'
        case 'BULLISH': return '상승 다이버전스 (매수)'
        case 'NONE': return '다이버전스 없음'
        default: return signal
      }
    },
    getDivergenceTypeText(type) {
      if (!type) return '-'
      switch (type) {
        case 'BEARISH': return '하락 다이버전스'
        case 'BULLISH': return '상승 다이버전스'
        case 'NONE': return '없음'
        default: return type
      }
    },
    getRsiClass(rsi) {
      if (rsi == null) return ''
      if (rsi >= 70) return 'rsi-overbought'
      if (rsi <= 30) return 'rsi-oversold'
      return 'rsi-normal'
    }
  }
}
</script>

<style scoped>
.indicator-section {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e5e7eb;
}

.section-icon {
  font-size: 1.5rem;
}

.section-header h2 {
  flex: 1;
  font-size: 1.25rem;
  font-weight: 700;
  color: #1a1a2e;
  margin: 0;
}

.refresh-btn {
  padding: 8px 16px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.refresh-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 글로벌 시장 그리드 */
.global-market-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
}

.market-card {
  background: #f8fafc;
  border-radius: 12px;
  padding: 20px;
  border: 2px solid #e5e7eb;
  transition: all 0.3s ease;
}

.market-card.signal-positive {
  border-color: #10b981;
  background: linear-gradient(135deg, #ecfdf5 0%, #f0fdf4 100%);
}

.market-card.signal-negative {
  border-color: #ef4444;
  background: linear-gradient(135deg, #fef2f2 0%, #fff1f2 100%);
}

.market-card.signal-caution {
  border-color: #f59e0b;
  background: linear-gradient(135deg, #fffbeb 0%, #fef3c7 100%);
}

.market-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.market-header h3 {
  font-size: 1rem;
  font-weight: 600;
  color: #374151;
  margin: 0;
}

.symbol {
  font-size: 0.875rem;
  color: #6b7280;
  background: #e5e7eb;
  padding: 4px 8px;
  border-radius: 4px;
}

.price {
  font-size: 1.75rem;
  font-weight: 700;
  color: #1f2937;
  margin-bottom: 8px;
}

.change {
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 12px;
}

.change.positive {
  color: #ef4444;
}

.change.negative {
  color: #3b82f6;
}

.signal-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.875rem;
  font-weight: 600;
  margin-bottom: 12px;
}

.signal-badge.signal-positive {
  background: #10b981;
  color: white;
}

.signal-badge.signal-neutral {
  background: #6b7280;
  color: white;
}

.signal-badge.signal-caution {
  background: #f59e0b;
  color: white;
}

.signal-badge.signal-negative {
  background: #ef4444;
  color: white;
}

.interpretation {
  font-size: 0.875rem;
  color: #4b5563;
  line-height: 1.5;
  margin: 0;
}

.halt-warning {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #dc2626;
  font-weight: 600;
}

.halt-check.danger {
  border-color: #ef4444;
  background: linear-gradient(135deg, #fef2f2 0%, #fff1f2 100%);
}

.halt-check.safe {
  border-color: #10b981;
  background: linear-gradient(135deg, #ecfdf5 0%, #f0fdf4 100%);
}

.halt-status {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 12px;
}

.status-danger {
  color: #dc2626;
}

.status-safe {
  color: #059669;
}

.halt-message {
  font-size: 0.875rem;
  color: #4b5563;
  margin: 0;
}

/* 섹터 */
.sector-interpretation {
  font-size: 1rem;
  color: #374151;
  margin-bottom: 20px;
  padding: 12px;
  background: #f3f4f6;
  border-radius: 8px;
}

.sectors-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 24px;
}

.sector-column h4 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: 12px;
  color: #374151;
}

.sector-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border-radius: 12px;
  margin-bottom: 12px;
}

.sector-card.top {
  background: linear-gradient(135deg, #fef2f2 0%, #fff1f2 100%);
  border: 1px solid #fecaca;
}

.sector-card.bottom {
  background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%);
  border: 1px solid #bfdbfe;
}

.sector-rank {
  font-size: 1.25rem;
  font-weight: 700;
  color: #6b7280;
  min-width: 40px;
}

.sector-info {
  flex: 1;
}

.sector-name {
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 4px;
}

.sector-change {
  font-size: 1.125rem;
  font-weight: 700;
}

.sector-change.positive {
  color: #ef4444;
}

.sector-change.negative {
  color: #3b82f6;
}

.leading-stock {
  width: 100%;
  font-size: 0.875rem;
  color: #6b7280;
  padding-top: 8px;
  border-top: 1px solid rgba(0, 0, 0, 0.1);
}

.leading-stock .label {
  margin-right: 4px;
}

.leading-stock .stock-name {
  font-weight: 600;
  color: #374151;
  margin-right: 8px;
}

.leading-stock .stock-change.positive {
  color: #ef4444;
}

.leading-stock .stock-change.negative {
  color: #3b82f6;
}

/* VWAP */
.vwap-search, .comprehensive-search {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.stock-input {
  flex: 1;
  max-width: 300px;
  padding: 12px 16px;
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  font-size: 1rem;
  transition: all 0.3s ease;
}

.stock-input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.search-btn {
  padding: 12px 24px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.search-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.search-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.vwap-result {
  padding: 20px;
  border-radius: 12px;
  border: 2px solid #e5e7eb;
}

.vwap-result.signal-strong-buy {
  border-color: #dc2626;
  background: linear-gradient(135deg, #fef2f2 0%, #fff1f2 100%);
}

.vwap-result.signal-buy {
  border-color: #f87171;
  background: linear-gradient(135deg, #fef2f2 0%, #fff5f5 100%);
}

.vwap-result.signal-neutral {
  border-color: #9ca3af;
  background: #f9fafb;
}

.vwap-result.signal-sell {
  border-color: #60a5fa;
  background: linear-gradient(135deg, #eff6ff 0%, #f0f9ff 100%);
}

.vwap-result.signal-strong-sell {
  border-color: #2563eb;
  background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%);
}

.vwap-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.vwap-header h3 {
  font-size: 1.25rem;
  font-weight: 700;
  color: #1f2937;
  margin: 0;
}

.vwap-signal {
  padding: 6px 16px;
  border-radius: 20px;
  font-weight: 600;
  color: white;
}

.vwap-signal.signal-strong-buy {
  background: #dc2626;
}

.vwap-signal.signal-buy {
  background: #f87171;
}

.vwap-signal.signal-neutral {
  background: #6b7280;
}

.vwap-signal.signal-sell {
  background: #60a5fa;
}

.vwap-signal.signal-strong-sell {
  background: #2563eb;
}

.vwap-content {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
}

.vwap-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.vwap-stat .label {
  font-size: 0.875rem;
  color: #6b7280;
}

.vwap-stat .value {
  font-size: 1.25rem;
  font-weight: 700;
  color: #1f2937;
}

.vwap-stat .value.positive {
  color: #ef4444;
}

.vwap-stat .value.negative {
  color: #3b82f6;
}

.vwap-interpretation {
  font-size: 0.9375rem;
  color: #374151;
  line-height: 1.6;
  margin: 0;
}

.reliability-warning {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
  color: #b45309;
  font-size: 0.875rem;
}

/* 종합 분석 */
.comprehensive-result {
  text-align: center;
  padding: 32px;
}

.score-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.score-circle {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
}

.score-circle.score-high {
  background: linear-gradient(135deg, #dc2626 0%, #b91c1c 100%);
}

.score-circle.score-mid-high {
  background: linear-gradient(135deg, #f87171 0%, #ef4444 100%);
}

.score-circle.score-neutral {
  background: linear-gradient(135deg, #6b7280 0%, #4b5563 100%);
}

.score-circle.score-mid-low {
  background: linear-gradient(135deg, #60a5fa 0%, #3b82f6 100%);
}

.score-circle.score-low {
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
}

.score-value {
  font-size: 2.5rem;
  line-height: 1;
}

.score-label {
  font-size: 1rem;
  opacity: 0.9;
}

.recommendation {
  font-size: 1.125rem;
  color: #374151;
  max-width: 400px;
  line-height: 1.6;
}

.no-data, .no-data-section {
  text-align: center;
  color: #9ca3af;
  padding: 20px;
}

/* RSI 다이버전스 */
.divergence-info {
  background: #f3f4f6;
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.divergence-info p {
  margin: 4px 0;
  font-size: 0.875rem;
  color: #4b5563;
}

.divergence-search {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.lookback-select {
  padding: 12px 16px;
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  font-size: 1rem;
  background: white;
  cursor: pointer;
}

.lookback-select:focus {
  outline: none;
  border-color: #667eea;
}

.divergence-result {
  padding: 20px;
  border-radius: 12px;
  border: 2px solid #e5e7eb;
}

.divergence-result.divergence-bearish {
  border-color: #3b82f6;
  background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%);
}

.divergence-result.divergence-bullish {
  border-color: #ef4444;
  background: linear-gradient(135deg, #fef2f2 0%, #fee2e2 100%);
}

.divergence-result.divergence-none {
  border-color: #9ca3af;
  background: #f9fafb;
}

.divergence-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.divergence-header h3 {
  font-size: 1.125rem;
  font-weight: 700;
  color: #1f2937;
  margin: 0;
}

.divergence-signal {
  padding: 6px 16px;
  border-radius: 20px;
  font-weight: 600;
  font-size: 0.875rem;
  color: white;
}

.divergence-signal.divergence-bearish {
  background: #3b82f6;
}

.divergence-signal.divergence-bullish {
  background: #ef4444;
}

.divergence-signal.divergence-none {
  background: #6b7280;
}

.divergence-content {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.divergence-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.divergence-stat .label {
  font-size: 0.875rem;
  color: #6b7280;
}

.divergence-stat .value {
  font-size: 1.125rem;
  font-weight: 700;
  color: #1f2937;
}

.divergence-stat .value.rsi-overbought {
  color: #dc2626;
}

.divergence-stat .value.rsi-oversold {
  color: #2563eb;
}

.divergence-stat .value.rsi-normal {
  color: #1f2937;
}

.divergence-interpretation {
  font-size: 0.9375rem;
  color: #374151;
  line-height: 1.6;
  margin: 0;
}

/* 반응형 */
@media (max-width: 768px) {
  .global-market-grid {
    grid-template-columns: 1fr;
  }

  .sectors-grid {
    grid-template-columns: 1fr;
  }

  .vwap-content {
    flex-direction: column;
    gap: 12px;
  }

  .vwap-search, .comprehensive-search {
    flex-direction: column;
  }

  .stock-input {
    max-width: 100%;
  }
}
</style>
