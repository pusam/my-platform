<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>수급 탐지기</h1>
        <div class="header-actions">
          <button @click="refreshData" class="btn btn-refresh" :disabled="loading">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :class="{ spinning: loading }">
              <path d="M21 12a9 9 0 11-9-9"/>
              <polyline points="21 3 21 9 15 9"/>
            </svg>
            새로고침
          </button>
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 설명 배너 -->
      <div class="info-banner whale">
        <div class="whale-icon">🐳</div>
        <div class="banner-text">
          <strong>큰 손(외국인/기관)의 움직임을 추적하세요</strong>
          <p>프로그램 매수가 쌓이는데 주가가 횡보? → 조만간 터질 신호!</p>
        </div>
      </div>

      <!-- 종목 검색 -->
      <div class="search-section">
        <div class="search-box">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            v-model="searchKeyword"
            @input="searchStocksDebounced"
            type="text"
            placeholder="종목명 또는 종목코드 검색..."
          />
        </div>
        <div v-if="searchResults.length > 0" class="search-results">
          <div
            v-for="stock in searchResults"
            :key="stock.stockCode"
            class="search-item"
            @click="addToWatchlist(stock)"
          >
            <span class="stock-name">{{ stock.stockName || stock.stockCode }}</span>
            <span class="stock-code">{{ stock.stockCode }}</span>
          </div>
        </div>
      </div>

      <!-- 로딩 -->
      <LoadingSpinner v-if="loading && stocks.length === 0" message="수급 데이터를 불러오는 중..." />

      <!-- 수급 신호 요약 -->
      <div v-if="stocks.length > 0" class="signal-summary">
        <div class="signal-card buy" v-if="buySignals.length > 0">
          <div class="signal-header">
            <span class="signal-icon">🚀</span>
            <span class="signal-title">매수 신호</span>
            <span class="signal-count">{{ buySignals.length }}종목</span>
          </div>
          <div class="signal-stocks">
            <span v-for="s in buySignals.slice(0, 5)" :key="s.stockCode" class="signal-tag">
              {{ s.stockName || s.stockCode }}
            </span>
          </div>
        </div>
        <div class="signal-card sell" v-if="sellSignals.length > 0">
          <div class="signal-header">
            <span class="signal-icon">⚠️</span>
            <span class="signal-title">매도 신호</span>
            <span class="signal-count">{{ sellSignals.length }}종목</span>
          </div>
          <div class="signal-stocks">
            <span v-for="s in sellSignals.slice(0, 5)" :key="s.stockCode" class="signal-tag">
              {{ s.stockName || s.stockCode }}
            </span>
          </div>
        </div>
      </div>

      <!-- 추적 중인 종목 헤더 -->
      <div v-if="stocks.length > 0" class="tracking-header">
        <h2>추적 중인 종목 ({{ stocks.length }}개)</h2>
        <p>종목을 클릭하면 상세 수급 추이를 볼 수 있습니다.</p>
      </div>

      <!-- 종목별 수급 카드 -->
      <div class="stocks-grid">
        <div
          v-for="stock in stocks"
          :key="stock.stockCode"
          class="stock-card"
          :class="{ 'buy-signal': stock.signal === 'BUY_SIGNAL', 'sell-signal': stock.signal === 'SELL_SIGNAL' }"
          @click="selectStock(stock)"
        >
          <!-- 카드 헤더 -->
          <div class="card-header">
            <div class="stock-info">
              <h3>{{ stock.stockName || stock.stockCode }}</h3>
              <span class="code">{{ stock.stockCode }}</span>
            </div>
            <div class="price-info">
              <span class="price">{{ formatCurrency(stock.currentPrice) }}</span>
              <span class="change" :class="getChangeClass(stock.changeRate)">
                {{ stock.changeRate > 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) || 0 }}%
              </span>
            </div>
          </div>

          <!-- API 연결 안됨 -->
          <div v-if="stock.foreignNetBuy == null && stock.institutionNetBuy == null" class="api-error-msg">
            <span class="error-icon">🔌</span>
            <span>API 연결 안됨</span>
          </div>

          <!-- 수급 바 차트 -->
          <div v-else class="supply-bars">
            <div class="bar-row">
              <span class="bar-label">외국인</span>
              <div class="bar-container">
                <div
                  class="bar"
                  :class="stock.foreignNetBuy >= 0 ? 'positive' : 'negative'"
                  :style="{ width: getBarWidth(stock.foreignNetBuy) + '%' }"
                ></div>
              </div>
              <span class="bar-value" :class="stock.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(stock.foreignNetBuy) }}
              </span>
            </div>
            <div class="bar-row">
              <span class="bar-label">기관</span>
              <div class="bar-container">
                <div
                  class="bar"
                  :class="stock.institutionNetBuy >= 0 ? 'positive' : 'negative'"
                  :style="{ width: getBarWidth(stock.institutionNetBuy) + '%' }"
                ></div>
              </div>
              <span class="bar-value" :class="stock.institutionNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(stock.institutionNetBuy) }}
              </span>
            </div>
            <div class="bar-row">
              <span class="bar-label">프로그램</span>
              <div class="bar-container">
                <div
                  class="bar program"
                  :class="stock.programNetBuy >= 0 ? 'positive' : 'negative'"
                  :style="{ width: getBarWidth(stock.programNetBuy) + '%' }"
                ></div>
              </div>
              <span class="bar-value" :class="stock.programNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(stock.programNetBuy) }}
              </span>
            </div>
          </div>

          <!-- 신호 -->
          <div v-if="stock.signal && stock.signal !== 'NEUTRAL'" class="signal-badge" :class="stock.signal.toLowerCase().replace('_', '-')">
            {{ stock.signal === 'BUY_SIGNAL' ? '🚀 매수 신호' : '⚠️ 매도 신호' }}
          </div>
          <div v-if="stock.signalReason" class="signal-reason">{{ stock.signalReason }}</div>

          <!-- 삭제 버튼 -->
          <button class="btn-remove" @click.stop="removeFromWatchlist(stock.stockCode)">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- 상세 모달 -->
      <div v-if="selectedStock" class="modal-overlay" @click="selectedStock = null">
        <div class="modal-content chart-modal" @click.stop>
          <div class="modal-header">
            <h2>{{ selectedStock.stockName || selectedStock.stockCode }} 수급 추이</h2>
            <button @click="selectedStock = null" class="modal-close">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <!-- 실시간 차트 -->
          <div class="chart-container">
            <canvas ref="chartCanvas"></canvas>
          </div>

          <!-- 상세 정보 -->
          <div class="detail-grid">
            <div class="detail-item">
              <span class="detail-label">외국인 순매수</span>
              <span class="detail-value" :class="selectedStock.foreignNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(selectedStock.foreignNetBuy) }} ({{ formatVolume(selectedStock.foreignNetVolume) }}주)
              </span>
            </div>
            <div class="detail-item">
              <span class="detail-label">기관 순매수</span>
              <span class="detail-value" :class="selectedStock.institutionNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(selectedStock.institutionNetBuy) }} ({{ formatVolume(selectedStock.institutionNetVolume) }}주)
              </span>
            </div>
            <div class="detail-item">
              <span class="detail-label">개인 순매수</span>
              <span class="detail-value" :class="selectedStock.individualNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(selectedStock.individualNetBuy) }} ({{ formatVolume(selectedStock.individualNetVolume) }}주)
              </span>
            </div>
            <div class="detail-item">
              <span class="detail-label">프로그램 순매수</span>
              <span class="detail-value" :class="selectedStock.programNetBuy >= 0 ? 'positive' : 'negative'">
                {{ formatBillion(selectedStock.programNetBuy) }} ({{ formatVolume(selectedStock.programNetVolume) }}주)
              </span>
            </div>
          </div>

          <div class="signal-detail" :class="selectedStock.signal?.toLowerCase().replace('_', '-')">
            <strong>{{ selectedStock.signal === 'BUY_SIGNAL' ? '🚀 매수 신호' : selectedStock.signal === 'SELL_SIGNAL' ? '⚠️ 매도 신호' : '➖ 중립' }}</strong>
            <p>{{ selectedStock.signalReason }}</p>
          </div>
        </div>
      </div>

      <!-- 빈 상태 -->
      <div v-if="!loading && stocks.length === 0" class="empty-state">
        <div class="empty-icon">🐳</div>
        <h3>관심 종목을 추가하세요</h3>
        <p>위 검색창에서 종목을 검색하여 수급을 추적할 수 있습니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { investorAPI, stockAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const stocks = ref([]);
const loading = ref(false);
const searchKeyword = ref('');
const searchResults = ref([]);
const selectedStock = ref(null);
const chartCanvas = ref(null);
let chartInstance = null;
let refreshInterval = null;
let searchTimeout = null;

// 관심종목 코드 목록
const watchlist = ref([]);

// 매수/매도 신호 필터
const buySignals = computed(() => stocks.value.filter(s => s.signal === 'BUY_SIGNAL'));
const sellSignals = computed(() => stocks.value.filter(s => s.signal === 'SELL_SIGNAL'));

const loadData = async () => {
  if (watchlist.value.length === 0) {
    // 기본 종목 로드
    await loadTopStocks();
    return;
  }

  try {
    loading.value = true;
    const response = await investorAPI.getWatchlist(watchlist.value);
    if (response.data.success) {
      stocks.value = response.data.data || [];
    }
  } catch (error) {
    console.error('수급 데이터 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const loadTopStocks = async () => {
  try {
    loading.value = true;
    const response = await investorAPI.getTopStocks();
    if (response.data.success) {
      stocks.value = response.data.data || [];
      watchlist.value = stocks.value.map(s => s.stockCode);
      saveWatchlist();
    }
  } catch (error) {
    console.error('주요 종목 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const refreshData = async () => {
  await loadData();
};

const searchStocksDebounced = () => {
  if (searchTimeout) clearTimeout(searchTimeout);
  searchTimeout = setTimeout(searchStocks, 300);
};

const searchStocks = async () => {
  if (!searchKeyword.value || searchKeyword.value.length < 2) {
    searchResults.value = [];
    return;
  }
  try {
    const response = await stockAPI.searchStocks(searchKeyword.value);
    searchResults.value = (response.data.data || []).slice(0, 10);
  } catch (error) {
    console.error('종목 검색 실패:', error);
  }
};

const addToWatchlist = async (stock) => {
  if (watchlist.value.includes(stock.stockCode)) {
    searchKeyword.value = '';
    searchResults.value = [];
    return;
  }
  watchlist.value.push(stock.stockCode);
  saveWatchlist();
  searchKeyword.value = '';
  searchResults.value = [];
  await loadData();
};

const removeFromWatchlist = (stockCode) => {
  watchlist.value = watchlist.value.filter(c => c !== stockCode);
  stocks.value = stocks.value.filter(s => s.stockCode !== stockCode);
  saveWatchlist();
};

const selectStock = async (stock) => {
  selectedStock.value = stock;
  await nextTick();
  drawChart();
};

const drawChart = () => {
  if (!chartCanvas.value || !selectedStock.value) return;

  const ctx = chartCanvas.value.getContext('2d');
  const timeSeries = selectedStock.value.timeSeries || [];

  // 캔버스 크기
  const width = chartCanvas.value.width = chartCanvas.value.parentElement.clientWidth;
  const height = chartCanvas.value.height = 200;
  const padding = 40;

  ctx.clearRect(0, 0, width, height);

  if (timeSeries.length < 2) {
    ctx.font = '14px sans-serif';
    ctx.fillStyle = '#999';
    ctx.textAlign = 'center';
    ctx.fillText('데이터 수집 중... (1분마다 갱신)', width / 2, height / 2);
    return;
  }

  // 데이터 범위 계산
  let maxVal = 0;
  let minVal = 0;
  timeSeries.forEach(d => {
    const vals = [d.foreignAccum || 0, d.institutionAccum || 0, d.programAccum || 0];
    maxVal = Math.max(maxVal, ...vals);
    minVal = Math.min(minVal, ...vals);
  });

  const range = Math.max(Math.abs(maxVal), Math.abs(minVal)) || 1;
  const chartWidth = width - padding * 2;
  const chartHeight = height - padding * 2;

  // 축 그리기
  ctx.strokeStyle = '#e0e0e0';
  ctx.lineWidth = 1;

  // 0선
  const zeroY = padding + chartHeight / 2;
  ctx.beginPath();
  ctx.moveTo(padding, zeroY);
  ctx.lineTo(width - padding, zeroY);
  ctx.stroke();

  // 라인 그리기 함수
  const drawLine = (data, field, color) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    data.forEach((d, i) => {
      const x = padding + (i / (data.length - 1)) * chartWidth;
      const val = d[field] || 0;
      const y = zeroY - (val / range) * (chartHeight / 2);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
  };

  // 외국인 (빨강)
  drawLine(timeSeries, 'foreignAccum', '#ef4444');
  // 기관 (파랑)
  drawLine(timeSeries, 'institutionAccum', '#3b82f6');
  // 프로그램 (보라)
  drawLine(timeSeries, 'programAccum', '#8b5cf6');

  // 범례
  const legends = [
    { label: '외국인', color: '#ef4444' },
    { label: '기관', color: '#3b82f6' },
    { label: '프로그램', color: '#8b5cf6' }
  ];
  let legendX = padding;
  legends.forEach(l => {
    ctx.fillStyle = l.color;
    ctx.fillRect(legendX, 10, 12, 12);
    ctx.fillStyle = '#666';
    ctx.font = '12px sans-serif';
    ctx.fillText(l.label, legendX + 16, 20);
    legendX += 70;
  });
};

const saveWatchlist = () => {
  localStorage.setItem('investorWatchlist', JSON.stringify(watchlist.value));
};

const loadWatchlist = () => {
  const saved = localStorage.getItem('investorWatchlist');
  if (saved) {
    watchlist.value = JSON.parse(saved);
  }
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', minimumFractionDigits: 0 }).format(value);
};

const formatBillion = (value) => {
  if (!value) return '0억';
  const num = parseFloat(value);
  return (num >= 0 ? '+' : '') + num.toFixed(1) + '억';
};

const formatVolume = (value) => {
  if (!value) return '0';
  const num = Math.abs(value);
  if (num >= 10000) return (value / 10000).toFixed(1) + '만';
  return value.toLocaleString();
};

const getBarWidth = (value) => {
  if (!value) return 0;
  // 최대 100억 기준
  return Math.min(Math.abs(value) / 100 * 100, 100);
};

const getChangeClass = (rate) => {
  if (!rate) return '';
  return rate > 0 ? 'positive' : rate < 0 ? 'negative' : '';
};

const goBack = () => router.back();
const logout = () => {
  UserManager.logout();
  router.push('/login');
};

onMounted(() => {
  loadWatchlist();
  loadData();
  // 1분마다 자동 갱신
  refreshInterval = setInterval(loadData, 60 * 1000);
});

onUnmounted(() => {
  if (refreshInterval) clearInterval(refreshInterval);
});

watch(selectedStock, () => {
  if (selectedStock.value) {
    nextTick(drawChart);
  }
});
</script>

<style scoped>
/* 배너 */
.info-banner.whale {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(139, 92, 246, 0.1) 100%);
  border: 2px solid rgba(59, 130, 246, 0.2);
  border-radius: 16px;
  margin-bottom: 24px;
}

.whale-icon {
  font-size: 48px;
}

.banner-text strong {
  display: block;
  font-size: 18px;
  color: #1f2937;
  margin-bottom: 4px;
}

.banner-text p {
  margin: 0;
  font-size: 14px;
  color: #6b7280;
}

/* 검색 */
.search-section {
  position: relative;
  margin-bottom: 24px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  background: rgba(255, 255, 255, 0.95);
  border: 2px solid #e5e7eb;
  border-radius: 14px;
  transition: all 0.3s;
}

.search-box:focus-within {
  border-color: #3b82f6;
  box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.1);
}

.search-box input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 16px;
  background: transparent;
}

.search-results {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 8px;
  background: white;
  border-radius: 14px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
  z-index: 100;
  max-height: 300px;
  overflow-y: auto;
}

.search-item {
  display: flex;
  justify-content: space-between;
  padding: 14px 20px;
  cursor: pointer;
  border-bottom: 1px solid #f3f4f6;
  transition: background 0.2s;
}

.search-item:hover {
  background: rgba(102, 126, 234, 0.05);
}

.search-item .stock-name {
  font-weight: 600;
  color: #1f2937;
}

.search-item .stock-code {
  color: #9ca3af;
  font-size: 13px;
}

/* 신호 요약 */
.signal-summary {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
}

.signal-card {
  flex: 1;
  padding: 16px 20px;
  border-radius: 14px;
}

.signal-card.buy {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.1) 0%, rgba(239, 68, 68, 0.05) 100%);
  border: 2px solid rgba(239, 68, 68, 0.2);
}

.signal-card.sell {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(59, 130, 246, 0.05) 100%);
  border: 2px solid rgba(59, 130, 246, 0.2);
}

.signal-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.signal-icon {
  font-size: 20px;
}

.signal-title {
  font-weight: 700;
  font-size: 16px;
}

.signal-count {
  margin-left: auto;
  background: rgba(0, 0, 0, 0.1);
  padding: 4px 10px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
}

.signal-stocks {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.signal-tag {
  background: rgba(255, 255, 255, 0.8);
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
}

/* 추적 종목 헤더 */
.tracking-header {
  margin-bottom: 20px;
  padding: 16px 20px;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.05) 0%, rgba(139, 92, 246, 0.05) 100%);
  border-radius: 12px;
  border-left: 4px solid #4F46E5;
}

.tracking-header h2 {
  margin: 0 0 4px 0;
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
}

.tracking-header p {
  margin: 0;
  font-size: 13px;
  color: #6b7280;
}

/* 종목 그리드 */
.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 20px;
}

.stock-card {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: all 0.3s;
  position: relative;
  border: 2px solid transparent;
}

.stock-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.12);
}

.stock-card.buy-signal {
  border-color: rgba(239, 68, 68, 0.3);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.95) 0%, rgba(239, 68, 68, 0.05) 100%);
}

.stock-card.sell-signal {
  border-color: rgba(59, 130, 246, 0.3);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.95) 0%, rgba(59, 130, 246, 0.05) 100%);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.stock-info h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
}

.stock-info .code {
  font-size: 13px;
  color: #9ca3af;
}

.price-info {
  text-align: right;
}

.price-info .price {
  display: block;
  font-size: 20px;
  font-weight: 700;
}

.price-info .change {
  font-size: 14px;
  font-weight: 600;
}

.price-info .change.positive { color: #ef4444; }
.price-info .change.negative { color: #3b82f6; }

/* 수급 바 */
.supply-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bar-label {
  width: 60px;
  font-size: 13px;
  font-weight: 500;
  color: #4b5563;
}

.bar-container {
  flex: 1;
  height: 8px;
  background: #f3f4f6;
  border-radius: 4px;
  overflow: hidden;
}

.bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s ease;
}

.bar.positive { background: linear-gradient(90deg, #ef4444, #f87171); }
.bar.negative { background: linear-gradient(90deg, #3b82f6, #60a5fa); }
.bar.program.positive { background: linear-gradient(90deg, #8b5cf6, #a78bfa); }
.bar.program.negative { background: linear-gradient(90deg, #6366f1, #818cf8); }

.bar-value {
  width: 60px;
  text-align: right;
  font-size: 13px;
  font-weight: 600;
}

.bar-value.positive { color: #ef4444; }
.bar-value.negative { color: #3b82f6; }

/* 신호 배지 */
.signal-badge {
  display: inline-block;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.signal-badge.buy-signal {
  background: linear-gradient(135deg, #ef4444 0%, #f87171 100%);
  color: white;
}

.signal-badge.sell-signal {
  background: linear-gradient(135deg, #3b82f6 0%, #60a5fa 100%);
  color: white;
}

.signal-reason {
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
}

/* API 연결 안됨 메시지 */
.api-error-msg {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  background: rgba(239, 68, 68, 0.05);
  border: 1px dashed rgba(239, 68, 68, 0.3);
  border-radius: 12px;
  color: #EF4444;
  font-size: 14px;
  font-weight: 500;
}

.api-error-msg .error-icon {
  font-size: 20px;
}

/* 삭제 버튼 */
.btn-remove {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 28px;
  height: 28px;
  border: none;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  opacity: 0;
  transition: all 0.2s;
}

.stock-card:hover .btn-remove {
  opacity: 1;
}

.btn-remove:hover {
  background: #ef4444;
  color: white;
}

/* 모달 */
.chart-modal {
  max-width: 700px;
  width: 90%;
}

.chart-container {
  background: #fafafa;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.detail-item {
  background: #f3f4f6;
  padding: 16px;
  border-radius: 12px;
}

.detail-label {
  display: block;
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 8px;
}

.detail-value {
  font-size: 18px;
  font-weight: 700;
}

.detail-value.positive { color: #ef4444; }
.detail-value.negative { color: #3b82f6; }

.signal-detail {
  padding: 16px 20px;
  border-radius: 12px;
  background: #f8f9fa;
}

.signal-detail.buy-signal {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.1) 0%, rgba(239, 68, 68, 0.05) 100%);
}

.signal-detail.sell-signal {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(59, 130, 246, 0.05) 100%);
}

.signal-detail strong {
  display: block;
  margin-bottom: 8px;
}

.signal-detail p {
  margin: 0;
  font-size: 14px;
  color: #4b5563;
}

/* 새로고침 버튼 */
.btn-refresh {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
}

.btn-refresh svg.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 빈 상태 */
.empty-state {
  text-align: center;
  padding: 60px 20px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.empty-state h3 {
  margin: 0 0 8px 0;
  font-size: 20px;
  color: #1f2937;
}

.empty-state p {
  margin: 0;
  color: #6b7280;
}

/* 반응형 */
@media (max-width: 768px) {
  .stocks-grid {
    grid-template-columns: 1fr;
  }

  .signal-summary {
    flex-direction: column;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
