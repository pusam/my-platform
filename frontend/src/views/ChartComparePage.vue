<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>지수 vs 내 종목</h1>
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
      <div class="info-banner">
        <div class="banner-icon">📊</div>
        <div class="banner-text">
          <strong>지수(노란선) vs 내 종목(빨간선) 비교</strong>
          <p>지수가 빠지는데 종목이 버티면 <span class="strong-text">개쎈 놈!</span> 지수 반등 시 급등 가능<br>
          지수는 오르는데 종목만 기어가면 <span class="weak-text">버려야 할 종목</span></p>
        </div>
      </div>

      <!-- 종목 검색 + 지수 선택 -->
      <div class="control-bar">
        <div class="search-box">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            v-model="searchKeyword"
            @input="searchStocksDebounced"
            type="text"
            placeholder="비교할 종목 검색..."
          />
        </div>
        <div class="index-selector">
          <button
            :class="{ active: selectedIndex === '1001' }"
            @click="selectIndex('1001')"
          >
            코스닥
          </button>
          <button
            :class="{ active: selectedIndex === '0001' }"
            @click="selectIndex('0001')"
          >
            코스피
          </button>
        </div>
      </div>

      <!-- 검색 결과 -->
      <div v-if="searchResults.length > 0" class="search-results">
        <div
          v-for="stock in searchResults"
          :key="stock.stockCode"
          class="search-item"
          @click="selectStock(stock)"
        >
          <span class="stock-name">{{ stock.stockName }}</span>
          <span class="stock-code">{{ stock.stockCode }}</span>
        </div>
      </div>

      <!-- 로딩 -->
      <LoadingSpinner v-if="loading" message="차트 데이터를 불러오는 중..." />

      <!-- 메인 차트 영역 -->
      <div v-if="chartData && !loading" class="chart-section">
        <!-- 분석 결과 카드 -->
        <div class="analysis-card" :class="chartData.analysis?.toLowerCase()">
          <div class="analysis-header">
            <span class="analysis-icon">
              {{ chartData.analysis === 'STRONG' ? '🔥' : chartData.analysis === 'WEAK' ? '⚠️' : '➖' }}
            </span>
            <span class="analysis-title">
              {{ chartData.analysis === 'STRONG' ? '강세 종목' : chartData.analysis === 'WEAK' ? '약세 종목' : '중립' }}
            </span>
            <span class="relative-strength" :class="chartData.relativeStrength >= 0 ? 'positive' : 'negative'">
              상대강도: {{ chartData.relativeStrength >= 0 ? '+' : '' }}{{ chartData.relativeStrength?.toFixed(2) }}%p
            </span>
          </div>
          <p class="analysis-reason">{{ chartData.analysisReason }}</p>
        </div>

        <!-- 현재가 정보 -->
        <div class="price-info-grid">
          <div class="price-card stock">
            <div class="price-label">{{ chartData.stockName }}</div>
            <div class="price-value">{{ formatCurrency(chartData.stockPrice) }}</div>
            <div class="price-change" :class="chartData.stockChangeRate >= 0 ? 'positive' : 'negative'">
              {{ chartData.stockChangeRate >= 0 ? '+' : '' }}{{ chartData.stockChangeRate?.toFixed(2) }}%
            </div>
          </div>
          <div class="vs-badge">VS</div>
          <div class="price-card index">
            <div class="price-label">{{ chartData.indexName }}</div>
            <div class="price-value">{{ chartData.indexPrice?.toFixed(2) }}</div>
            <div class="price-change" :class="chartData.indexChangeRate >= 0 ? 'positive' : 'negative'">
              {{ chartData.indexChangeRate >= 0 ? '+' : '' }}{{ chartData.indexChangeRate?.toFixed(2) }}%
            </div>
          </div>
        </div>

        <!-- 오버레이 차트 -->
        <div class="chart-container">
          <div class="chart-legend">
            <span class="legend-item stock"><span class="legend-color"></span>{{ chartData.stockName }}</span>
            <span class="legend-item index"><span class="legend-color"></span>{{ chartData.indexName }}</span>
            <span class="legend-item zero"><span class="legend-color"></span>기준선 (시초가)</span>
          </div>
          <canvas ref="chartCanvas"></canvas>
        </div>

        <!-- 갭 차트 (상대강도 추이) -->
        <div class="gap-chart-container">
          <h4>상대강도 추이 (종목 - 지수)</h4>
          <canvas ref="gapCanvas"></canvas>
        </div>
      </div>

      <!-- 빈 상태 -->
      <div v-if="!chartData && !loading" class="empty-state">
        <div class="empty-icon">📈</div>
        <h3>종목을 검색하세요</h3>
        <p>지수와 비교할 종목을 검색하여 상대강도를 분석합니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { chartAPI, stockAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const chartData = ref(null);
const loading = ref(false);
const searchKeyword = ref('');
const searchResults = ref([]);
const selectedIndex = ref('1001'); // 기본: 코스닥
const selectedStock = ref(null);
const chartCanvas = ref(null);
const gapCanvas = ref(null);
let searchTimeout = null;

const loadChartData = async () => {
  if (!selectedStock.value) return;

  try {
    loading.value = true;
    const response = await chartAPI.getCompareChart(selectedStock.value.stockCode, selectedIndex.value);
    if (response.data.success) {
      chartData.value = response.data.data;
      await nextTick();
      drawCharts();
    }
  } catch (error) {
    console.error('차트 데이터 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const refreshData = () => {
  loadChartData();
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

const selectStock = (stock) => {
  selectedStock.value = stock;
  searchKeyword.value = '';
  searchResults.value = [];
  loadChartData();
};

const selectIndex = (indexCode) => {
  selectedIndex.value = indexCode;
  if (selectedStock.value) {
    loadChartData();
  }
};

const drawCharts = () => {
  if (!chartData.value?.chartData) return;
  drawOverlayChart();
  drawGapChart();
};

const drawOverlayChart = () => {
  const canvas = chartCanvas.value;
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  const data = chartData.value.chartData;

  const width = canvas.width = canvas.parentElement.clientWidth - 40;
  const height = canvas.height = 300;
  const padding = { top: 30, right: 60, bottom: 40, left: 60 };

  ctx.clearRect(0, 0, width, height);

  if (data.length < 2) {
    ctx.font = '14px sans-serif';
    ctx.fillStyle = '#999';
    ctx.textAlign = 'center';
    ctx.fillText('데이터가 부족합니다', width / 2, height / 2);
    return;
  }

  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;

  // 범위 계산
  let minRate = 0, maxRate = 0;
  data.forEach(d => {
    const idx = parseFloat(d.indexRate) || 0;
    const stk = parseFloat(d.stockRate) || 0;
    minRate = Math.min(minRate, idx, stk);
    maxRate = Math.max(maxRate, idx, stk);
  });

  const range = Math.max(Math.abs(minRate), Math.abs(maxRate), 1) * 1.2;

  // 배경
  ctx.fillStyle = '#fafafa';
  ctx.fillRect(padding.left, padding.top, chartWidth, chartHeight);

  // 0선 (시초가 기준)
  const zeroY = padding.top + chartHeight / 2;
  ctx.strokeStyle = '#e0e0e0';
  ctx.lineWidth = 2;
  ctx.setLineDash([5, 5]);
  ctx.beginPath();
  ctx.moveTo(padding.left, zeroY);
  ctx.lineTo(width - padding.right, zeroY);
  ctx.stroke();
  ctx.setLineDash([]);

  // Y축 눈금
  ctx.fillStyle = '#999';
  ctx.font = '11px sans-serif';
  ctx.textAlign = 'right';
  for (let i = -2; i <= 2; i++) {
    const rate = (range / 2) * i;
    const y = padding.top + chartHeight / 2 - (rate / range) * chartHeight;
    ctx.fillText(rate.toFixed(1) + '%', padding.left - 8, y + 4);

    ctx.strokeStyle = '#eee';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(width - padding.right, y);
    ctx.stroke();
  }

  // 라인 그리기 함수
  const drawLine = (field, color, lineWidth = 2) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = lineWidth;
    ctx.beginPath();
    data.forEach((d, i) => {
      const x = padding.left + (i / (data.length - 1)) * chartWidth;
      const rate = parseFloat(d[field]) || 0;
      const y = padding.top + chartHeight / 2 - (rate / range) * chartHeight;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
  };

  // 지수 (노란색)
  drawLine('indexRate', '#F59E0B', 3);
  // 종목 (빨간색)
  drawLine('stockRate', '#EF4444', 3);

  // X축 시간
  ctx.fillStyle = '#999';
  ctx.textAlign = 'center';
  const step = Math.ceil(data.length / 6);
  data.forEach((d, i) => {
    if (i % step === 0 || i === data.length - 1) {
      const x = padding.left + (i / (data.length - 1)) * chartWidth;
      ctx.fillText(d.time, x, height - padding.bottom + 20);
    }
  });

  // 현재 값 표시
  if (data.length > 0) {
    const last = data[data.length - 1];
    const lastX = width - padding.right;

    // 지수 현재값
    const idxY = padding.top + chartHeight / 2 - ((parseFloat(last.indexRate) || 0) / range) * chartHeight;
    ctx.fillStyle = '#F59E0B';
    ctx.font = 'bold 12px sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText((parseFloat(last.indexRate) || 0).toFixed(2) + '%', lastX + 5, idxY + 4);

    // 종목 현재값
    const stkY = padding.top + chartHeight / 2 - ((parseFloat(last.stockRate) || 0) / range) * chartHeight;
    ctx.fillStyle = '#EF4444';
    ctx.fillText((parseFloat(last.stockRate) || 0).toFixed(2) + '%', lastX + 5, stkY + 4);
  }
};

const drawGapChart = () => {
  const canvas = gapCanvas.value;
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  const data = chartData.value.chartData;

  const width = canvas.width = canvas.parentElement.clientWidth - 40;
  const height = canvas.height = 120;
  const padding = { top: 20, right: 40, bottom: 30, left: 60 };

  ctx.clearRect(0, 0, width, height);

  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;

  // 범위 계산
  let maxGap = 1;
  data.forEach(d => {
    maxGap = Math.max(maxGap, Math.abs(parseFloat(d.gap) || 0));
  });
  maxGap *= 1.2;

  // 0선
  const zeroY = padding.top + chartHeight / 2;
  ctx.strokeStyle = '#e0e0e0';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(padding.left, zeroY);
  ctx.lineTo(width - padding.right, zeroY);
  ctx.stroke();

  // 영역 그리기
  ctx.beginPath();
  data.forEach((d, i) => {
    const x = padding.left + (i / (data.length - 1)) * chartWidth;
    const gap = parseFloat(d.gap) || 0;
    const y = zeroY - (gap / maxGap) * (chartHeight / 2);
    if (i === 0) ctx.moveTo(x, zeroY);
    ctx.lineTo(x, y);
  });
  ctx.lineTo(width - padding.right, zeroY);
  ctx.closePath();

  // 그라디언트
  const lastGap = parseFloat(data[data.length - 1]?.gap) || 0;
  const gradient = ctx.createLinearGradient(0, padding.top, 0, height - padding.bottom);
  if (lastGap >= 0) {
    gradient.addColorStop(0, 'rgba(239, 68, 68, 0.3)');
    gradient.addColorStop(1, 'rgba(239, 68, 68, 0)');
  } else {
    gradient.addColorStop(0, 'rgba(59, 130, 246, 0)');
    gradient.addColorStop(1, 'rgba(59, 130, 246, 0.3)');
  }
  ctx.fillStyle = gradient;
  ctx.fill();

  // 라인
  ctx.strokeStyle = lastGap >= 0 ? '#EF4444' : '#3B82F6';
  ctx.lineWidth = 2;
  ctx.beginPath();
  data.forEach((d, i) => {
    const x = padding.left + (i / (data.length - 1)) * chartWidth;
    const gap = parseFloat(d.gap) || 0;
    const y = zeroY - (gap / maxGap) * (chartHeight / 2);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.stroke();
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', minimumFractionDigits: 0 }).format(value);
};

const goBack = () => router.back();
const logout = () => {
  UserManager.logout();
  router.push('/login');
};

watch([selectedStock, selectedIndex], () => {
  if (selectedStock.value) {
    loadChartData();
  }
});
</script>

<style scoped>
/* 배너 */
.info-banner {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.1) 0%, rgba(239, 68, 68, 0.1) 100%);
  border: 2px solid rgba(245, 158, 11, 0.2);
  border-radius: 16px;
  margin-bottom: 24px;
}

.banner-icon {
  font-size: 40px;
}

.banner-text strong {
  display: block;
  font-size: 18px;
  color: var(--text-primary);
  margin-bottom: 8px;
}

.banner-text p {
  margin: 0;
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.strong-text {
  color: #EF4444;
  font-weight: 700;
}

.weak-text {
  color: #3B82F6;
  font-weight: 700;
}

/* 컨트롤 바 */
.control-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}

.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  background: white;
  border: 2px solid var(--border-color);
  border-radius: 12px;
}

.search-box input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 15px;
}

.index-selector {
  display: flex;
  gap: 8px;
}

.index-selector button {
  padding: 12px 24px;
  background: white;
  border: 2px solid var(--border-color);
  border-radius: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.index-selector button.active {
  background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
  border-color: #F59E0B;
  color: white;
}

/* 검색 결과 */
.search-results {
  background: white;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
  margin-bottom: 16px;
  max-height: 300px;
  overflow-y: auto;
}

.search-item {
  display: flex;
  justify-content: space-between;
  padding: 14px 20px;
  cursor: pointer;
  border-bottom: 1px solid var(--border-light);
}

.search-item:hover {
  background: rgba(245, 158, 11, 0.05);
}

/* 분석 카드 */
.analysis-card {
  padding: 20px 24px;
  border-radius: 16px;
  margin-bottom: 20px;
  border-left: 4px solid #999;
  background: linear-gradient(135deg, #f8f9fa 0%, #fff 100%);
}

.analysis-card.strong {
  border-left-color: #EF4444;
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.05) 0%, #fff 100%);
}

.analysis-card.weak {
  border-left-color: #3B82F6;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.05) 0%, #fff 100%);
}

.analysis-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.analysis-icon {
  font-size: 28px;
}

.analysis-title {
  font-size: 20px;
  font-weight: 700;
}

.relative-strength {
  margin-left: auto;
  padding: 6px 14px;
  border-radius: 20px;
  font-weight: 600;
  font-size: 14px;
}

.relative-strength.positive {
  background: rgba(239, 68, 68, 0.1);
  color: #EF4444;
}

.relative-strength.negative {
  background: rgba(59, 130, 246, 0.1);
  color: #3B82F6;
}

.analysis-reason {
  margin: 0;
  font-size: 15px;
  color: var(--text-secondary);
  line-height: 1.5;
}

/* 현재가 정보 */
.price-info-grid {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
}

.price-card {
  flex: 1;
  padding: 20px;
  border-radius: 16px;
  text-align: center;
}

.price-card.stock {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.1) 0%, rgba(239, 68, 68, 0.05) 100%);
  border: 2px solid rgba(239, 68, 68, 0.2);
}

.price-card.index {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.1) 0%, rgba(245, 158, 11, 0.05) 100%);
  border: 2px solid rgba(245, 158, 11, 0.2);
}

.price-label {
  font-size: 14px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.price-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.price-change {
  font-size: 16px;
  font-weight: 600;
  margin-top: 8px;
}

.price-change.positive { color: #EF4444; }
.price-change.negative { color: #3B82F6; }

.vs-badge {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-muted);
}

/* 차트 */
.chart-container {
  background: white;
  border-radius: 16px;
  padding: 20px;
  margin-bottom: 20px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.chart-legend {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
  font-size: 13px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.legend-color {
  width: 16px;
  height: 3px;
  border-radius: 2px;
}

.legend-item.stock .legend-color {
  background: #EF4444;
}

.legend-item.index .legend-color {
  background: #F59E0B;
}

.legend-item.zero .legend-color {
  background: #e0e0e0;
  border: 1px dashed #999;
}

.gap-chart-container {
  background: white;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.gap-chart-container h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: var(--text-muted);
}

/* 빈 상태 */
.empty-state {
  text-align: center;
  padding: 80px 20px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

/* 새로고침 */
.btn-refresh svg.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 반응형 */
@media (max-width: 768px) {
  .control-bar {
    flex-direction: column;
  }

  .price-info-grid {
    flex-direction: column;
  }

  .vs-badge {
    display: none;
  }
}
</style>
