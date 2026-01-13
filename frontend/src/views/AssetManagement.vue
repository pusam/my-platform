<template>
  <div class="asset-page">
    <h1>내 자산 관리</h1>

    <!-- 자산 요약 -->
    <div v-if="summary" class="summary-section">
      <div class="summary-card gold">
        <div class="card-header">
          <h2>금 (Gold)</h2>
        </div>
        <div class="card-body">
          <div class="stat-row">
            <span class="label">보유량:</span>
            <span class="value">{{ formatNumber(summary.gold?.totalQuantity) }} g</span>
          </div>
          <div class="stat-row">
            <span class="label">평균 구매가:</span>
            <span class="value">{{ formatCurrency(summary.gold?.averagePurchasePrice) }}</span>
          </div>
          <div class="stat-row">
            <span class="label">현재 시세:</span>
            <span class="value">{{ formatCurrency(summary.gold?.currentPrice) }}</span>
          </div>
          <div class="stat-row total">
            <span class="label">총 투자금액:</span>
            <span class="value">{{ formatCurrency(summary.gold?.totalInvestment) }}</span>
          </div>
          <div class="stat-row total">
            <span class="label">현재 평가금액:</span>
            <span class="value">{{ formatCurrency(summary.gold?.currentValue) }}</span>
          </div>
          <div class="stat-row profit" :class="getProfitClass(summary.gold?.profitLoss)">
            <span class="label">손익:</span>
            <span class="value">
              {{ formatCurrency(summary.gold?.profitLoss) }}
              ({{ formatNumber(summary.gold?.profitRate) }}%)
            </span>
          </div>
        </div>
      </div>

      <div class="summary-card silver">
        <div class="card-header">
          <h2>은 (Silver)</h2>
        </div>
        <div class="card-body">
          <div class="stat-row">
            <span class="label">보유량:</span>
            <span class="value">{{ formatNumber(summary.silver?.totalQuantity) }} g</span>
          </div>
          <div class="stat-row">
            <span class="label">평균 구매가:</span>
            <span class="value">{{ formatCurrency(summary.silver?.averagePurchasePrice) }}</span>
          </div>
          <div class="stat-row">
            <span class="label">현재 시세:</span>
            <span class="value">{{ formatCurrency(summary.silver?.currentPrice) }}</span>
          </div>
          <div class="stat-row total">
            <span class="label">총 투자금액:</span>
            <span class="value">{{ formatCurrency(summary.silver?.totalInvestment) }}</span>
          </div>
          <div class="stat-row total">
            <span class="label">현재 평가금액:</span>
            <span class="value">{{ formatCurrency(summary.silver?.currentValue) }}</span>
          </div>
          <div class="stat-row profit" :class="getProfitClass(summary.silver?.profitLoss)">
            <span class="label">손익:</span>
            <span class="value">
              {{ formatCurrency(summary.silver?.profitLoss) }}
              ({{ formatNumber(summary.silver?.profitRate) }}%)
            </span>
          </div>
        </div>
      </div>

      <!-- 주식 카드들 -->
      <div v-for="stock in summary.stocks" :key="stock.stockCode" class="summary-card stock">
        <div class="card-header">
          <h2>{{ stock.stockName }} ({{ stock.stockCode }})</h2>
        </div>
        <div class="card-body">
          <div class="stat-row">
            <span class="label">보유량:</span>
            <span class="value">{{ formatNumber(stock.totalQuantity) }} 주</span>
          </div>
          <div class="stat-row">
            <span class="label">평균 구매가:</span>
            <span class="value">{{ formatCurrency(stock.averagePurchasePrice) }}</span>
          </div>
          <div class="stat-row">
            <span class="label">현재 주가:</span>
            <span class="value">{{ formatCurrency(stock.currentPrice) }}</span>
          </div>
          <div class="stat-row total">
            <span class="label">총 투자금액:</span>
            <span class="value">{{ formatCurrency(stock.totalInvestment) }}</span>
          </div>
          <div class="stat-row total">
            <span class="label">현재 평가금액:</span>
            <span class="value">{{ formatCurrency(stock.currentValue) }}</span>
          </div>
          <div class="stat-row profit" :class="getProfitClass(stock.profitLoss)">
            <span class="label">손익:</span>
            <span class="value">
              {{ formatCurrency(stock.profitLoss) }}
              ({{ formatNumber(stock.profitRate) }}%)
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 자산 등록 버튼 -->
    <div class="action-bar">
      <button @click="showAddModal = true" class="btn btn-primary">
        + 자산 등록
      </button>
    </div>

    <!-- 자산 목록 -->
    <div class="asset-list">
      <h2>구매 내역</h2>
      <div v-if="assets.length === 0" class="empty-message">
        등록된 자산이 없습니다. 자산을 등록해보세요!
      </div>
      <table v-else>
        <thead>
          <tr>
            <th>종류</th>
            <th>구매일</th>
            <th>보유량</th>
            <th>구매가</th>
            <th>총액</th>
            <th>메모</th>
            <th>삭제</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="asset in assets" :key="asset.id">
            <td>{{ getAssetTypeLabel(asset) }}</td>
            <td>{{ formatDate(asset.purchaseDate) }}</td>
            <td>{{ formatNumber(asset.quantity) }} {{ asset.assetType === 'STOCK' ? '주' : 'g' }}</td>
            <td>{{ formatCurrency(asset.purchasePrice) }}</td>
            <td>{{ formatCurrency(asset.totalAmount) }}</td>
            <td>{{ asset.memo || '-' }}</td>
            <td>
              <button @click="deleteAsset(asset.id)" class="btn btn-delete">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 자산 등록 모달 -->
    <div v-if="showAddModal" class="modal-overlay" @click="closeModal">
      <div class="modal-content" @click.stop>
        <h2>자산 등록</h2>
        <form @submit.prevent="addAsset">
          <div class="form-group">
            <label>종류</label>
            <select v-model="newAsset.assetType" required @change="onAssetTypeChange">
              <option value="GOLD">금 (Gold)</option>
              <option value="SILVER">은 (Silver)</option>
              <option value="STOCK">주식 (Stock)</option>
            </select>
          </div>

          <!-- 주식 검색 (주식 선택시만 표시) -->
          <div v-if="newAsset.assetType === 'STOCK'" class="form-group">
            <label>종목 검색</label>
            <div class="stock-search">
              <input
                type="text"
                v-model="stockSearchKeyword"
                placeholder="종목명 또는 종목코드 입력"
                @input="searchStocksDebounced"
              />
              <div v-if="stockSearchResults.length > 0" class="search-results">
                <div
                  v-for="stock in stockSearchResults"
                  :key="stock.stockCode"
                  class="search-result-item"
                  @click="selectStock(stock)"
                >
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                  <span class="stock-price">{{ formatCurrency(stock.currentPrice) }}</span>
                </div>
              </div>
            </div>
            <div v-if="newAsset.stockCode" class="selected-stock">
              선택됨: {{ newAsset.stockName }} ({{ newAsset.stockCode }})
            </div>
          </div>

          <div class="form-group">
            <label>구매일</label>
            <input type="date" v-model="newAsset.purchaseDate" required />
          </div>

          <div class="form-group">
            <label>{{ newAsset.assetType === 'STOCK' ? '보유 주수' : '보유량 (그램)' }}</label>
            <input type="number" step="0.0001" v-model="newAsset.quantity" required />
          </div>

          <div class="form-group">
            <label>{{ newAsset.assetType === 'STOCK' ? '주당 구매가격 (원)' : '구매 당시 그램당 가격 (원)' }}</label>
            <input type="number" step="0.01" v-model="newAsset.purchasePrice" required />
          </div>

          <div class="form-group">
            <label>메모 (선택)</label>
            <input type="text" v-model="newAsset.memo" placeholder="예: 장기 투자용" />
          </div>

          <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>

          <div class="modal-actions">
            <button type="submit" class="btn btn-primary" :disabled="newAsset.assetType === 'STOCK' && !newAsset.stockCode">등록</button>
            <button type="button" @click="closeModal" class="btn btn-secondary">취소</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { assetAPI, stockAPI } from '../utils/api';

const summary = ref(null);
const assets = ref([]);
const showAddModal = ref(false);
const errorMessage = ref('');
const stockSearchKeyword = ref('');
const stockSearchResults = ref([]);
let searchTimeout = null;

const newAsset = ref({
  assetType: 'GOLD',
  stockCode: '',
  stockName: '',
  quantity: '',
  purchasePrice: '',
  purchaseDate: new Date().toISOString().split('T')[0],
  memo: ''
});

const loadData = async () => {
  try {
    const summaryResponse = await assetAPI.getAssetSummary();
    summary.value = summaryResponse.data.data;
    assets.value = summary.value.assets || [];
  } catch (error) {
    console.error('Failed to load data:', error);
  }
};

const onAssetTypeChange = () => {
  newAsset.value.stockCode = '';
  newAsset.value.stockName = '';
  stockSearchKeyword.value = '';
  stockSearchResults.value = [];
};

const searchStocksDebounced = () => {
  if (searchTimeout) {
    clearTimeout(searchTimeout);
  }
  searchTimeout = setTimeout(searchStocks, 500);
};

const searchStocks = async () => {
  if (!stockSearchKeyword.value || stockSearchKeyword.value.length < 2) {
    stockSearchResults.value = [];
    return;
  }

  try {
    const response = await stockAPI.searchStocks(stockSearchKeyword.value);
    stockSearchResults.value = response.data.data || [];
  } catch (error) {
    console.error('Stock search failed:', error);
    stockSearchResults.value = [];
  }
};

const selectStock = (stock) => {
  newAsset.value.stockCode = stock.stockCode;
  newAsset.value.stockName = stock.stockName;
  newAsset.value.purchasePrice = stock.currentPrice;
  stockSearchKeyword.value = '';
  stockSearchResults.value = [];
};

const addAsset = async () => {
  try {
    errorMessage.value = '';

    if (newAsset.value.assetType === 'STOCK' && !newAsset.value.stockCode) {
      errorMessage.value = '종목을 선택해주세요.';
      return;
    }

    await assetAPI.addAsset(newAsset.value);
    closeModal();
    await loadData();
  } catch (error) {
    console.error('Failed to add asset:', error);
    errorMessage.value = error.response?.data?.message || '자산 등록에 실패했습니다.';
  }
};

const deleteAsset = async (assetId) => {
  if (!confirm('정말 이 자산을 삭제하시겠습니까?')) {
    return;
  }

  try {
    await assetAPI.deleteAsset(assetId);
    await loadData();
  } catch (error) {
    console.error('Failed to delete asset:', error);
    alert('자산 삭제에 실패했습니다.');
  }
};

const closeModal = () => {
  showAddModal.value = false;
  errorMessage.value = '';
  stockSearchKeyword.value = '';
  stockSearchResults.value = [];
  newAsset.value = {
    assetType: 'GOLD',
    stockCode: '',
    stockName: '',
    quantity: '',
    purchasePrice: '',
    purchaseDate: new Date().toISOString().split('T')[0],
    memo: ''
  };
};

const getAssetTypeLabel = (asset) => {
  if (asset.assetType === 'GOLD') return '금';
  if (asset.assetType === 'SILVER') return '은';
  if (asset.assetType === 'STOCK') return `${asset.stockName}`;
  return asset.assetType;
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0
  }).format(value);
};

const formatNumber = (value) => {
  if (!value) return '0';
  return new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4
  }).format(value);
};

const formatDate = (date) => {
  if (!date) return '-';
  return new Date(date).toLocaleDateString('ko-KR');
};

const getProfitClass = (profitLoss) => {
  if (!profitLoss) return '';
  return profitLoss > 0 ? 'positive' : profitLoss < 0 ? 'negative' : '';
};

onMounted(() => {
  loadData();
});
</script>

<style scoped>
.asset-page {
  padding: 30px;
  max-width: 1400px;
  margin: 0 auto;
}

.asset-page h1 {
  margin-bottom: 30px;
  color: #333;
}

.summary-section {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(350px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.summary-card {
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.summary-card.gold .card-header {
  background: linear-gradient(135deg, #f7b733 0%, #fc4a1a 100%);
}

.summary-card.silver .card-header {
  background: linear-gradient(135deg, #c0c0c0 0%, #808080 100%);
}

.summary-card.stock .card-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.card-header {
  padding: 20px;
  color: white;
}

.card-header h2 {
  margin: 0;
  font-size: 20px;
}

.card-body {
  padding: 20px;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid #eee;
}

.stat-row.total {
  font-weight: 600;
  border-top: 2px solid #ddd;
  margin-top: 10px;
  padding-top: 15px;
}

.stat-row.profit {
  font-size: 18px;
  font-weight: 700;
  border-bottom: none;
}

.stat-row.profit.positive {
  color: #e74c3c;
}

.stat-row.profit.negative {
  color: #3498db;
}

.action-bar {
  margin-bottom: 20px;
  display: flex;
  justify-content: flex-end;
}

.asset-list {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.asset-list h2 {
  margin-bottom: 20px;
  color: #333;
}

.empty-message {
  text-align: center;
  padding: 40px;
  color: #999;
}

table {
  width: 100%;
  border-collapse: collapse;
}

thead {
  background: #f5f5f5;
}

th {
  padding: 12px;
  text-align: left;
  font-weight: 600;
  border-bottom: 2px solid #ddd;
}

td {
  padding: 12px;
  border-bottom: 1px solid #eee;
}

.btn {
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(102, 126, 234, 0.4);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary {
  background: #ccc;
  color: #333;
}

.btn-delete {
  background: #e74c3c;
  color: white;
  padding: 5px 15px;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 12px;
  padding: 30px;
  width: 90%;
  max-width: 500px;
  max-height: 90vh;
  overflow-y: auto;
}

.modal-content h2 {
  margin-bottom: 20px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-weight: 500;
  color: #555;
}

.form-group input,
.form-group select {
  width: 100%;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

.stock-search {
  position: relative;
}

.search-results {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: white;
  border: 1px solid #ddd;
  border-radius: 6px;
  max-height: 200px;
  overflow-y: auto;
  z-index: 100;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.search-result-item {
  padding: 12px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #eee;
}

.search-result-item:hover {
  background: #f5f5f5;
}

.search-result-item:last-child {
  border-bottom: none;
}

.stock-name {
  font-weight: 500;
}

.stock-code {
  color: #888;
  font-size: 12px;
}

.stock-price {
  color: #667eea;
  font-weight: 500;
}

.selected-stock {
  margin-top: 10px;
  padding: 10px;
  background: #e8f4f8;
  border-radius: 6px;
  color: #2980b9;
  font-weight: 500;
}

.error-message {
  background: #fee;
  color: #c33;
  padding: 10px;
  border-radius: 6px;
  margin-bottom: 15px;
}

.modal-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
</style>
