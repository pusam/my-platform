<template>
  <div class="surge-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>수급 급증 종목</h1>
        <p class="subtitle">장중 외국인/기관 순매수가 급증하는 종목</p>
      </div>

      <div class="action-bar">
        <div class="filter-section">
          <div class="filter-item">
            <label>최소 변화량</label>
            <select v-model="minChange" @change="fetchData">
              <option :value="30">30억 이상</option>
              <option :value="50">50억 이상</option>
              <option :value="100">100억 이상</option>
              <option :value="200">200억 이상</option>
            </select>
          </div>
        </div>
        <div class="action-buttons">
          <button @click="collectSnapshot" class="collect-btn" :disabled="collecting">
            {{ collecting ? '수집 중...' : '스냅샷 수집' }}
          </button>
          <button @click="fetchData" class="refresh-btn">
            새로고침
          </button>
        </div>
      </div>

      <div class="last-update" v-if="lastUpdateTime">
        마지막 업데이트: {{ lastUpdateTime }}
      </div>

      <div class="investor-tabs">
        <button v-for="type in investorTypes" :key="type.value"
                :class="['tab-btn', { active: selectedInvestor === type.value }]"
                @click="selectedInvestor = type.value">
          {{ type.icon }} {{ type.label }}
        </button>
      </div>

      <div v-if="currentStocks.length > 0" class="stocks-grid">
        <div v-for="stock in currentStocks" :key="stock.stockCode"
             :class="['stock-card', stock.surgeLevel.toLowerCase()]">
          <div class="surge-badge" :class="stock.surgeLevel.toLowerCase()">
            {{ getSurgeLevelText(stock.surgeLevel) }}
          </div>

          <div class="stock-header">
            <div class="stock-info">
              <span class="stock-name">{{ stock.stockName }}</span>
              <span class="stock-code">{{ stock.stockCode }}</span>
            </div>
            <div class="rank-info">
              <span class="current-rank">#{{ stock.currentRank }}</span>
              <span class="rank-change" :class="getRankChangeClass(stock.rankChange)" v-if="stock.rankChange">
                {{ formatRankChange(stock.rankChange) }}
              </span>
            </div>
          </div>

          <div class="stock-details">
            <div class="detail-row highlight">
              <span class="label">변화량</span>
              <span class="value amount positive">
                +{{ formatAmount(stock.amountChange) }}
              </span>
            </div>
            <div class="detail-row">
              <span class="label">누적 순매수</span>
              <span class="value">{{ formatAmount(stock.netBuyAmount) }}</span>
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
            <div class="detail-row">
              <span class="label">스냅샷 시간</span>
              <span class="value time">{{ formatTime(stock.snapshotTime) }}</span>
            </div>
          </div>

          <button @click="goToDetail(stock.stockCode)" class="detail-btn">
            상세보기
          </button>
        </div>
      </div>

      <div v-else class="no-data">
        <p>수급 급증 종목이 없습니다.</p>
        <p class="hint">장중(09:10~15:20)에 자동으로 10분마다 수집됩니다.</p>
        <p class="hint">수동으로 수집하려면 "스냅샷 수집" 버튼을 클릭하세요.</p>
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
const minChange = ref(50);
const selectedInvestor = ref('FOREIGN');
const allStocks = ref({});
const lastUpdateTime = ref('');

const investorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' }
];

const currentStocks = computed(() => {
  return allStocks.value[selectedInvestor.value] || [];
});

const fetchData = async () => {
  loading.value = true;
  try {
    const response = await api.get('/investor/surge/all', {
      params: { minChange: minChange.value }
    });
    if (response.data.success) {
      allStocks.value = response.data.data;
      lastUpdateTime.value = new Date().toLocaleTimeString('ko-KR');
    }
  } catch (error) {
    console.error('수급 급증 종목 조회 오류:', error);
  } finally {
    loading.value = false;
  }
};

const collectSnapshot = async () => {
  if (collecting.value) return;
  collecting.value = true;
  try {
    const response = await api.post('/investor/surge/collect');
    if (response.data.success) {
      alert('스냅샷 수집이 완료되었습니다!');
      await fetchData();
    }
  } catch (error) {
    console.error('스냅샷 수집 오류:', error);
    alert('스냅샷 수집에 실패했습니다.');
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

const formatTime = (timeStr) => {
  if (!timeStr) return '-';
  // timeStr is like "10:30:00" or just "10:30"
  const parts = timeStr.split(':');
  return `${parts[0]}:${parts[1]}`;
};

const formatRankChange = (change) => {
  if (!change) return '';
  if (change > 0) return `▲${change}`;
  if (change < 0) return `▼${Math.abs(change)}`;
  return '-';
};

const getRankChangeClass = (change) => {
  if (!change) return '';
  return change > 0 ? 'rank-up' : change < 0 ? 'rank-down' : '';
};

const getRateClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const getSurgeLevelText = (level) => {
  switch (level) {
    case 'HOT': return '🔥 HOT';
    case 'WARM': return '⚡ WARM';
    default: return '';
  }
};

onMounted(() => {
  fetchData();
});
</script>

<style scoped>
.surge-page {
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
  background: #e53e3e;
  color: white;
}

.collect-btn:hover:not(:disabled) {
  background: #c53030;
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

.investor-tabs {
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

.stocks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
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
  border-color: #e53e3e;
  box-shadow: 0 10px 30px rgba(229, 62, 62, 0.2);
  transform: translateY(-5px);
}

.stock-card.hot {
  border-color: #e53e3e;
  background: linear-gradient(135deg, #1a1a3a 0%, #2a1a2a 100%);
}

.stock-card.warm {
  border-color: #ed8936;
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a1a 100%);
}

.surge-badge {
  position: absolute;
  top: -10px;
  right: 15px;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-size: 0.8rem;
  font-weight: 700;
}

.surge-badge.hot {
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
  color: white;
}

.surge-badge.warm {
  background: linear-gradient(135deg, #ed8936 0%, #dd6b20 100%);
  color: white;
}

.surge-badge.normal {
  display: none;
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

.rank-info {
  text-align: right;
}

.current-rank {
  font-size: 1.1rem;
  font-weight: 700;
  color: #e53e3e;
}

.rank-change {
  display: block;
  font-size: 0.8rem;
  margin-top: 0.2rem;
}

.rank-change.rank-up {
  color: #e53e3e;
}

.rank-change.rank-down {
  color: #3182ce;
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

.detail-row.highlight {
  background: #2a2a4a;
  padding: 0.5rem;
  border-radius: 8px;
}

.detail-row .label {
  color: #888;
  font-size: 0.9rem;
}

.detail-row .value {
  font-weight: 600;
  color: #fff;
}

.detail-row .value.amount {
  font-size: 1.1rem;
  font-family: monospace;
}

.detail-row .value.time {
  color: #666;
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
  .surge-page {
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

  .stocks-grid {
    grid-template-columns: 1fr;
  }
}
</style>
