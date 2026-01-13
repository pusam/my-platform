<template>
  <div class="asset-page">
    <div class="header">
      <h1>내 자산 관리</h1>
      <div class="header-actions">
        <button @click="goBack" class="back-btn">← 돌아가기</button>
        <button @click="logout" class="logout-btn">로그아웃</button>
      </div>
    </div>

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
              <option value="OTHER">기타 (Other)</option>
            </select>
          </div>

          <!-- 기타 자산명 입력 (기타 선택시만 표시) -->
          <div v-if="newAsset.assetType === 'OTHER'" class="form-group">
            <label>자산명</label>
            <input
              type="text"
              v-model="newAsset.otherName"
              placeholder="예: 비트코인, 달러, 부동산 등"
              required
            />
          </div>

          <!-- 주식 검색 (주식 선택시만 표시) -->
          <div v-if="newAsset.assetType === 'STOCK'" class="form-group">
            <label>종목 검색</label>
            <input
              type="text"
              v-model="stockSearchKeyword"
              placeholder="종목명 또는 종목코드 입력 (2글자 이상)"
              @input="searchStocksDebounced"
            />
            <p class="search-hint">※ 검색어를 입력하면 관련 종목이 아래에 표시됩니다</p>
          </div>

          <!-- 검색 결과 리스트 (스크롤 가능) -->
          <div v-if="newAsset.assetType === 'STOCK' && stockSearchResults.length > 0" class="form-group">
            <label>검색 결과 ({{ stockSearchResults.length }}개)</label>
            <div class="stock-results-list">
              <div
                v-for="stock in stockSearchResults"
                :key="stock.stockCode"
                class="stock-result-item"
                :class="{ selected: newAsset.stockCode === stock.stockCode }"
                @click="selectStock(stock)"
              >
                <div class="stock-info">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                </div>
                <span class="stock-price">{{ formatCurrency(stock.currentPrice) }}</span>
              </div>
            </div>
          </div>

          <!-- 선택된 종목 표시 -->
          <div v-if="newAsset.assetType === 'STOCK' && newAsset.stockCode" class="form-group">
            <label>선택된 종목</label>
            <div class="selected-stock-box">
              <div class="selected-info">
                <strong>{{ newAsset.stockName }}</strong>
                <span class="selected-code">({{ newAsset.stockCode }})</span>
              </div>
              <button type="button" @click="clearStockSelection" class="btn-clear">✕ 취소</button>
            </div>
          </div>

          <div class="form-group">
            <label>구매일</label>
            <input type="date" v-model="newAsset.purchaseDate" required />
          </div>

          <div class="form-group">
            <label>{{ getQuantityLabel() }}</label>
            <input type="number" step="0.0001" v-model="newAsset.quantity" required />
          </div>

          <div class="form-group">
            <label>{{ getPriceLabel() }}</label>
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
import { useRouter } from 'vue-router';
import { assetAPI, stockAPI } from '../utils/api';
import { UserManager } from '../utils/auth';

const router = useRouter();

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
  otherName: '',
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
  newAsset.value.otherName = '';
  stockSearchKeyword.value = '';
  stockSearchResults.value = [];
  selectedStockCode.value = '';
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
  // 검색 결과는 유지하여 다른 종목도 볼 수 있도록 함
};

const clearStockSelection = () => {
  newAsset.value.stockCode = '';
  newAsset.value.stockName = '';
  newAsset.value.purchasePrice = '';
  selectedStockCode.value = '';
};

const addAsset = async () => {
  try {
    errorMessage.value = '';

    if (newAsset.value.assetType === 'STOCK' && !newAsset.value.stockCode) {
      errorMessage.value = '종목을 선택해주세요.';
      return;
    }

    if (newAsset.value.assetType === 'OTHER' && !newAsset.value.otherName) {
      errorMessage.value = '자산명을 입력해주세요.';
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
  selectedStockCode.value = '';
  newAsset.value = {
    assetType: 'GOLD',
    stockCode: '',
    stockName: '',
    otherName: '',
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
  if (asset.assetType === 'OTHER') return asset.otherName || '기타';
  return asset.assetType;
};

const getQuantityLabel = () => {
  if (newAsset.value.assetType === 'STOCK') return '보유 주수';
  if (newAsset.value.assetType === 'OTHER') return '보유량';
  return '보유량 (그램)';
};

const getPriceLabel = () => {
  if (newAsset.value.assetType === 'STOCK') return '주당 구매가격 (원)';
  if (newAsset.value.assetType === 'OTHER') return '단위당 구매가격 (원)';
  return '구매 당시 그램당 가격 (원)';
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

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
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

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 30px;
  padding-bottom: 20px;
  border-bottom: 2px solid #e0e0e0;
  flex-wrap: nowrap;
}

.header h1 {
  margin: 0;
  color: #333;
  font-size: 28px;
  white-space: nowrap;
}

.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.back-btn, .logout-btn {
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
  white-space: nowrap;
}

.back-btn {
  background: #007bff;
  color: white;
}

.back-btn:hover {
  background: #0056b3;
}

.logout-btn {
  background: #dc3545;
  color: white;
}

.logout-btn:hover {
  background: #c82333;
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

.search-hint {
  margin-top: 5px;
  font-size: 12px;
  color: #666;
  font-style: italic;
}

.stock-results-list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid #ddd;
  border-radius: 6px;
  background: white;
}

.stock-result-item {
  padding: 12px 15px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  border-bottom: 1px solid #f0f0f0;
  transition: background-color 0.2s;
}

.stock-result-item:last-child {
  border-bottom: none;
}

.stock-result-item:hover {
  background-color: #f8f9fa;
}

.stock-result-item.selected {
  background-color: #e3f2fd;
  border-left: 3px solid #2196f3;
}

.stock-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stock-name {
  font-weight: 600;
  font-size: 14px;
  color: #333;
}

.stock-code {
  color: #888;
  font-size: 12px;
}

.stock-price {
  color: #2196f3;
  font-weight: 600;
  font-size: 14px;
}

.selected-stock-box {
  padding: 15px;
  background: linear-gradient(135deg, #e3f2fd 0%, #f3e5f5 100%);
  border: 2px solid #2196f3;
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.selected-info {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1976d2;
}

.selected-info strong {
  font-size: 16px;
}

.selected-code {
  font-size: 13px;
  color: #666;
}

.btn-clear {
  background: #f44336;
  color: white;
  border: none;
  border-radius: 5px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.2s;
}

.btn-clear:hover {
  background: #d32f2f;
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
