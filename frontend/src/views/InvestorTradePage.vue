<template>
  <div class="investor-trade-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <BackButton />
        <div class="title-row">
          <h1>투자자별 매매 동향</h1>
          <span class="data-timestamp" :class="dataStatus">
            {{ dataStatusIcon }} 데이터 기준: {{ dataTimestamp }}
          </span>
        </div>
        <p class="subtitle">외국인, 기관, 연기금의 상위 매매 종목을 확인하세요</p>
        <p v-if="collecting" class="collecting-status">🔄 데이터 수집 중...</p>
      </div>
      <div class="trade-type-selector">
        <button :class="['trade-type-btn', { active: tradeType === 'BUY' }]" @click="changeTradeType('BUY')">
          📈 매수 TOP 50
        </button>
        <button :class="['trade-type-btn', { active: tradeType === 'SELL' }]" @click="changeTradeType('SELL')">
          📉 매도 TOP 50
        </button>
      </div>
      <div class="investor-tabs">
        <button v-for="type in investorTypes" :key="type.value" :class="['tab-btn', { active: selectedInvestor === type.value }]" @click="selectedInvestor = type.value">
          {{ type.icon }} {{ type.label }}
        </button>
      </div>
      <div v-if="currentTrades.length > 0" class="trades-table">
        <table>
          <thead>
            <tr>
              <th>순위</th>
              <th>종목명</th>
              <th>종목코드</th>
              <th>{{ tradeType === 'BUY' ? '순매수' : '순매도' }} (억원)</th>
              <th>현재가</th>
              <th>등락률</th>
              <th>상세보기</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(trade, index) in currentTrades" :key="`${trade.stockCode}-${index}`" class="trade-row" @click="goToDetail(trade.stockCode)">
              <td class="rank">{{ trade.rankNum }}</td>
              <td class="stock-name">{{ trade.stockName }}</td>
              <td class="stock-code">{{ trade.stockCode }}</td>
              <td class="amount-cell">
                <div class="amount-bar-container">
                  <div
                    class="amount-bar"
                    :class="tradeType === 'BUY' ? 'bar-buy' : 'bar-sell'"
                    :style="{ width: getBarWidth(trade.netBuyAmount) + '%' }"
                  ></div>
                  <span class="amount-value" :class="{ positive: trade.netBuyAmount > 0, negative: trade.netBuyAmount < 0 }">
                    {{ formatNumber(Math.abs(trade.netBuyAmount)) }}
                  </span>
                </div>
              </td>
              <td class="price">{{ formatNumber(trade.currentPrice) }}</td>
              <td class="rate" :class="{ positive: trade.changeRate > 0, negative: trade.changeRate < 0 }">
                {{ formatRate(trade.changeRate) }}
              </td>
              <td>
                <button @click.stop="goToDetail(trade.stockCode)" class="detail-btn">상세보기</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="no-data">
        <p v-if="collecting">🔄 데이터를 수집하고 있습니다...</p>
        <p v-else>💡 데이터가 없습니다.</p>
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
const collecting = ref(false);
const tradeType = ref('BUY');
const selectedInvestor = ref('FOREIGN');
const allTrades = ref({});
const dataTimestamp = ref('-');
const dataStatus = ref('status-unknown');
const investorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'PENSION', label: '연기금', icon: '💎' }
];
const currentTrades = computed(() => {
  return allTrades.value[selectedInvestor.value] || [];
});

// 데이터 바 너비 계산 (최대값 기준 %)
const maxAmount = computed(() => {
  const trades = currentTrades.value;
  if (!trades.length) return 1;
  return Math.max(...trades.map(t => Math.abs(t.netBuyAmount || 0)));
});

const getBarWidth = (amount) => {
  if (!amount || !maxAmount.value) return 0;
  return (Math.abs(amount) / maxAmount.value) * 100;
};

// 데이터 상태 아이콘
const dataStatusIcon = computed(() => {
  if (dataStatus.value === 'status-live') return '🔴';
  if (dataStatus.value === 'status-confirmed') return '✅';
  return '📊';
});

// 장 마감 여부 확인 (15:30 이후면 확정)
const updateDataStatus = () => {
  const now = new Date();
  const hours = now.getHours();
  const minutes = now.getMinutes();

  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const hour = String(hours).padStart(2, '0');
  const min = String(minutes).padStart(2, '0');

  if (hours >= 15 && minutes >= 30) {
    dataTimestamp.value = `${month}.${day} 장 마감 확정`;
    dataStatus.value = 'status-confirmed';
  } else if (hours >= 9 && hours < 16) {
    dataTimestamp.value = `${month}.${day} ${hour}:${min} (잠정)`;
    dataStatus.value = 'status-live';
  } else {
    dataTimestamp.value = `${month}.${day} ${hour}:${min}`;
    dataStatus.value = 'status-unknown';
  }
};
const changeTradeType = (type) => {
  tradeType.value = type;
  fetchData();
};
const fetchData = async () => {
  loading.value = true;
  try {
    const response = await investorAPI.getAllTopTrades(tradeType.value, 50);
    if (response.data.success) {
      allTrades.value = response.data.data;
      updateDataStatus();
    }
  } catch (error) {
    console.error('투자자 매매 데이터 조회 오류:', error);
  } finally {
    loading.value = false;
  }
};
const autoCollectAndFetch = async () => {
  // 먼저 기존 데이터 조회 시도
  await fetchData();

  // 데이터가 없으면 오늘 데이터만 수집 (전체 삭제 X)
  if (Object.values(allTrades.value).every(arr => arr.length === 0)) {
    collecting.value = true;
    try {
      // /investor/collect: 오늘 데이터만 수집 (기존 데이터 유지)
      // /investor/recollect: 전체 삭제 후 재수집 (사용 금지!)
      await investorAPI.collect();
      await fetchData();
    } catch (error) {
      console.error('데이터 수집 오류:', error);
    } finally {
      collecting.value = false;
    }
  }
};
const goToDetail = (stockCode) => {
  router.push(`/stock/${stockCode}`);
};
const formatNumber = (value) => {
  if (!value) return '0';
  return Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 });
};
const formatRate = (value) => {
  if (!value) return '0.00%';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
};
onMounted(() => {
  autoCollectAndFetch();
});
</script>
<style scoped>
.investor-trade-page {
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
.title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  flex-wrap: wrap;
}

.page-header h1 {
  color: #2d3748;
  margin-bottom: 0.5rem;
}

/* 데이터 타임스탬프 뱃지 */
.data-timestamp {
  font-size: 0.85rem;
  font-weight: 600;
  padding: 0.4rem 0.8rem;
  border-radius: 20px;
  white-space: nowrap;
}

.data-timestamp.status-live {
  background: rgba(239, 68, 68, 0.1);
  color: #e53e3e;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

.data-timestamp.status-confirmed {
  background: rgba(34, 197, 94, 0.1);
  color: #16a34a;
  border: 1px solid rgba(34, 197, 94, 0.3);
}

.data-timestamp.status-unknown {
  background: rgba(107, 114, 128, 0.1);
  color: #6b7280;
  border: 1px solid rgba(107, 114, 128, 0.3);
}
.subtitle {
  color: #718096;
  font-size: 1.1rem;
}
.trade-type-selector {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-bottom: 2rem;
}
.trade-type-btn {
  padding: 1rem 2rem;
  border: 2px solid #667eea;
  background: white;
  color: #667eea;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
}
.trade-type-btn.active {
  background: #667eea;
  color: white;
}
.trade-type-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(102, 126, 234, 0.3);
}
.collecting-status {
  color: #667eea;
  font-weight: 600;
  margin-top: 0.5rem;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
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
.trades-table {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
}
thead {
  background: #f7fafc;
}
th {
  padding: 1rem;
  text-align: left;
  font-weight: 600;
  color: #2d3748;
  border-bottom: 2px solid #e2e8f0;
}
td {
  padding: 1rem;
  border-bottom: 1px solid #e2e8f0;
}
.trade-row {
  cursor: pointer;
}
.trade-row:hover {
  background: #f7fafc;
}
.rank {
  font-weight: 700;
  color: #667eea;
  text-align: center;
  font-size: 1.1rem;
}
.stock-name {
  font-weight: 600;
  color: #2d3748;
}
.stock-code {
  color: #718096;
  font-family: monospace;
}
/* 순매수 금액 데이터 바 */
.amount-cell {
  padding: 0.5rem 1rem !important;
}

.amount-bar-container {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  height: 32px;
  border-radius: 4px;
  overflow: hidden;
  background: #f7fafc;
}

.amount-bar {
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
}

.amount-bar.bar-buy {
  background: linear-gradient(90deg, rgba(229, 62, 62, 0.15) 0%, rgba(229, 62, 62, 0.25) 100%);
}

.amount-bar.bar-sell {
  background: linear-gradient(90deg, rgba(49, 130, 206, 0.15) 0%, rgba(49, 130, 206, 0.25) 100%);
}

.amount-value {
  position: relative;
  z-index: 1;
  font-family: monospace;
  font-weight: 600;
  padding-right: 0.5rem;
}
.price {
  text-align: right;
  font-weight: 600;
  font-family: monospace;
}
.rate {
  text-align: right;
  font-weight: 600;
  font-family: monospace;
}
.positive {
  color: #e53e3e;
}
.negative {
  color: #3182ce;
}
.detail-btn {
  background: #667eea;
  color: white;
  border: none;
  padding: 0.5rem 1rem;
  border-radius: 5px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s;
}
.detail-btn:hover {
  background: #5568d3;
  transform: scale(1.05);
}
.no-data {
  text-align: center;
  padding: 3rem;
  color: #718096;
  font-size: 1.2rem;
}
@media (max-width: 768px) {
  .investor-trade-page {
    padding: 1rem;
  }
  .content-wrapper {
    padding: 1rem;
  }
  .trade-type-selector {
    flex-direction: column;
  }
  .investor-tabs {
    flex-wrap: wrap;
  }
  table {
    font-size: 0.85rem;
  }
  th, td {
    padding: 0.5rem;
  }
}
</style>
