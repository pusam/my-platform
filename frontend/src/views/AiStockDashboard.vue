<template>
  <div class="page-container">
    <div class="page-content">
      <header class="common-header">
        <h1>AI 주식 분석</h1>
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
      <section class="market-summary" v-if="analysis?.marketIndicators">
        <div class="market-card">
          <div class="market-index">
            <span class="label">KOSPI</span>
            <span class="value">{{ formatNumber(analysis.marketIndicators.kospiIndex) }}</span>
            <span :class="['change', analysis.marketIndicators.kospiChange >= 0 ? 'up' : 'down']">
              {{ analysis.marketIndicators.kospiChange >= 0 ? '+' : '' }}{{ analysis.marketIndicators.kospiChange?.toFixed(2) }}%
            </span>
          </div>
          <div class="market-index">
            <span class="label">KOSDAQ</span>
            <span class="value">{{ formatNumber(analysis.marketIndicators.kosdaqIndex) }}</span>
            <span :class="['change', analysis.marketIndicators.kosdaqChange >= 0 ? 'up' : 'down']">
              {{ analysis.marketIndicators.kosdaqChange >= 0 ? '+' : '' }}{{ analysis.marketIndicators.kosdaqChange?.toFixed(2) }}%
            </span>
          </div>
          <div class="market-sentiment">
            <span class="label">시장 상태</span>
            <span :class="['sentiment-badge', getSentimentClass(analysis.marketIndicators.marketSentiment)]">
              {{ analysis.marketIndicators.marketSentiment }}
            </span>
          </div>
          <div class="market-adr">
            <span class="label">ADR</span>
            <span class="value">{{ analysis.marketIndicators.adr?.toFixed(1) }}</span>
          </div>
        </div>
      </section>

      <!-- AI 앙상블 정보 -->
      <section class="ensemble-section" v-if="analysis?.ensembleInfo">
        <div class="section-title">
          <span class="icon">🤖</span>
          <h2>AI 4대장 앙상블</h2>
        </div>
        <div class="ensemble-card">
          <div class="ai-scores">
            <div class="ai-score">
              <span class="ai-name">GPT</span>
              <div class="score-bar">
                <div class="score-fill" :style="{ width: analysis.ensembleInfo.gptScore + '%' }"></div>
              </div>
              <span class="score-value">{{ analysis.ensembleInfo.gptScore }}</span>
            </div>
            <div class="ai-score">
              <span class="ai-name">Claude</span>
              <div class="score-bar">
                <div class="score-fill claude" :style="{ width: analysis.ensembleInfo.claudeScore + '%' }"></div>
              </div>
              <span class="score-value">{{ analysis.ensembleInfo.claudeScore }}</span>
            </div>
            <div class="ai-score">
              <span class="ai-name">Gemini</span>
              <div class="score-bar">
                <div class="score-fill gemini" :style="{ width: analysis.ensembleInfo.geminiScore + '%' }"></div>
              </div>
              <span class="score-value">{{ analysis.ensembleInfo.geminiScore }}</span>
            </div>
            <div class="ai-score">
              <span class="ai-name">Deepseek</span>
              <div class="score-bar">
                <div class="score-fill deepseek" :style="{ width: analysis.ensembleInfo.deepseekScore + '%' }"></div>
              </div>
              <span class="score-value">{{ analysis.ensembleInfo.deepseekScore }}</span>
            </div>
          </div>
          <div class="consensus">
            <div class="consensus-score">
              <span class="label">합의 점수</span>
              <span class="value">{{ analysis.ensembleInfo.consensusScore }}</span>
            </div>
            <div class="consensus-opinion">
              <span :class="['opinion-badge', getOpinionClass(analysis.ensembleInfo.consensusOpinion)]">
                {{ analysis.ensembleInfo.consensusOpinion }}
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- 단기 TOP PICK -->
      <section class="picks-section">
        <div class="section-title">
          <span class="icon">⚡</span>
          <h2>단기 AI TOP PICK</h2>
          <span class="subtitle">1~2주 투자</span>
        </div>
        <div class="picks-grid" v-if="analysis?.shortTermPicks?.length > 0">
          <div
            class="pick-card"
            v-for="stock in analysis.shortTermPicks"
            :key="stock.stockCode"
            @click="goToStockDetail(stock.stockCode)"
          >
            <div class="card-header">
              <div class="stock-info">
                <span class="stock-code">{{ stock.stockCode }}</span>
                <span class="stock-name">{{ stock.stockName }}</span>
              </div>
              <span :class="['opinion-tag', stock.opinionClass]">{{ stock.opinion }}</span>
            </div>
            <div class="card-price">
              <span class="price">{{ formatCurrency(stock.currentPrice) }}</span>
              <span :class="['change', stock.changeRate >= 0 ? 'up' : 'down']">
                {{ stock.changeRate >= 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) }}%
              </span>
            </div>
            <div class="card-scores">
              <div class="score-item">
                <span class="label">단기예측</span>
                <span :class="['score', getScoreClass(stock.shortTermScore)]">{{ stock.shortTermScore }}</span>
              </div>
              <div class="score-item">
                <span class="label">중장기예측</span>
                <span :class="['score', getScoreClass(stock.longTermScore)]">{{ stock.longTermScore }}</span>
              </div>
            </div>
            <div class="card-summary">
              <p>{{ stock.aiSummary }}</p>
            </div>
            <div class="card-reasons" v-if="stock.buyReasons?.length > 0">
              <span class="reason-tag" v-for="(reason, idx) in stock.buyReasons.slice(0, 2)" :key="idx">
                {{ reason }}
              </span>
            </div>
          </div>
        </div>
        <div class="empty-state" v-else-if="!loading">
          <p>분석 결과가 없습니다</p>
        </div>
      </section>

      <!-- 중장기 TOP PICK -->
      <section class="picks-section long-term">
        <div class="section-title">
          <span class="icon">🎯</span>
          <h2>중장기 AI TOP PICK</h2>
          <span class="subtitle">3개월 이상 투자</span>
        </div>
        <div class="picks-grid" v-if="analysis?.longTermPicks?.length > 0">
          <div
            class="pick-card"
            v-for="stock in analysis.longTermPicks"
            :key="stock.stockCode"
            @click="goToStockDetail(stock.stockCode)"
          >
            <div class="card-header">
              <div class="stock-info">
                <span class="stock-code">{{ stock.stockCode }}</span>
                <span class="stock-name">{{ stock.stockName }}</span>
              </div>
              <span :class="['opinion-tag', stock.opinionClass]">{{ stock.opinion }}</span>
            </div>
            <div class="card-price">
              <span class="price">{{ formatCurrency(stock.currentPrice) }}</span>
              <span :class="['change', stock.changeRate >= 0 ? 'up' : 'down']">
                {{ stock.changeRate >= 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) }}%
              </span>
            </div>
            <div class="card-scores">
              <div class="score-item">
                <span class="label">단기예측</span>
                <span :class="['score', getScoreClass(stock.shortTermScore)]">{{ stock.shortTermScore }}</span>
              </div>
              <div class="score-item">
                <span class="label">중장기예측</span>
                <span :class="['score', getScoreClass(stock.longTermScore)]">{{ stock.longTermScore }}</span>
              </div>
            </div>
            <div class="card-summary">
              <p>{{ stock.aiSummary }}</p>
            </div>
            <div class="card-reasons" v-if="stock.buyReasons?.length > 0">
              <span class="reason-tag" v-for="(reason, idx) in stock.buyReasons.slice(0, 2)" :key="idx">
                {{ reason }}
              </span>
            </div>
          </div>
        </div>
        <div class="empty-state" v-else-if="!loading">
          <p>분석 결과가 없습니다</p>
        </div>
      </section>

      <!-- 분석 시간 -->
      <div class="analysis-time" v-if="analysis?.analyzedAt">
        <p>마지막 분석: {{ formatDateTime(analysis.analyzedAt) }}</p>
      </div>

      <LoadingSpinner v-if="loading" message="AI 분석 중..." />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { aiAnalysisAPI } from '../utils/api'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import BackButton from '../components/BackButton.vue'

const router = useRouter()
const analysis = ref(null)
const loading = ref(true)

const fetchAnalysis = async () => {
  try {
    loading.value = true
    const response = await aiAnalysisAPI.getAnalysis()
    if (response.data.success) {
      analysis.value = response.data.data
    }
  } catch (err) {
    console.error('AI 분석 조회 실패:', err)
  } finally {
    loading.value = false
  }
}

const refreshAnalysis = async () => {
  try {
    loading.value = true
    const response = await aiAnalysisAPI.refresh()
    if (response.data.success) {
      analysis.value = response.data.data
    }
  } catch (err) {
    console.error('AI 분석 새로고침 실패:', err)
  } finally {
    loading.value = false
  }
}

const goToStockDetail = (stockCode) => {
  router.push(`/investor-stock/${stockCode}`)
}

const formatCurrency = (value) => {
  if (!value) return '0원'
  return new Intl.NumberFormat('ko-KR').format(value) + '원'
}

const formatNumber = (value) => {
  if (!value) return '-'
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

const formatDateTime = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const getScoreClass = (score) => {
  if (score >= 80) return 'excellent'
  if (score >= 65) return 'good'
  if (score >= 50) return 'neutral'
  return 'poor'
}

const getSentimentClass = (sentiment) => {
  if (sentiment === '과열') return 'hot'
  if (sentiment === '침체') return 'cold'
  return 'normal'
}

const getOpinionClass = (opinion) => {
  if (opinion === '적극 매수' || opinion === '풀매수') return 'strong-buy'
  if (opinion === '매수') return 'buy'
  if (opinion === '관망') return 'hold'
  return 'sell'
}

onMounted(() => {
  fetchAnalysis()
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

/* AI 앙상블 섹션 */
.ensemble-section {
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

.section-title .subtitle {
  font-size: 13px;
  color: #888;
  margin-left: 8px;
}

.ensemble-card {
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

.ai-scores {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.ai-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 80px;
}

.ai-name {
  font-size: 13px;
  font-weight: 600;
  color: #666;
}

.score-bar {
  width: 80px;
  height: 8px;
  background: #e5e7eb;
  border-radius: 4px;
  overflow: hidden;
}

.score-fill {
  height: 100%;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 4px;
  transition: width 0.5s ease;
}

.score-fill.claude {
  background: linear-gradient(90deg, #4f46e5 0%, #7c3aed 100%);
}

.score-fill.gemini {
  background: linear-gradient(90deg, #3b82f6 0%, #06b6d4 100%);
}

.score-fill.deepseek {
  background: linear-gradient(90deg, #10b981 0%, #34d399 100%);
}

.score-value {
  font-size: 15px;
  font-weight: 700;
  color: #333;
}

.consensus {
  display: flex;
  align-items: center;
  gap: 16px;
}

.consensus-score {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.consensus-score .label {
  font-size: 12px;
  color: #888;
}

.consensus-score .value {
  font-size: 32px;
  font-weight: 800;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.opinion-badge {
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 14px;
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

/* TOP PICK 섹션 */
.picks-section {
  margin-bottom: 32px;
}

.picks-section.long-term .section-title h2 {
  color: #16a34a;
}

.picks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
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
  border-color: #667eea;
}

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

.opinion-tag {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 700;
}

.opinion-tag.strong-buy {
  background: linear-gradient(135deg, #ef4444 0%, #f87171 100%);
  color: white;
}

.opinion-tag.buy {
  background: #fee2e2;
  color: #dc2626;
}

.opinion-tag.hold {
  background: #f3f4f6;
  color: #6b7280;
}

.opinion-tag.sell {
  background: #dbeafe;
  color: #3b82f6;
}

.card-price {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 16px;
}

.card-price .price {
  font-size: 20px;
  font-weight: 700;
  color: #333;
}

.card-scores {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}

.score-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
  background: #f8f9fa;
  padding: 10px;
  border-radius: 10px;
}

.score-item .label {
  font-size: 11px;
  color: #888;
}

.score-item .score {
  font-size: 22px;
  font-weight: 800;
}

.score.excellent { color: #ef4444; }
.score.good { color: #f59e0b; }
.score.neutral { color: #6b7280; }
.score.poor { color: #3b82f6; }

.card-summary {
  margin-bottom: 12px;
}

.card-summary p {
  font-size: 13px;
  color: #666;
  line-height: 1.5;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

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

  .ensemble-card {
    flex-direction: column;
    padding: 16px;
  }

  .ai-scores {
    width: 100%;
    justify-content: space-around;
  }

  .picks-grid {
    grid-template-columns: 1fr;
  }
}
</style>
