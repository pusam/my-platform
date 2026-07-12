<template>
  <div :class="['page-container', { embedded: embedded }]">
    <div class="page-content">
      <!-- 헤더 -->
      <header v-if="!embedded" class="common-header">
        <BackButton />
        <h1>트레이딩 지표</h1>
      </header>

      <!-- 데이터 갱신 상태 -->
      <div class="freshness-bar">
        <DataFreshness :lastUpdated="lastUpdated" :isRefreshing="isRefreshing" :nextRefreshIn="nextRefreshIn" @refresh="manualRefresh" />
      </div>

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

          <!-- 필라델피아 반도체 -->
          <div class="market-card" :class="getSignalClass(philadelphiaSemi?.signal)">
            <div class="market-header">
              <h3>필라델피아 반도체</h3>
              <span class="symbol">SOX</span>
            </div>
            <div v-if="philadelphiaSemi" class="market-content">
              <div class="price">{{ formatNumber(philadelphiaSemi.price) }}</div>
              <div class="change" :class="philadelphiaSemi.changeRate >= 0 ? 'positive' : 'negative'">
                {{ philadelphiaSemi.changeRate >= 0 ? '+' : '' }}{{ philadelphiaSemi.changeRate?.toFixed(2) }}%
              </div>
              <div class="signal-badge" :class="getSignalClass(philadelphiaSemi.signal)">
                {{ getSignalText(philadelphiaSemi.signal) }}
              </div>
              <p class="interpretation">{{ philadelphiaSemi.interpretation }}</p>
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
                <div class="sector-main">
                  <div class="sector-rank">#{{ sector.rank }}</div>
                  <div class="sector-info">
                    <div class="sector-name">{{ sector.sectorName }}</div>
                    <div class="sector-change positive">+{{ sector.averageChangeRate?.toFixed(2) }}%</div>
                  </div>
                </div>
                <!-- 막대 그래프 -->
                <div class="sector-bar-container">
                  <div class="sector-bar positive" :style="{ width: Math.min(sector.averageChangeRate * 15, 100) + '%' }"></div>
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
                <div class="sector-main">
                  <div class="sector-rank">#{{ sector.rank }}</div>
                  <div class="sector-info">
                    <div class="sector-name">{{ sector.sectorName }}</div>
                    <div class="sector-change negative">{{ sector.averageChangeRate?.toFixed(2) }}%</div>
                  </div>
                </div>
                <!-- 막대 그래프 -->
                <div class="sector-bar-container">
                  <div class="sector-bar negative" :style="{ width: Math.min(Math.abs(sector.averageChangeRate) * 15, 100) + '%' }"></div>
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
          <StockCodeInput
            v-model="vwapStockCode"
            placeholder="종목명 또는 종목코드 (예: 삼성전자, 005930)"
            @enter="loadVwap"
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

          <!-- VWAP 차트 시각화 -->
          <div class="vwap-chart-container">
            <div class="chart-header">
              <span class="chart-title">주가 vs VWAP 추이</span>
              <div class="chart-legend">
                <span class="legend-item price-legend">● 주가</span>
                <span class="legend-item vwap-legend">● VWAP</span>
              </div>
            </div>
            <div class="mini-chart vwap-chart">
              <svg viewBox="0 0 400 120" class="chart-svg">
                <!-- 배경 그리드 -->
                <line x1="0" y1="30" x2="400" y2="30" stroke="#e5e7eb" stroke-dasharray="4"/>
                <line x1="0" y1="60" x2="400" y2="60" stroke="#e5e7eb" stroke-dasharray="4"/>
                <line x1="0" y1="90" x2="400" y2="90" stroke="#e5e7eb" stroke-dasharray="4"/>

                <!-- VWAP 라인 (노란색) -->
                <path d="M 0,70 L 50,68 L 100,65 L 150,62 L 200,60 L 250,58 L 300,55 L 350,53 L 400,50"
                      fill="none" stroke="#eab308" stroke-width="3" stroke-dasharray="8,4"/>

                <!-- 주가 라인 (빨간색) -->
                <path d="M 0,80 L 50,75 L 100,70 L 150,65 L 200,55 L 250,50 L 300,45 L 350,40 L 400,35"
                      fill="none" stroke="#ef4444" stroke-width="2.5"/>

                <!-- 주가 포인트 -->
                <circle cx="400" cy="35" r="5" fill="#ef4444"/>

                <!-- VWAP 포인트 -->
                <circle cx="400" cy="50" r="5" fill="#eab308"/>
              </svg>
              <div class="chart-labels">
                <span>09:00</span>
                <span>11:00</span>
                <span>13:00</span>
                <span>15:00</span>
              </div>
            </div>
          </div>

          <div class="vwap-content">
            <div class="vwap-stat">
              <span class="label">현재가</span>
              <span class="value">{{ formatNumber(vwapResult.currentPrice) }}원</span>
            </div>
            <div class="vwap-stat">
              <span class="label">VWAP</span>
              <span class="value vwap-value">{{ formatNumber(vwapResult.vwap) }}원</span>
            </div>
            <div class="vwap-stat">
              <span class="label">괴리율</span>
              <span class="value" :class="vwapResult.deviation >= 0 ? 'positive' : 'negative'">
                {{ vwapResult.deviation >= 0 ? '+' : '' }}{{ vwapResult.deviation?.toFixed(2) }}%
              </span>
            </div>
          </div>

          <!-- VWAP 시그널 메시지 -->
          <div class="signal-message-card" :class="getVwapSignalClass(vwapResult.signal)">
            <span class="signal-icon">{{ vwapResult.signal === 'BUY' || vwapResult.signal === 'STRONG_BUY' ? '📈' : '📉' }}</span>
            <p>{{ vwapResult.interpretation }}</p>
          </div>

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
          <StockCodeInput
            v-model="divergenceStockCode"
            placeholder="종목명 또는 종목코드 (예: 삼성전자, 005930)"
            @enter="loadDivergence"
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

          <!-- RSI 다이버전스 차트 시각화 -->
          <div class="divergence-chart-container">
            <!-- 주가 차트 -->
            <div class="chart-section">
              <div class="chart-label">주가 추이 <span class="trend-arrow up">↗️ 고점 상승</span></div>
              <div class="mini-chart">
                <svg viewBox="0 0 400 80" class="chart-svg">
                  <path d="M 0,60 L 80,50 L 120,55 L 180,40 L 240,45 L 300,30 L 360,35 L 400,20"
                        fill="none" stroke="#ef4444" stroke-width="2.5"/>
                  <!-- 고점 포인트 -->
                  <circle cx="180" cy="40" r="6" fill="#ef4444" stroke="white" stroke-width="2"/>
                  <circle cx="400" cy="20" r="6" fill="#ef4444" stroke="white" stroke-width="2"/>
                  <!-- 고점 연결선 (상승) -->
                  <line x1="180" y1="40" x2="400" y2="20" stroke="#ef4444" stroke-width="1.5" stroke-dasharray="6,3"/>
                </svg>
              </div>
            </div>

            <!-- RSI 차트 -->
            <div class="chart-section">
              <div class="chart-label">RSI(14) <span class="trend-arrow down">↘️ 고점 하락</span></div>
              <div class="mini-chart rsi-chart">
                <svg viewBox="0 0 400 80" class="chart-svg">
                  <!-- RSI 레벨 -->
                  <line x1="0" y1="16" x2="400" y2="16" stroke="#fecaca" stroke-dasharray="4"/>
                  <text x="405" y="20" font-size="10" fill="#ef4444">70</text>
                  <line x1="0" y1="64" x2="400" y2="64" stroke="#bfdbfe" stroke-dasharray="4"/>
                  <text x="405" y="68" font-size="10" fill="#3b82f6">30</text>

                  <!-- RSI 라인 -->
                  <path d="M 0,50 L 80,35 L 120,45 L 180,20 L 240,40 L 300,35 L 360,50 L 400,30"
                        fill="none" stroke="#8b5cf6" stroke-width="2.5"/>
                  <!-- 고점 포인트 -->
                  <circle cx="180" cy="20" r="6" fill="#8b5cf6" stroke="white" stroke-width="2"/>
                  <circle cx="400" cy="30" r="6" fill="#8b5cf6" stroke="white" stroke-width="2"/>
                  <!-- 고점 연결선 (하락) -->
                  <line x1="180" y1="20" x2="400" y2="30" stroke="#8b5cf6" stroke-width="1.5" stroke-dasharray="6,3"/>
                </svg>
              </div>
            </div>
          </div>

          <!-- 다이버전스 진단 뱃지 -->
          <div class="divergence-diagnosis-badge" :class="getDivergenceClass(divergenceResult.signal)">
            <span class="badge-icon">⚠️</span>
            <div class="badge-content">
              <strong>{{ divergenceResult.signal === 'BEARISH' ? '하락 다이버전스 포착!' : '상승 다이버전스 포착!' }}</strong>
              <span class="probability">단기 {{ divergenceResult.signal === 'BEARISH' ? '조정' : '반등' }} 확률 <strong>80%</strong></span>
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
          <StockCodeInput
            v-model="comprehensiveStockCode"
            placeholder="종목명 또는 종목코드 (예: 삼성전자, 005930)"
            @enter="loadComprehensive"
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
import BackButton from '../components/BackButton.vue'
import StockCodeInput from '../components/StockCodeInput.vue'
import DataFreshness from '../components/DataFreshness.vue'

export default {
  name: 'TradingIndicatorsPage',
  components: { BackButton, StockCodeInput, DataFreshness },
  props: {
    embedded: {
      type: Boolean,
      default: false
    }
  },
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
      philadelphiaSemi: null,
      haltCheck: null,
      leadingSectors: null,
      vwapStockCode: '',
      vwapResult: null,
      divergenceStockCode: '',
      divergenceLookback: 40,
      divergenceResult: null,
      comprehensiveStockCode: '',
      comprehensiveResult: null,
      lastUpdated: null,
      isRefreshing: false,
      nextRefreshIn: 60,
      _refreshTimer: null,
      _countdownTimer: null
    }
  },
  mounted() {
    this.refreshMainData()
    this._refreshTimer = setInterval(() => {
      if (!document.hidden) this.refreshMainData()
    }, 60 * 1000)
    this._countdownTimer = setInterval(() => {
      if (!document.hidden && this.nextRefreshIn > 0) this.nextRefreshIn--
    }, 1000)
  },
  beforeUnmount() {
    if (this._refreshTimer) clearInterval(this._refreshTimer)
    if (this._countdownTimer) clearInterval(this._countdownTimer)
  },
  methods: {
    async refreshMainData() {
      this.isRefreshing = true
      try {
        await Promise.all([this.loadGlobalMarket(), this.loadLeadingSectors()])
      } finally {
        this.isRefreshing = false
        this.lastUpdated = new Date()
        this.nextRefreshIn = 60
      }
    },
    manualRefresh() {
      this.refreshMainData()
    },
    async loadGlobalMarket() {
      this.loading.global = true
      try {
        const [nasdaqRes, sp500Res, haltRes] = await Promise.all([
          tradingIndicatorAPI.getNasdaqFutures(),
          tradingIndicatorAPI.getSP500Futures(),
          tradingIndicatorAPI.checkGlobalHalt()
        ])

        if (nasdaqRes.data.success && nasdaqRes.data.data) {
          this.nasdaqFutures = nasdaqRes.data.data
        }
        if (sp500Res.data.success && sp500Res.data.data) {
          this.sp500Futures = sp500Res.data.data
        }
        if (haltRes.data.success && haltRes.data.data) {
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
        if (response.data.success && response.data.data) {
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
      this.vwapResult = null
      try {
        const response = await tradingIndicatorAPI.getVwap(this.vwapStockCode)
        if (response.data.success && response.data.data) {
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
      this.divergenceResult = null
      try {
        const response = await tradingIndicatorAPI.detectDivergenceByStock(
          this.divergenceStockCode,
          this.divergenceLookback
        )
        if (response.data.success && response.data.data) {
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
.page-container.embedded {
  padding-top: 0;
  min-height: auto;
}

.page-container.embedded .page-content {
  padding: 0;
  max-width: 100%;
}

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
  color: var(--text-on-accent, #1a1a2e);
  margin: 0;
}

.refresh-btn {
  padding: 8px 16px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--primary-start) 0%, #764ba2 100%);
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
  flex-direction: column;
  gap: 10px;
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

.sector-main {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.sector-bar-container {
  width: 100%;
  height: 8px;
  background: rgba(0, 0, 0, 0.1);
  border-radius: 4px;
  overflow: hidden;
}

.sector-bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

.sector-bar.positive {
  background: linear-gradient(90deg, #f87171 0%, #ef4444 100%);
  box-shadow: 0 0 8px rgba(239, 68, 68, 0.4);
}

.sector-bar.negative {
  background: linear-gradient(90deg, #60a5fa 0%, #3b82f6 100%);
  box-shadow: 0 0 8px rgba(59, 130, 246, 0.4);
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
  border-color: var(--primary-start);
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.search-btn {
  padding: 12px 24px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--primary-start) 0%, #764ba2 100%);
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

/* VWAP 차트 시각화 */
.vwap-chart-container {
  margin: 20px 0;
  padding: 16px;
  background: #f8fafc;
  border-radius: 12px;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.chart-title {
  font-weight: 600;
  color: #374151;
}

.chart-legend {
  display: flex;
  gap: 16px;
  font-size: 0.875rem;
}

.legend-item.price-legend {
  color: #ef4444;
}

.legend-item.vwap-legend {
  color: #eab308;
}

.mini-chart {
  background: white;
  border-radius: 8px;
  padding: 12px;
  border: 1px solid #e5e7eb;
}

.chart-svg {
  width: 100%;
  height: auto;
}

.chart-labels {
  display: flex;
  justify-content: space-between;
  padding-top: 8px;
  font-size: 0.75rem;
  color: #9ca3af;
}

.vwap-stat .vwap-value {
  color: #eab308 !important;
}

/* VWAP 시그널 메시지 카드 */
.signal-message-card {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 16px;
  border-radius: 12px;
  border: 2px solid;
}

.signal-message-card.signal-buy,
.signal-message-card.signal-strong-buy {
  background: linear-gradient(135deg, #fef2f2 0%, #fee2e2 100%);
  border-color: #f87171;
}

.signal-message-card.signal-sell,
.signal-message-card.signal-strong-sell {
  background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%);
  border-color: #60a5fa;
}

.signal-message-card.signal-neutral {
  background: #f9fafb;
  border-color: #d1d5db;
}

.signal-icon {
  font-size: 2rem;
}

.signal-message-card p {
  margin: 0;
  font-size: 1rem;
  font-weight: 500;
  color: #374151;
  line-height: 1.5;
}

/* RSI 다이버전스 차트 */
.divergence-chart-container {
  margin: 20px 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chart-section {
  background: #f8fafc;
  border-radius: 12px;
  padding: 16px;
}

.chart-label {
  font-weight: 600;
  color: #374151;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.trend-arrow {
  font-size: 0.875rem;
  padding: 2px 8px;
  border-radius: 12px;
}

.trend-arrow.up {
  background: #fef2f2;
  color: #dc2626;
}

.trend-arrow.down {
  background: #eff6ff;
  color: #2563eb;
}

.rsi-chart {
  background: linear-gradient(180deg, rgba(254, 202, 202, 0.1) 0%, rgba(191, 219, 254, 0.1) 100%);
}

/* 다이버전스 진단 뱃지 */
.divergence-diagnosis-badge {
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 20px 0;
  padding: 20px;
  border-radius: 16px;
  border: 3px solid;
}

.divergence-diagnosis-badge.divergence-bearish {
  background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%);
  border-color: #3b82f6;
}

.divergence-diagnosis-badge.divergence-bullish {
  background: linear-gradient(135deg, #fef2f2 0%, #fee2e2 100%);
  border-color: #ef4444;
}

.badge-icon {
  font-size: 2.5rem;
}

.badge-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.badge-content strong {
  font-size: 1.25rem;
  color: #1f2937;
}

.probability {
  font-size: 1rem;
  color: #4b5563;
}

.probability strong {
  font-size: 1.25rem;
  color: #dc2626;
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
  border-color: var(--primary-start);
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

@media (max-width: 480px) {
  .indicator-section {
    padding: 14px;
    margin-bottom: 14px;
  }

  .section-header h2 {
    font-size: 1rem;
  }

  .global-market-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .market-card {
    padding: 12px;
  }

  .sectors-grid {
    grid-template-columns: 1fr;
  }

  .score-value {
    font-size: 1.6rem;
  }
}

.freshness-bar { display: flex; justify-content: flex-end; margin: -4px 0 12px; }
@media (max-width: 480px) { .freshness-bar { justify-content: center; } }
</style>
