<template>
  <div class="investor-stock-detail-page">
    <LoadingSpinner v-if="loading" />

    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>{{ stockData?.stockName }} ({{ stockCode }})</h1>
        <p class="subtitle">투자자별 매매 동향 (최근 30일)</p>
      </div>

      <div v-if="stockData && stockData.dailyTrades.length > 0" class="chart-container">
        <div v-for="day in stockData.dailyTrades" :key="day.tradeDate" class="daily-trade-card">
          <div class="date-header">
            📅 {{ formatDate(day.tradeDate) }}
          </div>

          <div class="investor-grid">
            <div class="investor-item foreign">
              <div class="investor-label">
                <span class="icon">🌍</span>
                <span class="name">외국인</span>
              </div>
              <div class="amounts">
                <div class="amount-row">
                  <span class="label">매수:</span>
                  <span class="value">{{ formatAmount(day.foreign?.buyAmount) }}</span>
                </div>
                <div class="amount-row">
                  <span class="label">매도:</span>
                  <span class="value">{{ formatAmount(day.foreign?.sellAmount) }}</span>
                </div>
                <div class="amount-row net">
                  <span class="label">순매수:</span>
                  <span class="value" :class="getAmountClass(day.foreign?.netBuyAmount)">
                    {{ formatAmount(day.foreign?.netBuyAmount) }}
                  </span>
                </div>
              </div>
            </div>

            <div class="investor-item institution">
              <div class="investor-label">
                <span class="icon">🏢</span>
                <span class="name">기관</span>
              </div>
              <div class="amounts">
                <div class="amount-row">
                  <span class="label">매수:</span>
                  <span class="value">{{ formatAmount(day.institution?.buyAmount) }}</span>
                </div>
                <div class="amount-row">
                  <span class="label">매도:</span>
                  <span class="value">{{ formatAmount(day.institution?.sellAmount) }}</span>
                </div>
                <div class="amount-row net">
                  <span class="label">순매수:</span>
                  <span class="value" :class="getAmountClass(day.institution?.netBuyAmount)">
                    {{ formatAmount(day.institution?.netBuyAmount) }}
                  </span>
                </div>
              </div>
            </div>

          </div>
        </div>
      </div>

      <div v-else class="no-data">
        <p>💡 해당 종목의 투자자 매매 데이터가 없습니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import api from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const stockCode = ref(route.params.stockCode);
const stockData = ref(null);

const fetchStockDetail = async () => {
  loading.value = true;
  try {
    const response = await api.get(`/api/investor/stock/${stockCode.value}`, {
      params: { days: 30 }
    });

    if (response.data.success) {
      stockData.value = response.data.data;
    }
  } catch (error) {
    console.error('종목 상세 조회 오류:', error);
  } finally {
    loading.value = false;
  }
};

const goBack = () => {
  router.back();
};

const formatDate = (dateStr) => {
  const date = new Date(dateStr);
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${['일', '월', '화', '수', '목', '금', '토'][date.getDay()]})`;
};

const formatAmount = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const getAmountClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

onMounted(() => {
  fetchStockDetail();
});
</script>

<style scoped>
.investor-stock-detail-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1200px;
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
  font-size: 1.8rem;
}

.subtitle {
  color: #718096;
  font-size: 1.1rem;
}

.chart-container {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  max-height: 70vh;
  overflow-y: auto;
  padding: 1rem;
}

.daily-trade-card {
  background: #f7fafc;
  border-radius: 15px;
  padding: 1.5rem;
  border: 2px solid #e2e8f0;
  transition: all 0.3s;
}

.daily-trade-card:hover {
  border-color: #667eea;
  box-shadow: 0 5px 15px rgba(102, 126, 234, 0.2);
}

.date-header {
  font-size: 1.2rem;
  font-weight: 700;
  color: #2d3748;
  margin-bottom: 1rem;
  padding-bottom: 0.5rem;
  border-bottom: 2px solid #e2e8f0;
}

.investor-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1rem;
}

.investor-item {
  background: white;
  border-radius: 10px;
  padding: 1rem;
  border: 2px solid #e2e8f0;
}

.investor-item.foreign {
  border-color: #4299e1;
}

.investor-item.institution {
  border-color: #48bb78;
}

.investor-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid #e2e8f0;
}

.investor-label .icon {
  font-size: 1.5rem;
}

.investor-label .name {
  font-weight: 700;
  font-size: 1rem;
  color: #2d3748;
}

.amounts {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.9rem;
}

.amount-row .label {
  color: #718096;
}

.amount-row .value {
  font-weight: 600;
  font-family: monospace;
  color: #2d3748;
}

.amount-row.net {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid #e2e8f0;
}

.amount-row.net .label {
  font-weight: 700;
}

.amount-row.net .value {
  font-size: 1rem;
}

.positive {
  color: #e53e3e !important;
  font-weight: 700;
}

.negative {
  color: #3182ce !important;
  font-weight: 700;
}

.no-data {
  text-align: center;
  padding: 3rem;
  color: #718096;
  font-size: 1.2rem;
}

@media (max-width: 1024px) {
  .investor-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .investor-stock-detail-page {
    padding: 1rem;
  }

  .content-wrapper {
    padding: 1rem;
  }

  .page-header h1 {
    font-size: 1.3rem;
    margin-top: 3rem;
  }
}
</style>
