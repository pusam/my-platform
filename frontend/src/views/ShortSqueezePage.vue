<template>
  <div class="short-squeeze-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>공매도 분석</h1>
        <p class="subtitle">숏스퀴즈 후보 및 공매도 동향 분석</p>
      </div>

      <div class="action-bar">
        <div class="filter-section">
          <div class="filter-item">
            <label>조회 수</label>
            <select v-model="limit" @change="fetchData">
              <option :value="20">20개</option>
              <option :value="30">30개</option>
              <option :value="50">50개</option>
              <option :value="100">100개</option>
            </select>
          </div>
        </div>
        <div class="action-buttons">
          <button @click="collectData" class="collect-btn" :disabled="collecting">
            {{ collecting ? '수집 중...' : '데이터 수집' }}
          </button>
          <button @click="fetchData" class="refresh-btn">
            새로고침
          </button>
        </div>
      </div>

      <div class="last-update" v-if="latestDate">
        최근 데이터: {{ latestDate }}
      </div>

      <div class="analysis-tabs">
        <button v-for="tab in analysisTabs" :key="tab.value"
                :class="['tab-btn', { active: selectedTab === tab.value }]"
                @click="changeTab(tab.value)">
          {{ tab.icon }} {{ tab.label }}
        </button>
      </div>

      <!-- 숏스퀴즈 후보 탭 -->
      <div v-if="selectedTab === 'squeeze'" class="stocks-grid">
        <div v-for="stock in currentStocks" :key="stock.stockCode"
             :class="['stock-card', getSqueezeClass(stock.squeezeLevel)]">
          <div class="squeeze-badge" :class="stock.squeezeLevel?.toLowerCase()">
            {{ getSqueezeIcon(stock.squeezeLevel) }} {{ stock.squeezeLevel }}
          </div>

          <div class="stock-header">
            <div class="stock-info">
              <span class="stock-name">{{ stock.stockName }}</span>
              <span class="stock-code">{{ stock.stockCode }}</span>
            </div>
            <div class="score-info">
              <span class="score">{{ stock.squeezeScore }}점</span>
            </div>
          </div>

          <div class="stock-details">
            <div class="detail-row highlight">
              <span class="label">스퀴즈 신호</span>
              <span class="value signal">{{ stock.signalDescription || '-' }}</span>
            </div>

            <div class="detail-row">
              <span class="label">현재가</span>
              <span class="value">{{ formatNumber(stock.currentPrice) }}원</span>
            </div>

            <div class="detail-row" v-if="stock.changeRate">
              <span class="label">등락률</span>
              <span class="value rate" :class="getRateClass(stock.changeRate)">
                {{ formatRate(stock.changeRate) }}
              </span>
            </div>

            <div class="detail-row">
              <span class="label">5일 상승률</span>
              <span class="value rate" :class="getRateClass(stock.priceChange5Days)">
                {{ formatRate(stock.priceChange5Days) }}
              </span>
            </div>

            <div class="detail-section">
              <div class="section-title">대차잔고 분석</div>
              <div class="detail-row">
                <span class="label">대차잔고 비율</span>
                <span class="value">{{ formatPercent(stock.loanBalanceRatio) }}</span>
              </div>
              <div class="detail-row">
                <span class="label">5일 대차잔고 변화</span>
                <span class="value" :class="getLoanChangeClass(stock.loanBalanceChange5Days)">
                  {{ formatRate(stock.loanBalanceChange5Days) }}
                </span>
              </div>
            </div>

            <div class="detail-section">
              <div class="section-title">수급 분석</div>
              <div class="detail-row">
                <span class="label">외국인 3일 순매수</span>
                <span class="value" :class="getAmountClass(stock.foreignNetBuy3Days)">
                  {{ formatAmount(stock.foreignNetBuy3Days) }}
                </span>
              </div>
              <div class="detail-row">
                <span class="label">20일선 위치</span>
                <span class="value" :class="stock.isAboveMa20 ? 'positive' : 'negative'">
                  {{ stock.isAboveMa20 ? '상향 돌파' : '하향' }}
                </span>
              </div>
            </div>
          </div>

          <button @click="goToDetail(stock.stockCode)" class="detail-btn">
            상세보기
          </button>
        </div>
      </div>

      <!-- 대차잔고 상위 탭 -->
      <div v-if="selectedTab === 'loan'" class="stocks-table">
        <table>
          <thead>
            <tr>
              <th>순위</th>
              <th>종목명</th>
              <th>종목코드</th>
              <th>현재가</th>
              <th>등락률</th>
              <th>대차잔고 비율</th>
              <th>공매도 비중</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(stock, index) in currentStocks" :key="stock.stockCode"
                @click="goToDetail(stock.stockCode)"
                class="clickable-row">
              <td class="rank">{{ index + 1 }}</td>
              <td class="name">{{ stock.stockName }}</td>
              <td class="code">{{ stock.stockCode }}</td>
              <td class="price">{{ formatNumber(stock.currentPrice) }}</td>
              <td class="rate" :class="getRateClass(stock.changeRate)">
                {{ formatRate(stock.changeRate) }}
              </td>
              <td class="ratio highlight">{{ formatPercent(stock.loanBalanceRatio) }}</td>
              <td class="ratio">{{ formatPercent(stock.shortRatio) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 공매도 비중 상위 탭 -->
      <div v-if="selectedTab === 'short'" class="stocks-table">
        <table>
          <thead>
            <tr>
              <th>순위</th>
              <th>종목명</th>
              <th>종목코드</th>
              <th>현재가</th>
              <th>등락률</th>
              <th>공매도 비중</th>
              <th>공매도 잔고 비율</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(stock, index) in currentStocks" :key="stock.stockCode"
                @click="goToDetail(stock.stockCode)"
                class="clickable-row">
              <td class="rank">{{ index + 1 }}</td>
              <td class="name">{{ stock.stockName }}</td>
              <td class="code">{{ stock.stockCode }}</td>
              <td class="price">{{ formatNumber(stock.currentPrice) }}</td>
              <td class="rate" :class="getRateClass(stock.changeRate)">
                {{ formatRate(stock.changeRate) }}
              </td>
              <td class="ratio highlight">{{ formatPercent(stock.shortRatio) }}</td>
              <td class="ratio">{{ formatPercent(stock.shortBalanceRatio) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="currentStocks.length === 0" class="no-data">
        <p>데이터가 없습니다.</p>
        <p class="hint">"데이터 수집" 버튼을 클릭하여 공매도 데이터를 수집하세요.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import api from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();
const loading = ref(false);
const collecting = ref(false);
const limit = ref(30);
const selectedTab = ref('squeeze');
const latestDate = ref('');

const squeezeStocks = ref([]);
const loanBalanceStocks = ref([]);
const shortRatioStocks = ref([]);

const analysisTabs = [
  { value: 'squeeze', label: '숏스퀴즈 후보', icon: '🚀' },
  { value: 'loan', label: '대차잔고 상위', icon: '📊' },
  { value: 'short', label: '공매도 비중 상위', icon: '📉' }
];

const currentStocks = computed(() => {
  switch (selectedTab.value) {
    case 'squeeze': return squeezeStocks.value;
    case 'loan': return loanBalanceStocks.value;
    case 'short': return shortRatioStocks.value;
    default: return [];
  }
});

const changeTab = (tab) => {
  selectedTab.value = tab;
  fetchTabData(tab);
};

const fetchData = async () => {
  loading.value = true;
  try {
    await Promise.all([
      fetchTabData(selectedTab.value),
      fetchLatestDate()
    ]);
  } finally {
    loading.value = false;
  }
};

const fetchTabData = async (tab) => {
  try {
    let endpoint = '';
    switch (tab) {
      case 'squeeze':
        endpoint = '/short-selling/squeeze-candidates';
        break;
      case 'loan':
        endpoint = '/short-selling/top-loan-balance';
        break;
      case 'short':
        endpoint = '/short-selling/top-short-ratio';
        break;
    }

    const response = await api.get(endpoint, { params: { limit: limit.value } });
    if (response.data.success) {
      switch (tab) {
        case 'squeeze':
          squeezeStocks.value = response.data.data;
          break;
        case 'loan':
          loanBalanceStocks.value = response.data.data;
          break;
        case 'short':
          shortRatioStocks.value = response.data.data;
          break;
      }
    }
  } catch (error) {
    console.error('데이터 조회 오류:', error);
  }
};

const fetchLatestDate = async () => {
  try {
    const response = await api.get('/short-selling/latest-date');
    if (response.data.success) {
      latestDate.value = response.data.data;
    }
  } catch (error) {
    console.error('최근 날짜 조회 오류:', error);
  }
};

const collectData = async () => {
  if (collecting.value) return;
  collecting.value = true;
  try {
    const response = await api.post('/short-selling/collect');
    if (response.data.success) {
      alert('데이터 수집이 완료되었습니다!');
      await fetchData();
    } else {
      alert('수집 실패: ' + response.data.message);
    }
  } catch (error) {
    console.error('데이터 수집 오류:', error);
    alert('데이터 수집에 실패했습니다.');
  } finally {
    collecting.value = false;
  }
};

const goToDetail = (stockCode) => {
  router.push(`/investor-stock/${stockCode}`);
};

const goBack = () => {
  router.back();
};

// 포맷팅 함수들
const formatNumber = (value) => {
  if (!value) return '0';
  return Number(value).toLocaleString('ko-KR');
};

const formatAmount = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  if (Math.abs(num) >= 10000) {
    return `${(num / 10000).toFixed(1)}조`;
  }
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}억`;
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

const getAmountClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const getLoanChangeClass = (value) => {
  if (!value) return '';
  // 대차잔고 감소(음수)는 긍정적 신호 (숏커버링)
  return Number(value) < 0 ? 'positive' : Number(value) > 0 ? 'negative' : '';
};

const getSqueezeClass = (level) => {
  switch (level) {
    case 'CRITICAL': return 'squeeze-critical';
    case 'HIGH': return 'squeeze-high';
    case 'MEDIUM': return 'squeeze-medium';
    default: return 'squeeze-low';
  }
};

const getSqueezeIcon = (level) => {
  switch (level) {
    case 'CRITICAL': return '🔥';
    case 'HIGH': return '⚡';
    case 'MEDIUM': return '📈';
    default: return '📊';
  }
};

onMounted(() => {
  fetchData();
});
</script>

<style scoped>
.short-squeeze-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
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

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  padding: 1rem;
  background: #1a1a3a;
  border-radius: 10px;
}

.filter-section {
  display: flex;
  gap: 1rem;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.filter-item label {
  font-weight: 600;
  color: #aaa;
}

.filter-item select {
  padding: 0.5rem 1rem;
  border: 1px solid #3a3a5a;
  border-radius: 8px;
  font-size: 1rem;
  cursor: pointer;
  background: #2a2a4a;
  color: #fff;
}

.action-buttons {
  display: flex;
  gap: 0.5rem;
}

.collect-btn, .refresh-btn {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  font-weight: 600;
  transition: all 0.3s;
}

.collect-btn {
  background: #48bb78;
  color: white;
}

.collect-btn:hover:not(:disabled) {
  background: #38a169;
}

.collect-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.refresh-btn {
  background: #4a4a8a;
  color: white;
}

.refresh-btn:hover {
  background: #5a5a9a;
}

.last-update {
  text-align: right;
  color: #666;
  font-size: 0.9rem;
  margin-bottom: 1rem;
}

.analysis-tabs {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid #2a2a4a;
}

.tab-btn {
  padding: 1rem 2rem;
  background: none;
  border: none;
  color: #666;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
  border-bottom: 3px solid transparent;
  margin-bottom: -2px;
}

.tab-btn.active {
  color: #e53e3e;
  border-bottom-color: #e53e3e;
}

.tab-btn:hover:not(.active) {
  color: #aaa;
}

/* 숏스퀴즈 카드 그리드 */
.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
  gap: 1.5rem;
}

.stock-card {
  background: #1a1a3a;
  border-radius: 15px;
  padding: 1.5rem;
  border: 2px solid #2a2a4a;
  transition: all 0.3s;
  position: relative;
}

.stock-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.3);
}

.stock-card.squeeze-critical {
  border-color: #e53e3e;
  background: linear-gradient(135deg, #1a1a3a 0%, #2a1a2a 100%);
}

.stock-card.squeeze-high {
  border-color: #ed8936;
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a1a 100%);
}

.stock-card.squeeze-medium {
  border-color: #48bb78;
  background: linear-gradient(135deg, #1a1a3a 0%, #1a2a2a 100%);
}

.squeeze-badge {
  position: absolute;
  top: -10px;
  right: 15px;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-size: 0.8rem;
  font-weight: 700;
  color: white;
}

.squeeze-badge.critical {
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
}

.squeeze-badge.high {
  background: linear-gradient(135deg, #ed8936 0%, #dd6b20 100%);
}

.squeeze-badge.medium {
  background: linear-gradient(135deg, #48bb78 0%, #38a169 100%);
}

.squeeze-badge.low {
  background: linear-gradient(135deg, #718096 0%, #4a5568 100%);
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid #2a2a4a;
}

.stock-info {
  display: flex;
  flex-direction: column;
}

.stock-name {
  font-size: 1.2rem;
  font-weight: 700;
  color: #fff;
}

.stock-code {
  font-size: 0.9rem;
  color: #666;
  font-family: monospace;
}

.score-info {
  text-align: right;
}

.score {
  font-size: 1.3rem;
  font-weight: 700;
  color: #e53e3e;
}

.stock-details {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-row.highlight {
  background: #2a2a4a;
  padding: 0.5rem;
  border-radius: 8px;
  margin-bottom: 0.5rem;
}

.detail-row .label {
  color: #888;
  font-size: 0.9rem;
}

.detail-row .value {
  font-weight: 600;
  color: #fff;
}

.detail-row .value.signal {
  font-size: 0.85rem;
  color: #ed8936;
  text-align: right;
  max-width: 200px;
}

.detail-section {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px dashed #2a2a4a;
}

.section-title {
  font-size: 0.8rem;
  color: #666;
  margin-bottom: 0.5rem;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.positive {
  color: #e53e3e !important;
}

.negative {
  color: #3182ce !important;
}

.detail-btn {
  width: 100%;
  background: #4a4a8a;
  color: white;
  border: none;
  padding: 0.75rem;
  border-radius: 8px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
}

.detail-btn:hover {
  background: #5a5a9a;
}

/* 테이블 스타일 */
.stocks-table {
  overflow-x: auto;
}

.stocks-table table {
  width: 100%;
  border-collapse: collapse;
}

.stocks-table th,
.stocks-table td {
  padding: 1rem;
  text-align: left;
  border-bottom: 1px solid #2a2a4a;
}

.stocks-table th {
  background: #1a1a3a;
  color: #888;
  font-weight: 600;
  font-size: 0.9rem;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.stocks-table td {
  color: #fff;
}

.stocks-table .clickable-row {
  cursor: pointer;
  transition: background 0.2s;
}

.stocks-table .clickable-row:hover {
  background: #1a1a3a;
}

.stocks-table .rank {
  font-weight: 700;
  color: #e53e3e;
  width: 60px;
}

.stocks-table .name {
  font-weight: 600;
}

.stocks-table .code {
  font-family: monospace;
  color: #666;
}

.stocks-table .price {
  font-family: monospace;
}

.stocks-table .rate {
  font-weight: 600;
}

.stocks-table .ratio {
  font-weight: 600;
}

.stocks-table .ratio.highlight {
  color: #ed8936;
}

.no-data {
  text-align: center;
  padding: 3rem;
  color: #666;
}

.no-data p {
  font-size: 1.2rem;
  margin-bottom: 0.5rem;
}

.no-data .hint {
  font-size: 0.9rem;
  color: #555;
}

@media (max-width: 768px) {
  .short-squeeze-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    margin-top: 3rem;
    font-size: 1.5rem;
  }

  .action-bar {
    flex-direction: column;
    gap: 1rem;
  }

  .analysis-tabs {
    flex-wrap: wrap;
  }

  .tab-btn {
    padding: 0.75rem 1rem;
    font-size: 0.9rem;
  }

  .stocks-grid {
    grid-template-columns: 1fr;
  }

  .stocks-table {
    font-size: 0.85rem;
  }

  .stocks-table th,
  .stocks-table td {
    padding: 0.5rem;
  }
}
</style>
