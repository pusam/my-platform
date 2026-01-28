<template>
  <div class="technical-analysis-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>기술적 분석</h1>
        <p class="subtitle">이동평균선, RSI, 매매 시그널 분석</p>
      </div>

      <!-- 종목 검색 -->
      <div class="search-section">
        <div class="search-box">
          <input
            v-model="searchQuery"
            type="text"
            placeholder="종목명 또는 종목코드 입력"
            @keyup.enter="searchStock"
          />
          <button @click="searchStock" class="search-btn">분석</button>
        </div>
      </div>

      <!-- 분석 결과 -->
      <div v-if="analysisResult" class="analysis-result">
        <div class="stock-info-header">
          <div class="stock-title">
            <h2>{{ analysisResult.stockName }}</h2>
            <span class="stock-code">{{ analysisResult.stockCode }}</span>
          </div>
          <div class="stock-price">
            <span class="current-price">{{ formatNumber(analysisResult.currentPrice) }}원</span>
            <span class="change-rate" :class="getRateClass(analysisResult.changeRate)">
              {{ formatRate(analysisResult.changeRate) }}
            </span>
          </div>
        </div>

        <!-- 종합 신호 카드 -->
        <div class="signal-card" :class="getSignalCardClass(analysisResult.technicalSignal)">
          <div class="signal-main">
            <span class="signal-icon">{{ getTechnicalSignalIcon(analysisResult.technicalSignal) }}</span>
            <div class="signal-info">
              <span class="signal-label">{{ analysisResult.technicalSignalLabel || getTechnicalSignalLabel(analysisResult.technicalSignal) }}</span>
              <span class="signal-score">기술적 점수: {{ analysisResult.technicalScore }}점</span>
            </div>
          </div>
          <div class="signal-description" v-if="analysisResult.technicalDescription">
            {{ analysisResult.technicalDescription }}
          </div>
        </div>

        <!-- 이동평균선 -->
        <div class="indicator-section">
          <h3>이동평균선 (Moving Average)</h3>
          <div class="ma-cards">
            <div class="ma-card" :class="{ above: analysisResult.isAboveMa5 }">
              <span class="ma-period">MA5</span>
              <span class="ma-value">{{ formatNumber(analysisResult.ma5) }}</span>
              <span class="ma-status">{{ analysisResult.isAboveMa5 ? '상향' : '하향' }}</span>
            </div>
            <div class="ma-card" :class="{ above: analysisResult.isAboveMa20 }">
              <span class="ma-period">MA20</span>
              <span class="ma-value">{{ formatNumber(analysisResult.ma20) }}</span>
              <span class="ma-status">{{ analysisResult.isAboveMa20 ? '상향' : '하향' }}</span>
            </div>
            <div class="ma-card" :class="{ above: analysisResult.isAboveMa60 }">
              <span class="ma-period">MA60</span>
              <span class="ma-value">{{ formatNumber(analysisResult.ma60) }}</span>
              <span class="ma-status">{{ analysisResult.isAboveMa60 ? '상향' : '하향' }}</span>
            </div>
            <div class="ma-card" v-if="analysisResult.ma120">
              <span class="ma-period">MA120</span>
              <span class="ma-value">{{ formatNumber(analysisResult.ma120) }}</span>
              <span class="ma-status">-</span>
            </div>
          </div>
        </div>

        <!-- RSI -->
        <div class="indicator-section">
          <h3>RSI (상대강도지수)</h3>
          <div class="rsi-display">
            <div class="rsi-gauge">
              <div class="rsi-bar">
                <div class="rsi-fill" :style="{ width: (analysisResult.rsi14 || 50) + '%' }"></div>
                <div class="rsi-marker" :style="{ left: (analysisResult.rsi14 || 50) + '%' }"></div>
              </div>
              <div class="rsi-labels">
                <span>0</span>
                <span class="oversold-zone">30</span>
                <span>50</span>
                <span class="overbought-zone">70</span>
                <span>100</span>
              </div>
            </div>
            <div class="rsi-value-display" :class="getRsiClass(analysisResult.rsiStatus)">
              <span class="rsi-number">{{ analysisResult.rsi14 ? Number(analysisResult.rsi14).toFixed(1) : '-' }}</span>
              <span class="rsi-status">{{ analysisResult.rsiStatusLabel || getRsiLabel(analysisResult.rsiStatus) }}</span>
            </div>
          </div>
        </div>

        <!-- 매매 시그널 -->
        <div class="indicator-section">
          <h3>매매 시그널</h3>
          <div class="signals-grid">
            <div class="signal-item" :class="{ active: analysisResult.isGoldenCross }">
              <span class="signal-name">골든크로스</span>
              <span class="signal-value">{{ analysisResult.isGoldenCross ? '발생' : '-' }}</span>
            </div>
            <div class="signal-item" :class="{ active: analysisResult.isDeadCross, negative: true }">
              <span class="signal-name">데드크로스</span>
              <span class="signal-value">{{ analysisResult.isDeadCross ? '발생' : '-' }}</span>
            </div>
            <div class="signal-item" :class="{ active: analysisResult.isArrangedUp }">
              <span class="signal-name">정배열</span>
              <span class="signal-value">{{ analysisResult.isArrangedUp ? 'O' : '-' }}</span>
            </div>
            <div class="signal-item" :class="{ active: analysisResult.isArrangedDown, negative: true }">
              <span class="signal-name">역배열</span>
              <span class="signal-value">{{ analysisResult.isArrangedDown ? 'O' : '-' }}</span>
            </div>
          </div>
        </div>

        <!-- 공매도 정보 (있는 경우) -->
        <div class="indicator-section" v-if="analysisResult.loanBalanceRatio">
          <h3>공매도 현황</h3>
          <div class="short-info-grid">
            <div class="short-info-item">
              <span class="label">대차잔고 비율</span>
              <span class="value">{{ formatPercent(analysisResult.loanBalanceRatio) }}</span>
            </div>
            <div class="short-info-item">
              <span class="label">공매도 비중</span>
              <span class="value">{{ formatPercent(analysisResult.shortRatio) }}</span>
            </div>
            <div class="short-info-item" v-if="analysisResult.squeezeScore">
              <span class="label">스퀴즈 점수</span>
              <span class="value highlight">{{ analysisResult.squeezeScore }}점</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 빠른 분석 목록 -->
      <div class="quick-analysis-section">
        <h3>빠른 분석</h3>
        <div class="quick-stocks">
          <button v-for="stock in quickStocks" :key="stock.code"
                  @click="analyzeQuickStock(stock.code)"
                  class="quick-stock-btn">
            {{ stock.name }}
          </button>
        </div>
      </div>

      <!-- 분석 결과 없음 -->
      <div v-if="!analysisResult && !loading && searched" class="no-result">
        <p>분석 결과가 없습니다.</p>
        <p class="hint">종목코드를 확인하거나 다른 종목을 검색해보세요.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import api from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();
const loading = ref(false);
const searchQuery = ref('');
const analysisResult = ref(null);
const searched = ref(false);

const quickStocks = [
  { code: '005930', name: '삼성전자' },
  { code: '000660', name: 'SK하이닉스' },
  { code: '373220', name: 'LG에너지솔루션' },
  { code: '207940', name: '삼성바이오로직스' },
  { code: '005380', name: '현대차' },
  { code: '035420', name: 'NAVER' },
  { code: '035720', name: '카카오' },
  { code: '051910', name: 'LG화학' }
];

const searchStock = async () => {
  if (!searchQuery.value.trim()) return;

  loading.value = true;
  searched.value = true;

  try {
    // 종목코드 추출 (숫자 6자리)
    let stockCode = searchQuery.value.trim();
    if (!/^\d{6}$/.test(stockCode)) {
      // 종목명으로 검색하는 경우 - 일단 종목코드로 시도
      stockCode = searchQuery.value.replace(/\D/g, '').padStart(6, '0');
    }

    const response = await api.get(`/short-selling/analysis/${stockCode}`);
    if (response.data.success) {
      analysisResult.value = response.data.data;
    } else {
      analysisResult.value = null;
    }
  } catch (error) {
    console.error('분석 오류:', error);
    analysisResult.value = null;
  } finally {
    loading.value = false;
  }
};

const analyzeQuickStock = (code) => {
  searchQuery.value = code;
  searchStock();
};

const goBack = () => {
  router.back();
};

// 포맷팅 함수들
const formatNumber = (value) => {
  if (!value) return '-';
  return Number(value).toLocaleString('ko-KR');
};

const formatRate = (value) => {
  if (!value && value !== 0) return '-';
  const sign = value > 0 ? '+' : '';
  return `${sign}${Number(value).toFixed(2)}%`;
};

const formatPercent = (value) => {
  if (!value && value !== 0) return '-';
  return `${Number(value).toFixed(2)}%`;
};

const getRateClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const getTechnicalSignalIcon = (signal) => {
  switch (signal) {
    case 'STRONG_BUY': return '🚀';
    case 'BUY': return '📈';
    case 'NEUTRAL': return '➡️';
    case 'SELL': return '📉';
    case 'STRONG_SELL': return '🔻';
    default: return '➡️';
  }
};

const getTechnicalSignalLabel = (signal) => {
  switch (signal) {
    case 'STRONG_BUY': return '강력 매수';
    case 'BUY': return '매수';
    case 'NEUTRAL': return '중립';
    case 'SELL': return '매도';
    case 'STRONG_SELL': return '강력 매도';
    default: return '-';
  }
};

const getSignalCardClass = (signal) => {
  switch (signal) {
    case 'STRONG_BUY': return 'strong-buy';
    case 'BUY': return 'buy';
    case 'NEUTRAL': return 'neutral';
    case 'SELL': return 'sell';
    case 'STRONG_SELL': return 'strong-sell';
    default: return 'neutral';
  }
};

const getRsiClass = (status) => {
  switch (status) {
    case 'OVERBOUGHT': return 'overbought';
    case 'OVERSOLD': return 'oversold';
    default: return 'neutral';
  }
};

const getRsiLabel = (status) => {
  switch (status) {
    case 'OVERBOUGHT': return '과열';
    case 'OVERSOLD': return '침체';
    case 'NEUTRAL': return '중립';
    default: return '';
  }
};

onMounted(() => {
  // 기본 종목 분석 (삼성전자)
  analyzeQuickStock('005930');
});
</script>

<style scoped>
.technical-analysis-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1000px;
  margin: 0 auto;
  background: #0f0f23;
  border-radius: 20px;
  padding: 2rem;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid #2a2a4a;
}

.page-header {
  text-align: center;
  margin-bottom: 2rem;
  position: relative;
}

.back-button {
  position: absolute;
  left: 0;
  top: 0;
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.75rem 1.5rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s;
}

.back-button:hover {
  background: #5a5a9a;
  transform: translateX(-5px);
}

.page-header h1 {
  color: #fff;
  margin-bottom: 0.5rem;
}

.subtitle {
  color: #888;
  font-size: 1.1rem;
}

/* 검색 섹션 */
.search-section {
  margin-bottom: 2rem;
}

.search-box {
  display: flex;
  gap: 0.5rem;
  max-width: 500px;
  margin: 0 auto;
}

.search-box input {
  flex: 1;
  padding: 1rem 1.5rem;
  border: 2px solid #3a3a5a;
  border-radius: 12px;
  font-size: 1rem;
  background: #1a1a3a;
  color: #fff;
  transition: all 0.3s;
}

.search-box input:focus {
  outline: none;
  border-color: #667eea;
}

.search-btn {
  padding: 1rem 2rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 12px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
}

.search-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 5px 20px rgba(102, 126, 234, 0.4);
}

/* 분석 결과 */
.analysis-result {
  margin-bottom: 2rem;
}

.stock-info-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  background: #1a1a3a;
  border-radius: 15px;
  margin-bottom: 1.5rem;
}

.stock-title h2 {
  color: #fff;
  margin: 0;
  font-size: 1.5rem;
}

.stock-code {
  color: #888;
  font-family: monospace;
  font-size: 0.9rem;
}

.stock-price {
  text-align: right;
}

.current-price {
  display: block;
  font-size: 1.5rem;
  font-weight: 700;
  color: #fff;
}

.change-rate {
  font-size: 1rem;
  font-weight: 600;
}

.change-rate.positive {
  color: #e53e3e;
}

.change-rate.negative {
  color: #3182ce;
}

/* 신호 카드 */
.signal-card {
  padding: 1.5rem;
  border-radius: 15px;
  margin-bottom: 1.5rem;
}

.signal-card.strong-buy {
  background: linear-gradient(135deg, rgba(229, 62, 62, 0.2) 0%, rgba(197, 48, 48, 0.2) 100%);
  border: 2px solid #e53e3e;
}

.signal-card.buy {
  background: linear-gradient(135deg, rgba(237, 137, 54, 0.2) 0%, rgba(221, 107, 32, 0.2) 100%);
  border: 2px solid #ed8936;
}

.signal-card.neutral {
  background: linear-gradient(135deg, rgba(113, 128, 150, 0.2) 0%, rgba(74, 85, 104, 0.2) 100%);
  border: 2px solid #718096;
}

.signal-card.sell {
  background: linear-gradient(135deg, rgba(49, 130, 206, 0.2) 0%, rgba(43, 108, 176, 0.2) 100%);
  border: 2px solid #3182ce;
}

.signal-card.strong-sell {
  background: linear-gradient(135deg, rgba(43, 108, 176, 0.2) 0%, rgba(26, 54, 93, 0.2) 100%);
  border: 2px solid #2b6cb0;
}

.signal-main {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1rem;
}

.signal-icon {
  font-size: 2.5rem;
}

.signal-info {
  display: flex;
  flex-direction: column;
}

.signal-label {
  font-size: 1.5rem;
  font-weight: 700;
  color: #fff;
}

.signal-score {
  color: #aaa;
  font-size: 0.9rem;
}

.signal-description {
  padding: 1rem;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 10px;
  color: #ccc;
  font-size: 0.95rem;
}

/* 지표 섹션 */
.indicator-section {
  background: #1a1a3a;
  border-radius: 15px;
  padding: 1.5rem;
  margin-bottom: 1.5rem;
}

.indicator-section h3 {
  color: #fff;
  margin: 0 0 1rem 0;
  font-size: 1.1rem;
  border-bottom: 1px solid #2a2a4a;
  padding-bottom: 0.5rem;
}

/* 이동평균선 카드 */
.ma-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
}

.ma-card {
  background: #252540;
  border-radius: 10px;
  padding: 1rem;
  text-align: center;
  border: 2px solid #3a3a5a;
  transition: all 0.3s;
}

.ma-card.above {
  border-color: #e53e3e;
  background: rgba(229, 62, 62, 0.1);
}

.ma-period {
  display: block;
  font-size: 0.8rem;
  color: #888;
  margin-bottom: 0.5rem;
}

.ma-value {
  display: block;
  font-size: 1.1rem;
  font-weight: 700;
  color: #fff;
  margin-bottom: 0.25rem;
}

.ma-status {
  font-size: 0.8rem;
  color: #888;
}

.ma-card.above .ma-status {
  color: #e53e3e;
}

/* RSI */
.rsi-display {
  display: flex;
  align-items: center;
  gap: 2rem;
}

.rsi-gauge {
  flex: 1;
}

.rsi-bar {
  height: 20px;
  background: linear-gradient(90deg, #3182ce 0%, #48bb78 30%, #48bb78 70%, #e53e3e 100%);
  border-radius: 10px;
  position: relative;
}

.rsi-fill {
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  background: transparent;
}

.rsi-marker {
  position: absolute;
  top: -5px;
  width: 4px;
  height: 30px;
  background: #fff;
  border-radius: 2px;
  transform: translateX(-50%);
  box-shadow: 0 0 10px rgba(255, 255, 255, 0.5);
}

.rsi-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 0.5rem;
  font-size: 0.8rem;
  color: #888;
}

.rsi-labels .oversold-zone {
  color: #3182ce;
}

.rsi-labels .overbought-zone {
  color: #e53e3e;
}

.rsi-value-display {
  text-align: center;
  padding: 1rem 1.5rem;
  background: #252540;
  border-radius: 10px;
}

.rsi-number {
  display: block;
  font-size: 2rem;
  font-weight: 700;
  color: #fff;
}

.rsi-status {
  font-size: 0.9rem;
  color: #888;
}

.rsi-value-display.overbought .rsi-number {
  color: #e53e3e;
}

.rsi-value-display.oversold .rsi-number {
  color: #3182ce;
}

.rsi-value-display.neutral .rsi-number {
  color: #48bb78;
}

/* 매매 시그널 그리드 */
.signals-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
}

.signal-item {
  background: #252540;
  border-radius: 10px;
  padding: 1rem;
  text-align: center;
  border: 2px solid #3a3a5a;
}

.signal-item.active {
  border-color: #48bb78;
  background: rgba(72, 187, 120, 0.1);
}

.signal-item.active.negative {
  border-color: #e53e3e;
  background: rgba(229, 62, 62, 0.1);
}

.signal-name {
  display: block;
  font-size: 0.85rem;
  color: #888;
  margin-bottom: 0.5rem;
}

.signal-value {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
}

.signal-item.active .signal-value {
  color: #48bb78;
}

.signal-item.active.negative .signal-value {
  color: #e53e3e;
}

/* 공매도 정보 */
.short-info-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.short-info-item {
  background: #252540;
  border-radius: 10px;
  padding: 1rem;
  text-align: center;
}

.short-info-item .label {
  display: block;
  font-size: 0.85rem;
  color: #888;
  margin-bottom: 0.5rem;
}

.short-info-item .value {
  font-size: 1.2rem;
  font-weight: 700;
  color: #fff;
}

.short-info-item .value.highlight {
  color: #ed8936;
}

/* 빠른 분석 */
.quick-analysis-section {
  margin-top: 2rem;
}

.quick-analysis-section h3 {
  color: #fff;
  margin-bottom: 1rem;
}

.quick-stocks {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.quick-stock-btn {
  padding: 0.5rem 1rem;
  background: #2a2a4a;
  color: #fff;
  border: 1px solid #3a3a5a;
  border-radius: 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s;
}

.quick-stock-btn:hover {
  background: #3a3a5a;
  border-color: #667eea;
}

/* 결과 없음 */
.no-result {
  text-align: center;
  padding: 3rem;
  color: #666;
}

.no-result p {
  font-size: 1.2rem;
  margin-bottom: 0.5rem;
}

.no-result .hint {
  font-size: 0.9rem;
  color: #555;
}

/* 반응형 */
@media (max-width: 768px) {
  .technical-analysis-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    margin-top: 3rem;
    font-size: 1.5rem;
  }

  .stock-info-header {
    flex-direction: column;
    gap: 1rem;
    text-align: center;
  }

  .stock-price {
    text-align: center;
  }

  .ma-cards {
    grid-template-columns: repeat(2, 1fr);
  }

  .signals-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .short-info-grid {
    grid-template-columns: 1fr;
  }

  .rsi-display {
    flex-direction: column;
    gap: 1rem;
  }
}
</style>
