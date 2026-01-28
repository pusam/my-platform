<template>
  <div class="investor-trade-page">
    <LoadingSpinner v-if="loading" />
    <div v-else class="content-wrapper">
      <div class="page-header">
        <button @click="goBack" class="back-button">← 돌아가기</button>
        <h1>투자자별 매매 동향</h1>
        <p class="subtitle">외국인, 기관, 개인의 상위 매매 종목을 확인하세요</p>
      </div>
      <div class="trade-type-selector">
        <button :class="['trade-type-btn', { active: tradeType === 'BUY' }]" @click="changeTradeType('BUY')">
          📈 매수 TOP 50
        </button>
        <button :class="['trade-type-btn', { active: tradeType === 'SELL' }]" @click="changeTradeType('SELL')">
          📉 매도 TOP 50
        </button>
        <button class="consecutive-btn" @click="goToConsecutive">
          🔥 연속 매수 종목
        </button>
        <button class="surge-btn" @click="goToSurge">
          ⚡ 수급 급증
        </button>
        <button class="refresh-btn" @click="collectData" :disabled="collecting">
          🔄 {{ collecting ? '수집 중...' : '데이터 수집' }}
        </button>
        <button class="recollect-btn" @click="recollectData" :disabled="collecting">
          🗑️ {{ collecting ? '처리 중...' : '삭제 후 재수집' }}
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
            <tr v-for="(trade, index) in currentTrades" :key="`${trade.stockCode}-${index}`" class="trade-row">
              <td class="rank">{{ trade.rankNum }}</td>
              <td class="stock-name">{{ trade.stockName }}</td>
              <td class="stock-code">{{ trade.stockCode }}</td>
              <td class="amount" :class="{ positive: trade.netBuyAmount > 0, negative: trade.netBuyAmount < 0 }">
                {{ formatNumber(trade.netBuyAmount) }}
              </td>
              <td class="price">{{ formatNumber(trade.currentPrice) }}</td>
              <td class="rate" :class="{ positive: trade.changeRate > 0, negative: trade.changeRate < 0 }">
                {{ formatRate(trade.changeRate) }}
              </td>
              <td>
                <button @click="goToDetail(trade.stockCode)" class="detail-btn">상세보기</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="no-data">
        <p>💡 데이터가 없습니다. 데이터를 수집해주세요.</p>
        <button @click="collectData" class="collect-btn" :disabled="collecting">
          {{ collecting ? '수집 중...' : '데이터 수집하기' }}
        </button>
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
const tradeType = ref('BUY');
const selectedInvestor = ref('FOREIGN');
const allTrades = ref({});
const investorTypes = [
  { value: 'FOREIGN', label: '외국인', icon: '🌍' },
  { value: 'INSTITUTION', label: '기관', icon: '🏢' },
  { value: 'INDIVIDUAL', label: '개인', icon: '👤' }
];
const currentTrades = computed(() => {
  return allTrades.value[selectedInvestor.value] || [];
});
const changeTradeType = (type) => {
  tradeType.value = type;
  fetchData();
};
const fetchData = async () => {
  loading.value = true;
  try {
    const response = await api.get('/investor/all-top-trades', {
      params: {
        tradeType: tradeType.value,
        limit: 50
      }
    });
    if (response.data.success) {
      allTrades.value = response.data.data;
    }
  } catch (error) {
    console.error('투자자 매매 데이터 조회 오류:', error);
  } finally {
    loading.value = false;
  }
};
const collectData = async () => {
  if (collecting.value) return;
  collecting.value = true;
  try {
    const response = await api.post('/investor/collect/recent', null, {
      params: { days: 5 }
    });
    if (response.data.success) {
      alert('데이터 수집이 완료되었습니다!');
      await fetchData();
    }
  } catch (error) {
    console.error('데이터 수집 오류:', error);
    alert('데이터 수집에 실패했습니다.');
  } finally {
    collecting.value = false;
  }
};
const recollectData = async () => {
  if (collecting.value) return;
  if (!confirm('기존 데이터를 모두 삭제하고 새로 수집합니다. 계속하시겠습니까?')) return;
  collecting.value = true;
  try {
    const response = await api.post('/investor/recollect');
    if (response.data.success) {
      const data = response.data.data;
      alert(`삭제: ${data.deletedCount}건, 수집: ${data.collectedCount}건 완료!`);
      await fetchData();
    }
  } catch (error) {
    console.error('재수집 오류:', error);
    alert('재수집에 실패했습니다.');
  } finally {
    collecting.value = false;
  }
};
const goToDetail = (stockCode) => {
  router.push(`/investor-stock/${stockCode}`);
};
const goToConsecutive = () => {
  router.push('/consecutive-buy');
};
const goToSurge = () => {
  router.push('/investor-surge');
};
const goBack = () => {
  router.back();
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
  fetchData();
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
.page-header h1 {
  color: #2d3748;
  margin-bottom: 0.5rem;
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
.consecutive-btn {
  padding: 1rem 2rem;
  border: 2px solid #ed8936;
  background: linear-gradient(135deg, #ed8936 0%, #dd6b20 100%);
  color: white;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
}
.consecutive-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(237, 137, 54, 0.4);
}
.surge-btn {
  padding: 1rem 2rem;
  border: 2px solid #e53e3e;
  background: linear-gradient(135deg, #e53e3e 0%, #c53030 100%);
  color: white;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
}
.surge-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(229, 62, 62, 0.4);
}
.refresh-btn {
  padding: 1rem 2rem;
  border: 2px solid #38a169;
  background: linear-gradient(135deg, #48bb78 0%, #38a169 100%);
  color: white;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
}
.refresh-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(72, 187, 120, 0.4);
}
.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.recollect-btn {
  padding: 1rem 2rem;
  border: 2px solid #805ad5;
  background: linear-gradient(135deg, #9f7aea 0%, #805ad5 100%);
  color: white;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1.1rem;
  font-weight: 600;
  transition: all 0.3s;
}
.recollect-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(159, 122, 234, 0.4);
}
.recollect-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
.amount {
  text-align: right;
  font-family: monospace;
  font-weight: 600;
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
.collect-btn {
  margin-top: 1.5rem;
  background: #48bb78;
  color: white;
  border: none;
  padding: 1rem 2rem;
  border-radius: 10px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 600;
  transition: all 0.3s;
}
.collect-btn:hover:not(:disabled) {
  background: #38a169;
  transform: translateY(-2px);
}
.collect-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
