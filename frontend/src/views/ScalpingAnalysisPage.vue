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

      <!-- 실시간 상태 표시줄 -->
      <div v-if="analysisData" class="realtime-status-bar" :class="{ active: autoRefresh }">
        <div class="status-indicator">
          <span class="status-dot" :class="{ pulsing: autoRefresh }"></span>
          <span class="status-text">
            {{ autoRefresh ? '실시간 체결 감시 중' : '실시간 감시 대기' }}
          </span>
        </div>
        <div class="status-meta">
          <span v-if="isMockData" class="mock-badge">📋 테스트 데이터</span>
          <span class="update-time">Update: {{ formatTime(lastUpdated) }}</span>
        </div>
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

        <!-- 투자자 순매수 (막대 차트) -->
        <div class="analysis-card investor-card">
          <h3>실시간 수급</h3>
          <div class="investor-bar-chart">
            <!-- 외국인 -->
            <div class="investor-bar-row">
              <span class="bar-label">🌍 외국인</span>
              <div class="bar-container">
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :class="analysisData.foreignNetBuy >= 0 ? 'positive' : 'negative'"
                    :style="{ width: getBarWidth(analysisData.foreignNetBuy) + '%' }"
                  ></div>
                </div>
              </div>
              <span class="bar-value" :class="analysisData.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.foreignNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.foreignNetBuy) }}
              </span>
            </div>
            <!-- 기관 -->
            <div class="investor-bar-row">
              <span class="bar-label">🏢 기관</span>
              <div class="bar-container">
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :class="analysisData.instNetBuy >= 0 ? 'positive' : 'negative'"
                    :style="{ width: getBarWidth(analysisData.instNetBuy) + '%' }"
                  ></div>
                </div>
              </div>
              <span class="bar-value" :class="analysisData.instNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.instNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.instNetBuy) }}
              </span>
            </div>
            <!-- 프로그램 -->
            <div class="investor-bar-row highlight">
              <span class="bar-label">💻 프로그램</span>
              <div class="bar-container">
                <div class="bar-track">
                  <div
                    class="bar-fill"
                    :class="analysisData.programNetBuy >= 0 ? 'positive' : 'negative'"
                    :style="{ width: getBarWidth(analysisData.programNetBuy) + '%' }"
                  ></div>
                </div>
              </div>
              <span class="bar-value" :class="analysisData.programNetBuy >= 0 ? 'positive' : 'negative'">
                {{ analysisData.programNetBuy >= 0 ? '+' : '' }}{{ formatBillion(analysisData.programNetBuy) }}
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
const isMockData = ref(false);
let refreshInterval = null;

// Mock Data (테스트용 삼성전자 데이터)
const getMockData = (code) => {
  const now = new Date();
  const hours = [];
  const programSeries = [];

  // 09:00 ~ 현재 시간까지 프로그램 매매 추이 생성
  for (let h = 9; h <= Math.min(now.getHours(), 15); h++) {
    for (let m = 0; m < 60; m += 30) {
      if (h === 15 && m > 30) break;
      const time = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
      // 점진적으로 증가하는 누적 순매수
      const baseValue = ((h - 9) * 60 + m) * 3 + Math.random() * 100;
      programSeries.push({
        time,
        value: Math.round(baseValue)
      });
    }
  }

  return {
    stockCode: code || '005930',
    stockName: code === '005930' ? '삼성전자' : '테스트 종목',
    currentPrice: 74200,
    changePrice: 1100,
    changeRate: 1.5,
    tradingVolume: 12500000,
    volumePower: 125.4,
    volumeSignal: 'BUY',
    foreignNetBuy: 200,
    instNetBuy: -50,
    programNetBuy: 520,
    programTrend: 'UP',
    programTradingSeries: programSeries
  };
};

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
    isMockData.value = false;
    const response = await scalpingAPI.getAnalysis(stockCode.value);

    if (response.data.success && response.data.data) {
      analysisData.value = response.data.data;
      lastUpdated.value = new Date();
      errorMessage.value = '';
    } else {
      // API 실패 시 Mock Data로 Fallback
      console.warn('API 응답 없음, Mock Data로 대체');
      applyMockData();
    }
  } catch (error) {
    console.error('분석 조회 오류:', error);
    // API 에러 시 Mock Data로 Fallback
    console.warn('API 에러 발생, Mock Data로 대체');
    applyMockData();
  } finally {
    loading.value = false;
  }
};

// Mock Data 적용
const applyMockData = () => {
  analysisData.value = getMockData(stockCode.value);
  lastUpdated.value = new Date();
  isMockData.value = true;
  errorMessage.value = '';
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
  return value.toFixed(0) + '억';
};

// 막대 차트 너비 계산 (최대값 기준 %)
const getBarWidth = (value) => {
  if (!value || !analysisData.value) return 0;
  const maxVal = Math.max(
    Math.abs(analysisData.value.foreignNetBuy || 0),
    Math.abs(analysisData.value.instNetBuy || 0),
    Math.abs(analysisData.value.programNetBuy || 0),
    1
  );
  return Math.min((Math.abs(value) / maxVal) * 100, 100);
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

/* 실시간 상태 표시줄 */
.realtime-status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 1px solid #2a2a4a;
  border-radius: 12px;
  padding: 12px 20px;
  margin-bottom: 1rem;
}

.realtime-status-bar.active {
  border-color: #22c55e;
  box-shadow: 0 0 20px rgba(34, 197, 94, 0.2);
}

.status-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #71717a;
}

.status-dot.pulsing {
  background: #22c55e;
  animation: pulse-glow 1.5s ease-in-out infinite;
}

@keyframes pulse-glow {
  0%, 100% {
    opacity: 1;
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.7);
  }
  50% {
    opacity: 0.8;
    box-shadow: 0 0 0 8px rgba(34, 197, 94, 0);
  }
}

.status-text {
  color: #fff;
  font-weight: 600;
  font-size: 0.95rem;
}

.realtime-status-bar.active .status-text {
  color: #22c55e;
}

.status-meta {
  display: flex;
  align-items: center;
  gap: 16px;
}

.mock-badge {
  background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
  color: #fff;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.8rem;
  font-weight: 600;
}

.update-time {
  color: #888;
  font-size: 0.9rem;
  font-family: 'Monaco', 'Consolas', monospace;
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

/* 투자자 막대 차트 */
.investor-bar-chart {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.investor-bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: #0f0f23;
  border-radius: 10px;
}

.investor-bar-row.highlight {
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a4a 100%);
  border: 1px solid #3a3a5a;
}

.bar-label {
  width: 100px;
  color: #ccc;
  font-size: 0.9rem;
  flex-shrink: 0;
}

.bar-container {
  flex: 1;
}

.bar-track {
  height: 24px;
  background: #27272a;
  border-radius: 12px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 12px;
  transition: width 0.5s ease;
}

.bar-fill.positive {
  background: linear-gradient(90deg, #ef4444 0%, #dc2626 100%);
  box-shadow: 0 0 10px rgba(239, 68, 68, 0.4);
}

.bar-fill.negative {
  background: linear-gradient(90deg, #3b82f6 0%, #2563eb 100%);
  box-shadow: 0 0 10px rgba(59, 130, 246, 0.4);
}

.bar-value {
  width: 80px;
  text-align: right;
  font-size: 1.1rem;
  font-weight: 700;
  font-family: 'Monaco', 'Consolas', monospace;
  flex-shrink: 0;
}

.bar-value.positive { color: #ef4444; }
.bar-value.negative { color: #3b82f6; }

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
