<template>
  <div class="consecutive-buy-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <BackButton />
        <h1>연속 매수 종목</h1>
        <p class="subtitle">외국인, 기관이 연속으로 순매수 중인 종목</p>
      </div>

      <div class="filter-section">
        <div class="filter-item">
          <label>최소 연속 일수</label>
          <select v-model="minDays" @change="fetchData">
            <option :value="2">2일 이상</option>
            <option :value="3">3일 이상</option>
            <option :value="5">5일 이상</option>
            <option :value="7">7일 이상</option>
            <option :value="10">10일 이상</option>
          </select>
        </div>
        <div class="filter-item">
          <label>정렬 기준</label>
          <select v-model="sortBy">
            <option value="netBuy">누적 순매수순</option>
            <option value="days">연속 일수순</option>
            <option value="changeRate">등락률 낮은순</option>
          </select>
        </div>
      </div>

      <div class="investor-tabs">
        <button v-for="type in investorTypes" :key="type.value"
                :class="['tab-btn', { active: selectedInvestor === type.value }]"
                @click="selectedInvestor = type.value">
          {{ type.icon }} {{ type.label }}
        </button>
      </div>

      <div v-if="currentStocks.length > 0" class="stocks-grid">
        <div v-for="stock in currentStocks" :key="stock.stockCode" class="stock-card">
          <div class="stock-header">
            <div class="stock-info">
              <span class="stock-name">{{ stock.stockName }}</span>
              <span class="stock-code">{{ stock.stockCode }}</span>
            </div>
            <div class="consecutive-badge">
              {{ stock.consecutiveDays }}일 연속
            </div>
          </div>

          <div class="stock-details">
            <div class="detail-row">
              <span class="label">누적 순매수</span>
              <span class="value amount" :class="{ positive: stock.totalNetBuyAmount > 0 }">
                {{ formatAmount(stock.totalNetBuyAmount) }}
              </span>
            </div>
            <div class="detail-row">
              <span class="label">일평균</span>
              <span class="value">{{ formatAmount(stock.avgDailyAmount) }}</span>
            </div>
            <div class="detail-row">
              <span class="label">기간</span>
              <span class="value date">{{ formatDateRange(stock.startDate, stock.endDate) }}</span>
            </div>
            <div class="detail-row" v-if="stock.currentPrice">
              <span class="label">현재가</span>
              <span class="value">{{ formatNumber(stock.currentPrice) }}원</span>
            </div>
            <div class="detail-row" v-if="stock.changeRate">
              <span class="label">등락률</span>
              <span class="value rate" :class="getRateClass(stock.changeRate)">
                {{ formatRate(stock.changeRate) }}
              </span>
            </div>
          </div>

          <button @click="goToDetail(stock.stockCode)" class="detail-btn">
            상세보기
          </button>
        </div>
      </div>

      <div v-else class="no-data">
        <p>{{ minDays }}일 이상 연속 매수 중인 종목이 없습니다.</p>
        <div v-if="dataStatus" class="data-status">
          <p class="status-message">{{ dataStatus.message }}</p>
          <div class="status-details">
            <span v-if="dataStatus.foreignTradeDays !== undefined">
              📊 외국인 데이터: {{ dataStatus.foreignTradeDays }}일
            </span>
            <span v-if="dataStatus.institutionTradeDays !== undefined">
              📊 기관 데이터: {{ dataStatus.institutionTradeDays }}일
            </span>
            <span v-if="dataStatus.latestTradeDate">
              📅 최신 데이터: {{ dataStatus.latestTradeDate }}
            </span>
          </div>
        </div>
        <p class="hint" v-else>데이터 수집이 필요할 수 있습니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { investorAPI } from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import BackButton from '../components/BackButton.vue';

const router = useRouter();
const loading = ref(false);
const minDays = ref(3);
const selectedInvestor = ref('FOREIGN');
const sortBy = ref('netBuy');  // 정렬 기준: netBuy, days, changeRate
const allStocks = ref({});
const dataStatus = ref(null);

const investorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'INDIVIDUAL', label: '개인', icon: '👤' }
];

const currentStocks = computed(() => {
  const stocks = allStocks.value[selectedInvestor.value] || [];
  if (!stocks.length) return [];

  // 정렬 적용
  return [...stocks].sort((a, b) => {
    switch (sortBy.value) {
      case 'days':
        // 연속 일수순 (내림차순: 오래 산 종목부터)
        return (b.consecutiveDays || 0) - (a.consecutiveDays || 0);
      case 'changeRate':
        // 등락률 낮은순 (오름차순: 많이 샀는데 주가 떨어진 매집주)
        return (a.changeRate || 0) - (b.changeRate || 0);
      case 'netBuy':
      default:
        // 누적 순매수순 (내림차순)
        return (b.totalNetBuyAmount || 0) - (a.totalNetBuyAmount || 0);
    }
  });
});

const fetchData = async () => {
  loading.value = true;
  try {
    const response = await investorAPI.getAllConsecutiveBuy(minDays.value);
    if (response.data.success) {
      const data = response.data.data;
      allStocks.value = {
        FOREIGN: data.FOREIGN || [],
        INSTITUTION: data.INSTITUTION || [],
        INDIVIDUAL: data.INDIVIDUAL || []
      };
      dataStatus.value = data.dataStatus;
    }
  } catch (error) {
    console.error('연속 매수 종목 조회 오류:', error);
  } finally {
    loading.value = false;
  }
};

const goToDetail = (stockCode) => {
  // ★ 현재 선택된 투자자 타입을 쿼리 파라미터로 전달
  router.push({
    path: `/investor-stock/${stockCode}`,
    query: { investorType: selectedInvestor.value }
  });
};

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
  if (!value) return '0.00%';
  const sign = value > 0 ? '+' : '';
  return `${sign}${Number(value).toFixed(2)}%`;
};

const getRateClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const formatDateRange = (start, end) => {
  if (!start || !end) return '-';
  const startDate = new Date(start);
  const endDate = new Date(end);
  const formatDate = (d) => `${d.getMonth() + 1}/${d.getDate()}`;
  return `${formatDate(startDate)} ~ ${formatDate(endDate)}`;
};

onMounted(() => {
  fetchData();
});
</script>

<style scoped>
.consecutive-buy-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1400px;
  margin: 0 auto;
  background: white;
  border-radius: 20px;
  padding: 2rem;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
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
  background: #667eea;
  color: white;
  border: none;
  padding: 0.75rem 1.5rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s;
}

.back-button:hover {
  background: #5568d3;
  transform: translateX(-5px);
}

.page-header h1 {
  color: #2d3748;
  margin-bottom: 0.5rem;
}

.subtitle {
  color: #718096;
  font-size: 1.1rem;
}

.filter-section {
  display: flex;
  justify-content: center;
  gap: 2rem;
  margin-bottom: 2rem;
  padding: 1rem;
  background: #f7fafc;
  border-radius: 10px;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.filter-item label {
  font-weight: 600;
  color: #4a5568;
}

.filter-item select {
  padding: 0.5rem 1rem;
  border: 2px solid #e2e8f0;
  border-radius: 8px;
  font-size: 1rem;
  cursor: pointer;
  background: white;
}

.filter-item select:focus {
  outline: none;
  border-color: #667eea;
}

.investor-tabs {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-bottom: 2rem;
  border-bottom: 2px solid #e2e8f0;
}

.tab-btn {
  padding: 1rem 2rem;
  background: none;
  border: none;
  color: #718096;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
  border-bottom: 3px solid transparent;
  margin-bottom: -2px;
}

.tab-btn.active {
  color: #667eea;
  border-bottom-color: #667eea;
}

.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1.5rem;
}

.stock-card {
  background: #f7fafc;
  border-radius: 15px;
  padding: 1.5rem;
  border: 2px solid #e2e8f0;
  transition: all 0.3s;
}

.stock-card:hover {
  border-color: #667eea;
  box-shadow: 0 10px 30px rgba(102, 126, 234, 0.2);
  transform: translateY(-5px);
}

.stock-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid #e2e8f0;
}

.stock-info {
  display: flex;
  flex-direction: column;
}

.stock-name {
  font-size: 1.2rem;
  font-weight: 700;
  color: #2d3748;
}

.stock-code {
  font-size: 0.9rem;
  color: #718096;
  font-family: monospace;
}

.consecutive-badge {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 0.5rem 1rem;
  border-radius: 20px;
  font-weight: 700;
  font-size: 0.9rem;
}

.stock-details {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-row .label {
  color: #718096;
  font-size: 0.9rem;
}

.detail-row .value {
  font-weight: 600;
  color: #2d3748;
}

.detail-row .value.amount {
  font-size: 1.1rem;
  font-family: monospace;
}

.detail-row .value.date {
  font-size: 0.9rem;
}

.detail-row .value.rate {
  font-family: monospace;
}

.positive {
  color: #e53e3e !important;
}

.negative {
  color: #3182ce !important;
}

.detail-btn {
  width: 100%;
  background: #667eea;
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
  background: #5568d3;
}

.no-data {
  text-align: center;
  padding: 3rem;
  color: #718096;
}

.no-data p {
  font-size: 1.2rem;
  margin-bottom: 0.5rem;
}

.no-data .hint {
  font-size: 0.9rem;
  color: #a0aec0;
}

.data-status {
  margin-top: 1.5rem;
  padding: 1.5rem;
  background: #f0f4ff;
  border-radius: 12px;
  border: 1px solid #667eea;
}

.status-message {
  color: #667eea;
  font-weight: 600;
  margin-bottom: 1rem;
  font-size: 1rem;
}

.status-details {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  justify-content: center;
  font-size: 0.9rem;
  color: #4a5568;
}

.status-details span {
  background: white;
  padding: 0.5rem 1rem;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
}

@media (max-width: 768px) {
  .consecutive-buy-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    margin-top: 3rem;
    font-size: 1.5rem;
  }

  .filter-section {
    flex-direction: column;
    gap: 1rem;
  }

  .stocks-grid {
    grid-template-columns: 1fr;
  }
}
</style>
