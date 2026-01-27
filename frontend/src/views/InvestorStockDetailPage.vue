<template>
  <div class="investor-stock-detail-page">
    <LoadingSpinner v-if="loading" />

    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>{{ getStockName() }} ({{ stockCode }})</h1>
        <p class="subtitle">투자자별 매매 동향</p>
      </div>

      <!-- 장중 수급 추이 섹션 -->
      <div v-if="hasSurgeData" class="section">
        <h2 class="section-title">📈 장중 수급 추이 (오늘)</h2>

        <div class="investor-tabs">
          <button v-for="type in investorTypes" :key="type.value"
                  :class="['tab-btn', { active: selectedInvestor === type.value }]"
                  @click="selectedInvestor = type.value">
            {{ type.icon }} {{ type.label }}
          </button>
        </div>

        <div v-if="currentSurgeTrend.length > 0" class="surge-trend-container">
          <div v-for="item in currentSurgeTrend" :key="item.snapshotTime" class="surge-item">
            <div class="time-badge">{{ formatTime(item.snapshotTime) }}</div>
            <div class="surge-details">
              <div class="detail-row">
                <span class="label">순위</span>
                <span class="value">#{{ item.currentRank }}</span>
              </div>
              <div class="detail-row highlight">
                <span class="label">누적 순매수</span>
                <span class="value" :class="getAmountClass(item.netBuyAmount)">
                  {{ formatAmount(item.netBuyAmount) }}
                </span>
              </div>
              <div class="detail-row" v-if="hasAmountChange(item.amountChange)">
                <span class="label">변화량</span>
                <span class="value" :class="getAmountClass(item.amountChange)">
                  {{ formatAmountWithSign(item.amountChange) }}
                </span>
              </div>
              <div class="detail-row" v-if="item.currentPrice">
                <span class="label">현재가</span>
                <span class="value">{{ formatNumber(item.currentPrice) }}원</span>
              </div>
              <div class="detail-row" v-if="item.changeRate">
                <span class="label">등락률</span>
                <span class="value" :class="getAmountClass(item.changeRate)">
                  {{ formatRate(item.changeRate) }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="no-surge-data">
          <p>선택한 투자자의 장중 데이터가 없습니다.</p>
        </div>
      </div>

      <!-- 일별 매매 동향 섹션 -->
      <div v-if="stockData && stockData.dailyTrades && stockData.dailyTrades.length > 0" class="section">
        <h2 class="section-title">📅 일별 매매 동향 (최근 30일)</h2>
        <div class="chart-container">
          <div v-for="day in stockData.dailyTrades" :key="day.tradeDate" class="daily-trade-card">
            <div class="date-header">
              {{ formatDate(day.tradeDate) }}
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
      </div>

      <div v-if="!hasSurgeData && (!stockData || !stockData.dailyTrades || stockData.dailyTrades.length === 0)" class="no-data">
        <p>💡 해당 종목의 투자자 매매 데이터가 없습니다.</p>
        <p class="hint">상위 50개 종목에 포함된 경우에만 데이터가 수집됩니다.</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import api from '../utils/api';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const stockCode = ref(route.params.stockCode);
const stockData = ref(null);
const surgeTrend = ref({ FOREIGN: [], INSTITUTION: [] });
const selectedInvestor = ref('FOREIGN');

const investorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' }
];

const hasSurgeData = computed(() => {
  return surgeTrend.value.FOREIGN.length > 0 || surgeTrend.value.INSTITUTION.length > 0;
});

const currentSurgeTrend = computed(() => {
  return surgeTrend.value[selectedInvestor.value] || [];
});

const getStockName = () => {
  if (stockData.value?.stockName) return stockData.value.stockName;
  if (surgeTrend.value.FOREIGN.length > 0) return surgeTrend.value.FOREIGN[0].stockName;
  if (surgeTrend.value.INSTITUTION.length > 0) return surgeTrend.value.INSTITUTION[0].stockName;
  return stockCode.value;
};

const fetchStockDetail = async () => {
  loading.value = true;
  try {
    // 일별 매매 데이터 조회
    const dailyResponse = await api.get(`/investor/stock/${stockCode.value}`, {
      params: { days: 30 }
    });
    if (dailyResponse.data.success && dailyResponse.data.data) {
      stockData.value = dailyResponse.data.data;
    }

    // 장중 수급 추이 조회 (외국인, 기관 모두)
    const [foreignResponse, institutionResponse] = await Promise.all([
      api.get(`/investor/surge/trend/${stockCode.value}`, { params: { investorType: 'FOREIGN' } }),
      api.get(`/investor/surge/trend/${stockCode.value}`, { params: { investorType: 'INSTITUTION' } })
    ]);

    if (foreignResponse.data.success) {
      surgeTrend.value.FOREIGN = foreignResponse.data.data || [];
    }
    if (institutionResponse.data.success) {
      surgeTrend.value.INSTITUTION = institutionResponse.data.data || [];
    }

    // 외국인 데이터가 있으면 외국인 탭, 없으면 기관 탭 선택
    if (surgeTrend.value.FOREIGN.length === 0 && surgeTrend.value.INSTITUTION.length > 0) {
      selectedInvestor.value = 'INSTITUTION';
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

const formatTime = (timeStr) => {
  if (!timeStr) return '-';
  const parts = timeStr.split(':');
  return `${parts[0]}:${parts[1]}`;
};

const formatAmount = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  return `${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const formatAmountWithSign = (value) => {
  if (!value) return '0억';
  const num = Number(value);
  const sign = num > 0 ? '+' : '';
  return `${sign}${num.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}억`;
};

const formatNumber = (value) => {
  if (!value) return '0';
  return Number(value).toLocaleString('ko-KR');
};

const formatRate = (value) => {
  if (!value) return '0.00%';
  const sign = value > 0 ? '+' : '';
  return `${sign}${Number(value).toFixed(2)}%`;
};

const getAmountClass = (value) => {
  if (!value) return '';
  return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : '';
};

const hasAmountChange = (value) => {
  if (value === null || value === undefined) return false;
  return Math.abs(Number(value)) > 0.01;
};

onMounted(() => {
  fetchStockDetail();
});
</script>

<style scoped>
.investor-stock-detail-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  padding: 2rem;
}

.content-wrapper {
  max-width: 1200px;
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
  font-size: 1.8rem;
}

.subtitle {
  color: #888;
  font-size: 1.1rem;
}

.section {
  margin-bottom: 2rem;
}

.section-title {
  color: #fff;
  font-size: 1.3rem;
  margin-bottom: 1rem;
  padding-bottom: 0.5rem;
  border-bottom: 2px solid #2a2a4a;
}

.investor-tabs {
  display: flex;
  gap: 1rem;
  margin-bottom: 1rem;
}

.tab-btn {
  padding: 0.75rem 1.5rem;
  background: #1a1a3a;
  border: 2px solid #2a2a4a;
  border-radius: 10px;
  color: #888;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
}

.tab-btn.active {
  background: #e53e3e;
  border-color: #e53e3e;
  color: white;
}

.tab-btn:hover:not(.active) {
  border-color: #4a4a8a;
  color: #fff;
}

.surge-trend-container {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.surge-item {
  background: #1a1a3a;
  border-radius: 12px;
  padding: 1rem;
  border: 1px solid #2a2a4a;
}

.time-badge {
  background: #4a4a8a;
  color: white;
  padding: 0.3rem 0.8rem;
  border-radius: 15px;
  font-size: 0.9rem;
  font-weight: 600;
  display: inline-block;
  margin-bottom: 0.75rem;
}

.surge-details {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.surge-details .detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.surge-details .detail-row.highlight {
  background: #2a2a4a;
  padding: 0.5rem;
  border-radius: 8px;
  margin: 0.25rem 0;
}

.surge-details .label {
  color: #888;
  font-size: 0.9rem;
}

.surge-details .value {
  font-weight: 600;
  color: #fff;
  font-family: monospace;
}

.no-surge-data {
  text-align: center;
  padding: 2rem;
  color: #666;
}

.chart-container {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-height: 60vh;
  overflow-y: auto;
  padding: 0.5rem;
}

.daily-trade-card {
  background: #1a1a3a;
  border-radius: 12px;
  padding: 1rem;
  border: 1px solid #2a2a4a;
  transition: all 0.3s;
}

.daily-trade-card:hover {
  border-color: #4a4a8a;
}

.date-header {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  margin-bottom: 0.75rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid #2a2a4a;
}

.investor-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1rem;
}

.investor-item {
  background: #0f0f23;
  border-radius: 10px;
  padding: 1rem;
  border: 1px solid #2a2a4a;
}

.investor-item.foreign {
  border-left: 3px solid #4299e1;
}

.investor-item.institution {
  border-left: 3px solid #48bb78;
}

.investor-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid #2a2a4a;
}

.investor-label .icon {
  font-size: 1.2rem;
}

.investor-label .name {
  font-weight: 700;
  font-size: 0.9rem;
  color: #fff;
}

.amounts {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.85rem;
}

.amount-row .label {
  color: #888;
}

.amount-row .value {
  font-weight: 600;
  font-family: monospace;
  color: #fff;
}

.amount-row.net {
  margin-top: 0.3rem;
  padding-top: 0.3rem;
  border-top: 1px solid #2a2a4a;
}

.amount-row.net .label {
  font-weight: 700;
}

.amount-row.net .value {
  font-size: 0.95rem;
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
  color: #888;
}

.no-data p {
  margin-bottom: 0.5rem;
}

.no-data .hint {
  font-size: 0.9rem;
  color: #666;
}

@media (max-width: 1024px) {
  .investor-grid {
    grid-template-columns: 1fr;
  }

  .surge-trend-container {
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

  .investor-tabs {
    flex-wrap: wrap;
  }

  .tab-btn {
    flex: 1;
    min-width: 120px;
    text-align: center;
  }
}
</style>
