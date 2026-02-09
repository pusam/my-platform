<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>AI 종목 추천</h1>
        <div class="header-actions">
          <BackButton />
          <button @click="refreshAnalysis" :disabled="loading" class="btn btn-refresh">
            <svg v-if="!loading" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M23 4v6h-6M1 20v-6h6"/>
              <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
            </svg>
            <span v-if="loading" class="loading-spinner"></span>
            {{ loading ? '분석 중...' : '새로고침' }}
          </button>
        </div>
      </header>

      <!-- 시장 상태 요약 -->
      <section class="market-summary">
        <div class="market-card">
          <div class="market-index">
            <span class="label">KOSPI</span>
            <span class="value">{{ formatNumber(marketData.kospiIndex) }}</span>
            <span :class="['change', marketData.kospiChange >= 0 ? 'up' : 'down']">
              {{ marketData.kospiChange >= 0 ? '+' : '' }}{{ marketData.kospiChange?.toFixed(2) }}%
            </span>
          </div>
          <div class="market-index">
            <span class="label">KOSDAQ</span>
            <span class="value">{{ formatNumber(marketData.kosdaqIndex) }}</span>
            <span :class="['change', marketData.kosdaqChange >= 0 ? 'up' : 'down']">
              {{ marketData.kosdaqChange >= 0 ? '+' : '' }}{{ marketData.kosdaqChange?.toFixed(2) }}%
            </span>
          </div>
          <div class="market-sentiment">
            <span class="label">시장 상태</span>
            <span :class="['sentiment-badge', getSentimentClass(marketData.marketSentiment)]">
              {{ marketData.marketSentiment }}
            </span>
          </div>
          <div class="market-adr">
            <span class="label">ADR</span>
            <span class="value">{{ marketData.adr?.toFixed(1) }}</span>
          </div>
        </div>
      </section>

      <!-- 4분할 전략 점수 패널 -->
      <section class="strategy-section">
        <div class="section-title">
          <span class="icon">📊</span>
          <h2>4분할 투자 전략</h2>
        </div>
        <div class="strategy-card">
          <div class="strategy-scores">
            <div class="strategy-score" @click="filterTab = 'scalping'">
              <span class="strategy-name">⚡ 스캘핑</span>
              <div class="score-bar">
                <div class="score-fill scalping" :style="{ width: strategyScores.scalping + '%' }"></div>
              </div>
              <span class="score-value">{{ strategyScores.scalping }}</span>
            </div>
            <div class="strategy-score" @click="filterTab = 'swing'">
              <span class="strategy-name">📈 스윙</span>
              <div class="score-bar">
                <div class="score-fill swing" :style="{ width: strategyScores.swing + '%' }"></div>
              </div>
              <span class="score-value">{{ strategyScores.swing }}</span>
            </div>
            <div class="strategy-score" @click="filterTab = 'turnaround'">
              <span class="strategy-name">🔄 턴어라운드</span>
              <div class="score-bar">
                <div class="score-fill turnaround" :style="{ width: strategyScores.turnaround + '%' }"></div>
              </div>
              <span class="score-value">{{ strategyScores.turnaround }}</span>
            </div>
            <div class="strategy-score" @click="filterTab = 'value'">
              <span class="strategy-name">💎 가치투자</span>
              <div class="score-bar">
                <div class="score-fill value" :style="{ width: strategyScores.value + '%' }"></div>
              </div>
              <span class="score-value">{{ strategyScores.value }}</span>
            </div>
          </div>
          <div class="total-score">
            <div class="total-score-display">
              <span class="label">AI 종합 투자 매력도</span>
              <span class="value">{{ totalScore }}</span>
            </div>
            <div class="total-opinion">
              <span :class="['opinion-badge', getOpinionClass(totalOpinion)]">
                {{ totalOpinion }}
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- 전략별 필터 탭 -->
      <section class="picks-section">
        <div class="section-title">
          <span class="icon">🎯</span>
          <h2>AI 추천 종목</h2>
        </div>

        <div class="filter-tabs">
          <button
            v-for="tab in filterTabs"
            :key="tab.key"
            :class="['filter-tab', { active: filterTab === tab.key }]"
            @click="filterTab = tab.key"
          >
            <span class="tab-icon">{{ tab.icon }}</span>
            <span class="tab-label">{{ tab.label }}</span>
          </button>
        </div>

        <div class="picks-grid">
          <div
            class="pick-card"
            v-for="stock in filteredStocks"
            :key="stock.stockCode"
            :class="[stock.strategyType]"
            @click="goToStockDetail(stock.stockCode)"
          >
            <div class="card-header">
              <div class="stock-info">
                <span class="stock-code">{{ stock.stockCode }}</span>
                <span class="stock-name">{{ stock.stockName }}</span>
              </div>
              <span :class="['strategy-tag', stock.strategyType]">
                {{ stock.strategyIcon }} {{ stock.strategyLabel }}
              </span>
            </div>

            <div class="card-price">
              <span class="price">{{ formatCurrency(stock.currentPrice) }}</span>
              <span :class="['change', stock.changeRate >= 0 ? 'up' : 'down']">
                {{ stock.changeRate >= 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) }}%
              </span>
            </div>

            <div class="card-metrics">
              <div class="metric-item" v-for="metric in stock.keyMetrics" :key="metric.label">
                <span class="label">{{ metric.label }}</span>
                <span class="value" :class="metric.class">{{ metric.value }}</span>
              </div>
            </div>

            <div class="card-reason">
              <span class="reason-icon">💡</span>
              <p>{{ stock.reason }}</p>
            </div>

            <div class="card-target">
              <div class="target-item">
                <span class="label">기대수익</span>
                <span class="value positive">+{{ stock.expectedReturn }}%</span>
              </div>
              <div class="target-item">
                <span class="label">손절가</span>
                <span class="value negative">{{ formatCurrency(stock.stopLoss) }}</span>
              </div>
            </div>

            <div class="card-reasons" v-if="stock.tags?.length > 0">
              <span class="reason-tag" v-for="(tag, idx) in stock.tags" :key="idx">
                {{ tag }}
              </span>
            </div>
          </div>
        </div>

        <div class="empty-state" v-if="filteredStocks.length === 0">
          <p>해당 전략의 추천 종목이 없습니다</p>
        </div>
      </section>

      <!-- 전략 설명 -->
      <section class="strategy-guide">
        <h3>전략 가이드</h3>
        <div class="guide-grid">
          <div class="guide-item scalping">
            <span class="guide-icon">⚡</span>
            <div class="guide-content">
              <h4>스캘핑 (초단타)</h4>
              <p>실시간 체결강도 120%+ & 수급 급증 종목. 분~시간 단위 매매.</p>
            </div>
          </div>
          <div class="guide-item swing">
            <span class="guide-icon">📈</span>
            <div class="guide-content">
              <h4>스윙 (단기)</h4>
              <p>기관/외국인 3일+ 연속 매수 & 섹터 모멘텀. 1~5일 보유.</p>
            </div>
          </div>
          <div class="guide-item turnaround">
            <span class="guide-icon">🔄</span>
            <div class="guide-content">
              <h4>턴어라운드 (중기)</h4>
              <p>흑자전환 확정 & VWAP/RSI 기술적 반등. 2~4주 보유.</p>
            </div>
          </div>
          <div class="guide-item value">
            <span class="guide-icon">💎</span>
            <div class="guide-content">
              <h4>가치투자 (장기)</h4>
              <p>PEG 0.5 이하 & 마법의 공식 상위. 1~3개월+ 보유.</p>
            </div>
          </div>
        </div>
      </section>

      <!-- 분석 시간 -->
      <div class="analysis-time">
        <p>마지막 분석: {{ formatDateTime(lastUpdated) }}</p>
      </div>

      <LoadingSpinner v-if="loading" message="데이터 분석 중..." />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import BackButton from '../components/BackButton.vue'

const router = useRouter()
const loading = ref(false)
const lastUpdated = ref(new Date())
const filterTab = ref('all')

// 필터 탭 정의
const filterTabs = [
  { key: 'all', icon: '📋', label: '전체' },
  { key: 'scalping', icon: '⚡', label: '스캘핑' },
  { key: 'swing', icon: '📈', label: '스윙' },
  { key: 'turnaround', icon: '🔄', label: '턴어라운드' },
  { key: 'value', icon: '💎', label: '가치투자' }
]

// 시장 데이터 (Mock)
const marketData = ref({
  kospiIndex: 2685.42,
  kospiChange: 1.25,
  kosdaqIndex: 842.15,
  kosdaqChange: 0.85,
  marketSentiment: '강세',
  adr: 128.5
})

// 4분할 전략 점수 (Mock)
const strategyScores = ref({
  scalping: 78,
  swing: 82,
  turnaround: 71,
  value: 85
})

// 종합 점수 계산
const totalScore = computed(() => {
  const scores = strategyScores.value
  return Math.round((scores.scalping + scores.swing + scores.turnaround + scores.value) / 4)
})

// 종합 의견
const totalOpinion = computed(() => {
  const score = totalScore.value
  if (score >= 80) return '적극 매수'
  if (score >= 70) return '매수'
  if (score >= 55) return '관망'
  return '매도'
})

// 추천 종목 데이터 (Mock)
const recommendedStocks = ref([
  // 스캘핑 종목
  {
    stockCode: '034020',
    stockName: '두산에너빌리티',
    currentPrice: 21500,
    changeRate: 4.35,
    strategyType: 'scalping',
    strategyIcon: '⚡',
    strategyLabel: '스캘핑',
    reason: '실시간 수급 300억 급증, 체결강도 128%로 강한 매수세 유입 중',
    expectedReturn: 5.0,
    stopLoss: 20400,
    keyMetrics: [
      { label: '체결강도', value: '128%', class: 'positive' },
      { label: '수급', value: '+300억', class: 'positive' },
      { label: '거래량비', value: '320%', class: 'positive' }
    ],
    tags: ['체결강도 급등', '원전 테마']
  },
  {
    stockCode: '000660',
    stockName: 'SK하이닉스',
    currentPrice: 178500,
    changeRate: 3.2,
    strategyType: 'scalping',
    strategyIcon: '⚡',
    strategyLabel: '스캘핑',
    reason: '외국인 폭발 매수 + HBM 수혜 기대감으로 체결강도 142% 돌파',
    expectedReturn: 4.0,
    stopLoss: 173000,
    keyMetrics: [
      { label: '체결강도', value: '142%', class: 'positive' },
      { label: '외국인', value: '+580억', class: 'positive' },
      { label: '프로그램', value: '+220억', class: 'positive' }
    ],
    tags: ['HBM 수혜', '외국인 매집']
  },
  // 스윙 종목
  {
    stockCode: '055550',
    stockName: '신한지주',
    currentPrice: 52000,
    changeRate: 1.8,
    strategyType: 'swing',
    strategyIcon: '📈',
    strategyLabel: '스윙',
    reason: '기관 5일 연속 순매수, 금융 섹터 강세 + 밸류업 수혜 기대',
    expectedReturn: 8.0,
    stopLoss: 49500,
    keyMetrics: [
      { label: '연속매수', value: '5일', class: 'positive' },
      { label: '기관순매수', value: '+850억', class: 'positive' },
      { label: '배당률', value: '6.2%', class: 'positive' }
    ],
    tags: ['기관 연속 매수', '밸류업']
  },
  {
    stockCode: '105560',
    stockName: 'KB금융',
    currentPrice: 78500,
    changeRate: 2.1,
    strategyType: 'swing',
    strategyIcon: '📈',
    strategyLabel: '스윙',
    reason: '외국인 4일 연속 순매수, 저PBR 금융주 재평가 진행 중',
    expectedReturn: 7.5,
    stopLoss: 74800,
    keyMetrics: [
      { label: '연속매수', value: '4일', class: 'positive' },
      { label: 'PBR', value: '0.42배', class: 'positive' },
      { label: '외국인', value: '+620억', class: 'positive' }
    ],
    tags: ['저PBR', '금융 강세']
  },
  // 턴어라운드 종목
  {
    stockCode: '000250',
    stockName: '삼천당제약',
    currentPrice: 125000,
    changeRate: 2.5,
    strategyType: 'turnaround',
    strategyIcon: '🔄',
    strategyLabel: '턴어라운드',
    reason: '3분기 영업이익 흑자전환 확정, RSI 골든크로스로 기술적 반등 신호',
    expectedReturn: 25.0,
    stopLoss: 105000,
    keyMetrics: [
      { label: '영업이익', value: '흑자전환', class: 'positive' },
      { label: 'RSI', value: '골든크로스', class: 'positive' },
      { label: 'VWAP', value: '+2.5%', class: 'positive' }
    ],
    tags: ['흑자전환', 'RSI 반등']
  },
  {
    stockCode: '028300',
    stockName: 'HLB',
    currentPrice: 58000,
    changeRate: 1.8,
    strategyType: 'turnaround',
    strategyIcon: '🔄',
    strategyLabel: '턴어라운드',
    reason: '실적 대폭 개선 + FDA 승인 기대감, VWAP 돌파 진행 중',
    expectedReturn: 35.0,
    stopLoss: 48000,
    keyMetrics: [
      { label: '순이익', value: '흑자전환', class: 'positive' },
      { label: 'VWAP', value: '+5.2%', class: 'positive' },
      { label: '매출성장', value: '+85%', class: 'positive' }
    ],
    tags: ['바이오', 'FDA 기대']
  },
  // 가치투자 종목
  {
    stockCode: '000270',
    stockName: '기아',
    currentPrice: 128000,
    changeRate: 0.8,
    strategyType: 'value',
    strategyIcon: '💎',
    strategyLabel: '가치투자',
    reason: 'PER 4.5배, PEG 0.5로 초저평가 구간. 글로벌 판매 호조 지속',
    expectedReturn: 25.0,
    stopLoss: 112000,
    keyMetrics: [
      { label: 'PER', value: '4.5배', class: 'positive' },
      { label: 'PEG', value: '0.5', class: 'positive' },
      { label: 'ROE', value: '18.5%', class: 'positive' }
    ],
    tags: ['초저평가', '실적 성장']
  },
  {
    stockCode: '005380',
    stockName: '현대차',
    currentPrice: 258000,
    changeRate: 1.2,
    strategyType: 'value',
    strategyIcon: '💎',
    strategyLabel: '가치투자',
    reason: '마법의 공식 상위권, PEG 0.52로 성장 대비 저평가 상태',
    expectedReturn: 20.0,
    stopLoss: 228000,
    keyMetrics: [
      { label: 'PER', value: '5.2배', class: 'positive' },
      { label: 'PEG', value: '0.52', class: 'positive' },
      { label: 'ROE', value: '14.2%', class: 'positive' }
    ],
    tags: ['마법의 공식', 'EV 성장']
  }
])

// 필터링된 종목
const filteredStocks = computed(() => {
  if (filterTab.value === 'all') {
    return recommendedStocks.value
  }
  return recommendedStocks.value.filter(stock => stock.strategyType === filterTab.value)
})

// 데이터 새로고침
const refreshAnalysis = async () => {
  loading.value = true
  // 실제 API 호출 대신 Mock 데이터 갱신 시뮬레이션
  await new Promise(resolve => setTimeout(resolve, 1500))
  lastUpdated.value = new Date()
  loading.value = false
}

const goToStockDetail = (stockCode) => {
  router.push(`/investor-stock/${stockCode}`)
}

const formatCurrency = (value) => {
  if (!value && value !== 0) return '0원'
  return new Intl.NumberFormat('ko-KR').format(Math.round(value)) + '원'
}

const formatNumber = (value) => {
  if (!value) return '-'
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

const formatDateTime = (date) => {
  if (!date) return ''
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const getSentimentClass = (sentiment) => {
  if (sentiment === '과열' || sentiment === '강세') return 'hot'
  if (sentiment === '침체' || sentiment === '약세') return 'cold'
  return 'normal'
}

const getOpinionClass = (opinion) => {
  if (opinion === '적극 매수') return 'strong-buy'
  if (opinion === '매수') return 'buy'
  if (opinion === '관망') return 'hold'
  return 'sell'
}

onMounted(() => {
  // 초기 로드 시뮬레이션
  loading.value = true
  setTimeout(() => {
    loading.value = false
  }, 800)
})
</script>

<style scoped>
@import '../assets/css/common.css';

.page-content {
  max-width: 1200px;
  margin: 0 auto;
}

.btn-refresh {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.3s ease;
}

.btn-refresh:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.btn-refresh:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.loading-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 시장 요약 */
.market-summary {
  margin-bottom: 24px;
}

.market-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  border-radius: 16px;
  padding: 20px 30px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  flex-wrap: wrap;
  gap: 16px;
}

.market-index, .market-sentiment, .market-adr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.market-index .label, .market-sentiment .label, .market-adr .label {
  font-size: 12px;
  color: #888;
}

.market-index .value, .market-adr .value {
  font-size: 20px;
  font-weight: 700;
  color: #333;
}

.change {
  font-size: 13px;
  font-weight: 600;
}

.change.up { color: #ef4444; }
.change.down { color: #3b82f6; }

.sentiment-badge {
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 600;
}

.sentiment-badge.hot {
  background: #fee2e2;
  color: #ef4444;
}

.sentiment-badge.cold {
  background: #dbeafe;
  color: #3b82f6;
}

.sentiment-badge.normal {
  background: #f3f4f6;
  color: #6b7280;
}

/* 4분할 전략 섹션 */
.strategy-section {
  margin-bottom: 32px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.section-title .icon {
  font-size: 24px;
}

.section-title h2 {
  font-size: 20px;
  font-weight: 700;
  color: #333;
  margin: 0;
}

.strategy-card {
  background: white;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 24px;
}

.strategy-scores {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.strategy-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 100px;
  cursor: pointer;
  padding: 8px;
  border-radius: 12px;
  transition: all 0.3s;
}

.strategy-score:hover {
  background: #f8f9fa;
  transform: translateY(-2px);
}

.strategy-name {
  font-size: 13px;
  font-weight: 600;
  color: #444;
}

.score-bar {
  width: 100px;
  height: 8px;
  background: #e5e7eb;
  border-radius: 4px;
  overflow: hidden;
}

.score-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s ease;
}

.score-fill.scalping {
  background: linear-gradient(90deg, #f59e0b 0%, #f97316 100%);
}

.score-fill.swing {
  background: linear-gradient(90deg, #22c55e 0%, #16a34a 100%);
}

.score-fill.turnaround {
  background: linear-gradient(90deg, #3b82f6 0%, #2563eb 100%);
}

.score-fill.value {
  background: linear-gradient(90deg, #a855f7 0%, #7c3aed 100%);
}

.score-value {
  font-size: 18px;
  font-weight: 800;
  color: #333;
}

.total-score {
  display: flex;
  align-items: center;
  gap: 20px;
}

.total-score-display {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.total-score-display .label {
  font-size: 12px;
  color: #888;
  white-space: nowrap;
}

.total-score-display .value {
  font-size: 42px;
  font-weight: 800;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.opinion-badge {
  padding: 10px 20px;
  border-radius: 24px;
  font-size: 15px;
  font-weight: 700;
}

.opinion-badge.strong-buy {
  background: linear-gradient(135deg, #ef4444 0%, #f87171 100%);
  color: white;
}

.opinion-badge.buy {
  background: #fecaca;
  color: #dc2626;
}

.opinion-badge.hold {
  background: #f3f4f6;
  color: #6b7280;
}

.opinion-badge.sell {
  background: #dbeafe;
  color: #3b82f6;
}

/* 필터 탭 */
.filter-tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.filter-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 18px;
  background: #f3f4f6;
  border: 2px solid transparent;
  border-radius: 24px;
  font-size: 14px;
  font-weight: 600;
  color: #666;
  cursor: pointer;
  transition: all 0.3s;
}

.filter-tab:hover {
  background: #e5e7eb;
}

.filter-tab.active {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-color: transparent;
}

.tab-icon {
  font-size: 16px;
}

/* 추천 종목 그리드 */
.picks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 20px;
}

.pick-card {
  background: white;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
}

.pick-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.pick-card.scalping:hover { border-color: #f59e0b; }
.pick-card.swing:hover { border-color: #22c55e; }
.pick-card.turnaround:hover { border-color: #3b82f6; }
.pick-card.value:hover { border-color: #a855f7; }

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

.stock-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stock-code {
  font-size: 12px;
  color: #888;
}

.stock-name {
  font-size: 18px;
  font-weight: 700;
  color: #333;
}

.strategy-tag {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 700;
}

.strategy-tag.scalping {
  background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%);
  color: #d97706;
}

.strategy-tag.swing {
  background: linear-gradient(135deg, #dcfce7 0%, #bbf7d0 100%);
  color: #16a34a;
}

.strategy-tag.turnaround {
  background: linear-gradient(135deg, #dbeafe 0%, #bfdbfe 100%);
  color: #2563eb;
}

.strategy-tag.value {
  background: linear-gradient(135deg, #f3e8ff 0%, #e9d5ff 100%);
  color: #7c3aed;
}

.card-price {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 16px;
}

.card-price .price {
  font-size: 22px;
  font-weight: 700;
  color: #333;
}

.card-metrics {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.card-metrics .metric-item {
  flex: 1;
  text-align: center;
  padding: 8px;
  background: #f8f9fa;
  border-radius: 8px;
}

.card-metrics .label {
  display: block;
  font-size: 10px;
  color: #888;
  margin-bottom: 2px;
}

.card-metrics .value {
  font-size: 13px;
  font-weight: 700;
}

.card-metrics .value.positive { color: #16a34a; }
.card-metrics .value.negative { color: #dc2626; }

.card-reason {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
  padding: 12px;
  background: linear-gradient(135deg, #fef3c7 0%, #fffbeb 100%);
  border-radius: 10px;
}

.reason-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.card-reason p {
  font-size: 13px;
  color: #92400e;
  line-height: 1.5;
  margin: 0;
}

.card-target {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.card-target .target-item {
  flex: 1;
  text-align: center;
  padding: 10px;
  background: #f8f9fa;
  border-radius: 8px;
}

.card-target .label {
  display: block;
  font-size: 11px;
  color: #888;
  margin-bottom: 4px;
}

.card-target .value {
  font-size: 14px;
  font-weight: 700;
}

.card-target .value.positive { color: #16a34a; }
.card-target .value.negative { color: #dc2626; }

.card-reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.reason-tag {
  background: linear-gradient(135deg, #f0f4ff 0%, #e8f0fe 100%);
  color: #4f46e5;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
}

/* 전략 가이드 */
.strategy-guide {
  margin-top: 32px;
  background: white;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.strategy-guide h3 {
  font-size: 18px;
  font-weight: 700;
  color: #333;
  margin: 0 0 20px 0;
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 16px;
}

.guide-item {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-radius: 12px;
}

.guide-item.scalping { background: linear-gradient(135deg, #fffbeb 0%, #fef3c7 100%); }
.guide-item.swing { background: linear-gradient(135deg, #f0fdf4 0%, #dcfce7 100%); }
.guide-item.turnaround { background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%); }
.guide-item.value { background: linear-gradient(135deg, #faf5ff 0%, #f3e8ff 100%); }

.guide-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.guide-content h4 {
  font-size: 14px;
  font-weight: 700;
  color: #333;
  margin: 0 0 6px 0;
}

.guide-content p {
  font-size: 12px;
  color: #666;
  line-height: 1.5;
  margin: 0;
}

.empty-state {
  text-align: center;
  padding: 40px;
  color: #888;
  background: #f8f9fa;
  border-radius: 16px;
}

.analysis-time {
  text-align: center;
  padding: 16px;
  color: #888;
  font-size: 13px;
}

/* 반응형 */
@media (max-width: 768px) {
  .market-card {
    flex-direction: column;
    padding: 16px;
  }

  .strategy-card {
    flex-direction: column;
    padding: 16px;
  }

  .strategy-scores {
    width: 100%;
    justify-content: space-around;
  }

  .filter-tabs {
    overflow-x: auto;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
  }

  .picks-grid {
    grid-template-columns: 1fr;
  }

  .guide-grid {
    grid-template-columns: 1fr;
  }
}
</style>
