<template>
  <div class="scalping-page">
    <LoadingSpinner v-if="loading && !analysisData" />

    <div class="content-wrapper">
      <div class="page-header">
        <BackButton />
        <h1>단타 분석</h1>
        <p class="subtitle">실시간 체결강도 및 프로그램 매매 추이</p>
      </div>

      <!-- 종목 검색 -->
      <div class="search-section">
        <div class="search-box">
          <input
            v-model="searchInput"
            @keyup.enter="searchStock"
            placeholder="종목코드 또는 종목명 입력 (예: 005930, 삼성전자)"
            class="search-input"
          />
          <button @click="searchStock" class="search-button" :disabled="loading">
            {{ loading ? '조회 중...' : '조회' }}
          </button>
        </div>
        <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
      </div>

      <!-- 자동 갱신 설정 -->
      <div v-if="analysisData" class="refresh-section">
        <label class="auto-refresh-label">
          <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
          10초 자동 갱신
        </label>
        <span v-if="lastUpdated" class="last-updated">
          마지막 갱신: {{ formatTime(lastUpdated) }}
        </span>
      </div>

      <!-- 종목 정보 카드 -->
      <div v-if="analysisData" class="stock-info-card">
        <div class="stock-header">
          <div class="stock-name-section">
            <h2>{{ analysisData.stockName || analysisData.stockCode }}</h2>
            <span class="stock-code">{{ analysisData.stockCode }}</span>
          </div>
          <div class="price-section">
            <span class="current-price">{{ formatNumber(analysisData.currentPrice) }}원</span>
            <span class="change" :class="changeClass">
              {{ analysisData.changePrice >= 0 ? '+' : '' }}{{ formatNumber(analysisData.changePrice) }}원
              ({{ analysisData.changeRate >= 0 ? '+' : '' }}{{ analysisData.changeRate?.toFixed(2) }}%)
            </span>
          </div>
        </div>
        <div class="stock-meta">
          <span class="meta-item">
            <span class="label">거래량</span>
            <span class="value">{{ formatVolume(analysisData.tradingVolume) }}</span>
          </span>
        </div>
      </div>

      <!-- 분석 그리드 -->
      <div v-if="analysisData" class="analysis-grid">
        <!-- 체결강도 게이지 -->
        <div class="analysis-card">
          <VolumePowerGauge
            :volumePower="analysisData.volumePower"
            :signal="analysisData.volumeSignal"
          />
        </div>

        <!-- 투자자 순매수 -->
        <div class="analysis-card investor-card">
          <h3>투자자 순매수</h3>
          <div class="investor-grid">
            <div class="investor-item">
              <span class="investor-label">외국인</span>
              <span class="investor-value" :class="analysisData.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.foreignNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.foreignNetBuy) }}
              </span>
            </div>
            <div class="investor-item">
              <span class="investor-label">기관</span>
              <span class="investor-value" :class="analysisData.instNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.instNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.instNetBuy) }}
              </span>
            </div>
          </div>
        </div>

        <!-- 프로그램 매매 차트 -->
        <div class="analysis-card chart-card">
          <ProgramTradingChart
            :series="analysisData.programTradingSeries || []"
            :programNetBuy="analysisData.programNetBuy"
            :programTrend="analysisData.programTrend"
          />
        </div>
      </div>

      <!-- 사용 가이드 -->
      <div v-if="!analysisData" class="guide-section">
        <h3>단타 분석 사용법</h3>
        <div class="guide-grid">
          <div class="guide-item">
            <span class="guide-icon">📊</span>
            <div class="guide-content">
              <h4>체결강도</h4>
              <p>매수/매도 체결량 비율로 100%가 기준점입니다. 120% 이상이면 매수세 강함, 80% 이하면 매도세 강함을 의미합니다.</p>
            </div>
          </div>
          <div class="guide-item">
            <span class="guide-icon">📈</span>
            <div class="guide-content">
              <h4>프로그램 매매</h4>
              <p>09:00부터 현재까지 프로그램의 누적 순매수 추이입니다. 양수(빨간색)는 순매수, 음수(파란색)는 순매도를 의미합니다.</p>
            </div>
          </div>
          <div class="guide-item">
            <span class="guide-icon">👥</span>
            <div class="guide-content">
              <h4>투자자 순매수</h4>
              <p>외국인과 기관의 당일 누적 순매수 금액(억원)입니다. 두 세력이 동시에 순매수하는 종목에 주목하세요.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { scalpingAPI, stockAPI } from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import VolumePowerGauge from '../components/VolumePowerGauge.vue';
import ProgramTradingChart from '../components/ProgramTradingChart.vue';
import BackButton from '../components/BackButton.vue';

const router = useRouter();
const loading = ref(false);
const searchInput = ref('');
const stockCode = ref('');
const analysisData = ref(null);
const errorMessage = ref('');
const autoRefresh = ref(false);
const lastUpdated = ref(null);
let refreshInterval = null;

const searchStock = async () => {
  if (!searchInput.value.trim()) {
    errorMessage.value = '종목코드 또는 종목명을 입력해주세요.';
    return;
  }

  loading.value = true;
  errorMessage.value = '';

  try {
    let code = searchInput.value.trim();

    // 6자리 숫자가 아니면 종목 검색
    if (!/^\d{6}$/.test(code)) {
      const searchResponse = await stockAPI.searchStocks(code);
      if (searchResponse.data.success && searchResponse.data.data?.length > 0) {
        code = searchResponse.data.data[0].stockCode;
      } else {
        errorMessage.value = '종목을 찾을 수 없습니다.';
        loading.value = false;
        return;
      }
    }

    stockCode.value = code;
    await fetchAnalysis();
  } catch (error) {
    console.error('검색 오류:', error);
    errorMessage.value = '종목 검색에 실패했습니다.';
  } finally {
    loading.value = false;
  }
};

const fetchAnalysis = async () => {
  if (!stockCode.value) return;

  try {
    loading.value = true;
    const response = await scalpingAPI.getAnalysis(stockCode.value);

    if (response.data.success) {
      analysisData.value = response.data.data;
      lastUpdated.value = new Date();
      errorMessage.value = '';
    } else {
      errorMessage.value = response.data.message || '데이터 조회 실패';
    }
  } catch (error) {
    console.error('분석 조회 오류:', error);
    errorMessage.value = '데이터 조회에 실패했습니다.';
  } finally {
    loading.value = false;
  }
};

const refreshVolumePower = async () => {
  if (!stockCode.value || !autoRefresh.value) return;

  try {
    const response = await scalpingAPI.refreshVolumePower(stockCode.value);
    if (response.data.success) {
      // 체결강도 관련 데이터만 갱신
      const data = response.data.data;
      if (analysisData.value) {
        analysisData.value.volumePower = data.volumePower;
        analysisData.value.volumeSignal = data.volumeSignal;
        analysisData.value.currentPrice = data.currentPrice;
        analysisData.value.changePrice = data.changePrice;
        analysisData.value.changeRate = data.changeRate;
        analysisData.value.tradingVolume = data.tradingVolume;
        lastUpdated.value = new Date();
      }
    }
  } catch (error) {
    console.error('갱신 오류:', error);
  }
};

const toggleAutoRefresh = () => {
  if (autoRefresh.value) {
    startAutoRefresh();
  } else {
    stopAutoRefresh();
  }
};

const startAutoRefresh = () => {
  if (refreshInterval) clearInterval(refreshInterval);
  refreshInterval = setInterval(refreshVolumePower, 10000); // 10초
};

const stopAutoRefresh = () => {
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
};

const formatNumber = (value) => {
  if (value == null) return '0';
  return Number(value).toLocaleString('ko-KR');
};

const formatVolume = (value) => {
  if (value == null) return '0';
  if (value >= 1000000) {
    return (value / 1000000).toFixed(1) + 'M';
  } else if (value >= 1000) {
    return (value / 1000).toFixed(1) + 'K';
  }
  return value.toString();
};

const formatBillion = (value) => {
  if (value == null) return '0억';
  return value.toFixed(2) + '억';
};

const formatTime = (date) => {
  if (!date) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

const changeClass = ref('');

// 주가 변동 클래스 계산
const updateChangeClass = () => {
  if (!analysisData.value) return '';
  const change = analysisData.value.changePrice;
  if (change > 0) changeClass.value = 'positive';
  else if (change < 0) changeClass.value = 'negative';
  else changeClass.value = 'neutral';
};

onMounted(() => {
  // URL 파라미터에서 종목코드 확인
  const urlParams = new URLSearchParams(window.location.search);
  const code = urlParams.get('code');
  if (code && /^\d{6}$/.test(code)) {
    searchInput.value = code;
    searchStock();
  }
});

onUnmounted(() => {
  stopAutoRefresh();
});

// analysisData 변경 시 changeClass 갱신
import { watch } from 'vue';
watch(analysisData, updateChangeClass, { deep: true });
</script>

<style scoped>
.scalping-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1200px;
  margin: 0 auto;
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
  font-size: 2rem;
}

.subtitle {
  color: #888;
  font-size: 1.1rem;
}

/* 검색 섹션 */
.search-section {
  margin-bottom: 1.5rem;
}

.search-box {
  display: flex;
  gap: 12px;
  max-width: 600px;
  margin: 0 auto;
}

.search-input {
  flex: 1;
  padding: 14px 20px;
  border: 2px solid #2a2a4a;
  border-radius: 12px;
  background: #0f0f23;
  color: #fff;
  font-size: 1rem;
  transition: border-color 0.3s;
}

.search-input:focus {
  outline: none;
  border-color: #4a4a8a;
}

.search-input::placeholder {
  color: #666;
}

.search-button {
  padding: 14px 28px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.search-button:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.search-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-message {
  text-align: center;
  color: #ef4444;
  margin-top: 12px;
}

/* 자동 갱신 섹션 */
.refresh-section {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 24px;
  margin-bottom: 1.5rem;
}

.auto-refresh-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #ccc;
  cursor: pointer;
}

.auto-refresh-label input {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.last-updated {
  color: #888;
  font-size: 0.9rem;
}

/* 종목 정보 카드 */
.stock-info-card {
  background: #0f0f23;
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 1.5rem;
  border: 1px solid #2a2a4a;
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 16px;
}

.stock-name-section h2 {
  margin: 0;
  color: #fff;
  font-size: 1.5rem;
}

.stock-code {
  color: #888;
  font-size: 0.9rem;
  margin-left: 8px;
}

.price-section {
  text-align: right;
}

.current-price {
  display: block;
  font-size: 1.8rem;
  font-weight: 700;
  color: #fff;
  font-family: 'Monaco', 'Consolas', monospace;
}

.change {
  font-size: 1.1rem;
  font-weight: 600;
}

.change.positive { color: #ef4444; }
.change.negative { color: #3b82f6; }
.change.neutral { color: #888; }

.stock-meta {
  display: flex;
  gap: 24px;
}

.meta-item {
  display: flex;
  gap: 8px;
}

.meta-item .label {
  color: #888;
}

.meta-item .value {
  color: #fff;
  font-weight: 500;
}

/* 분석 그리드 */
.analysis-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(350px, 1fr));
  gap: 20px;
  margin-bottom: 2rem;
}

.analysis-card.chart-card {
  grid-column: 1 / -1;
}

.investor-card {
  background: #1a1a3a;
  border-radius: 16px;
  padding: 24px;
  border: 1px solid #2a2a4a;
}

.investor-card h3 {
  margin: 0 0 20px 0;
  color: #fff;
  font-size: 1.2rem;
}

.investor-grid {
  display: flex;
  gap: 24px;
}

.investor-item {
  flex: 1;
  text-align: center;
  padding: 16px;
  background: #0f0f23;
  border-radius: 12px;
}

.investor-label {
  display: block;
  color: #888;
  font-size: 0.9rem;
  margin-bottom: 8px;
}

.investor-value {
  display: block;
  font-size: 1.5rem;
  font-weight: 700;
  font-family: 'Monaco', 'Consolas', monospace;
}

.investor-value.positive { color: #ef4444; }
.investor-value.negative { color: #3b82f6; }

/* 가이드 섹션 */
.guide-section {
  background: #0f0f23;
  border-radius: 16px;
  padding: 32px;
  border: 1px solid #2a2a4a;
}

.guide-section h3 {
  margin: 0 0 24px 0;
  color: #fff;
  font-size: 1.3rem;
  text-align: center;
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 24px;
}

.guide-item {
  display: flex;
  gap: 16px;
  padding: 20px;
  background: #1a1a3a;
  border-radius: 12px;
}

.guide-icon {
  font-size: 2rem;
  flex-shrink: 0;
}

.guide-content h4 {
  margin: 0 0 8px 0;
  color: #fff;
  font-size: 1.1rem;
}

.guide-content p {
  margin: 0;
  color: #888;
  font-size: 0.9rem;
  line-height: 1.5;
}

/* 반응형 */
@media (max-width: 768px) {
  .scalping-page {
    padding: 1rem;
  }

  .page-header h1 {
    font-size: 1.5rem;
    margin-top: 3rem;
  }

  .search-box {
    flex-direction: column;
  }

  .stock-header {
    flex-direction: column;
    text-align: center;
  }

  .price-section {
    text-align: center;
  }

  .analysis-grid {
    grid-template-columns: 1fr;
  }

  .investor-grid {
    flex-direction: column;
  }
}
</style>
