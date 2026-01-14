<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>자산 관리</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 자산 등록 버튼 -->
      <div class="action-bar">
        <button @click="showAddModal = true" class="btn btn-primary">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          자산 등록
        </button>
      </div>

      <!-- 자산 요약 카드들 -->
      <section v-if="summary" class="summary-grid">
        <article class="summary-card gold">
          <div class="card-header">
            <div class="card-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v12"/>
                <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
              </svg>
            </div>
            <h2>금 (Gold)</h2>
          </div>
          <div class="card-body">
            <div class="stat-row">
              <span class="label">보유량</span>
              <span class="value">{{ formatNumber(summary.gold?.totalQuantity) }} g</span>
            </div>
            <div class="stat-row">
              <span class="label">평균 구매가</span>
              <span class="value">{{ formatCurrency(summary.gold?.averagePurchasePrice) }}</span>
            </div>
            <div class="stat-row">
              <span class="label">현재 시세</span>
              <span class="value">{{ formatCurrency(summary.gold?.currentPrice) }}</span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-row highlight">
              <span class="label">총 투자금액</span>
              <span class="value">{{ formatCurrency(summary.gold?.totalInvestment) }}</span>
            </div>
            <div class="stat-row highlight">
              <span class="label">현재 평가금액</span>
              <span class="value">{{ formatCurrency(summary.gold?.currentValue) }}</span>
            </div>
            <div class="stat-row profit" :class="getProfitClass(summary.gold?.profitLoss)">
              <span class="label">손익</span>
              <span class="value">
                {{ formatCurrency(summary.gold?.profitLoss) }}
                <small>({{ formatNumber(summary.gold?.profitRate) }}%)</small>
              </span>
            </div>
          </div>
        </article>

        <article class="summary-card silver">
          <div class="card-header">
            <div class="card-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v12"/>
                <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
              </svg>
            </div>
            <h2>은 (Silver)</h2>
          </div>
          <div class="card-body">
            <div class="stat-row">
              <span class="label">보유량</span>
              <span class="value">{{ formatNumber(summary.silver?.totalQuantity) }} g</span>
            </div>
            <div class="stat-row">
              <span class="label">평균 구매가</span>
              <span class="value">{{ formatCurrency(summary.silver?.averagePurchasePrice) }}</span>
            </div>
            <div class="stat-row">
              <span class="label">현재 시세</span>
              <span class="value">{{ formatCurrency(summary.silver?.currentPrice) }}</span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-row highlight">
              <span class="label">총 투자금액</span>
              <span class="value">{{ formatCurrency(summary.silver?.totalInvestment) }}</span>
            </div>
            <div class="stat-row highlight">
              <span class="label">현재 평가금액</span>
              <span class="value">{{ formatCurrency(summary.silver?.currentValue) }}</span>
            </div>
            <div class="stat-row profit" :class="getProfitClass(summary.silver?.profitLoss)">
              <span class="label">손익</span>
              <span class="value">
                {{ formatCurrency(summary.silver?.profitLoss) }}
                <small>({{ formatNumber(summary.silver?.profitRate) }}%)</small>
              </span>
            </div>
          </div>
        </article>

        <!-- 주식 카드들 -->
        <article v-for="stock in summary.stocks" :key="stock.stockCode" class="summary-card stock">
          <div class="card-header">
            <div class="card-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <polyline points="22,7 13.5,15.5 8.5,10.5 2,17"/>
                <polyline points="16,7 22,7 22,13"/>
              </svg>
            </div>
            <h2>{{ stock.stockName }}</h2>
            <span class="stock-code">{{ stock.stockCode }}</span>
          </div>
          <div class="card-body">
            <div class="stat-row">
              <span class="label">보유량</span>
              <span class="value">{{ formatNumber(stock.totalQuantity) }} 주</span>
            </div>
            <div class="stat-row">
              <span class="label">평균 구매가</span>
              <span class="value">{{ formatCurrency(stock.averagePurchasePrice) }}</span>
            </div>
            <div class="stat-row">
              <span class="label">현재 주가</span>
              <span class="value">{{ formatCurrency(stock.currentPrice) }}</span>
            </div>
            <div class="stat-divider"></div>
            <div class="stat-row highlight">
              <span class="label">총 투자금액</span>
              <span class="value">{{ formatCurrency(stock.totalInvestment) }}</span>
            </div>
            <div class="stat-row highlight">
              <span class="label">현재 평가금액</span>
              <span class="value">{{ formatCurrency(stock.currentValue) }}</span>
            </div>
            <div class="stat-row profit" :class="getProfitClass(stock.profitLoss)">
              <span class="label">손익</span>
              <span class="value">
                {{ formatCurrency(stock.profitLoss) }}
                <small>({{ formatNumber(stock.profitRate) }}%)</small>
              </span>
            </div>
          </div>
        </article>
      </section>

      <!-- 구매 내역 -->
      <section class="history-section">
        <div class="section-header">
          <h2>구매 내역</h2>
        </div>

        <div v-if="assets.length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <line x1="12" y1="1" x2="12" y2="23"/>
              <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
            </svg>
          </div>
          <h3>등록된 자산이 없습니다</h3>
          <p>자산을 등록해보세요!</p>
          <button @click="showAddModal = true" class="btn btn-primary">자산 등록하기</button>
        </div>

        <div v-else class="table-container">
          <table>
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
                <td>
                  <span class="asset-type" :class="asset.assetType.toLowerCase()">
                    {{ getAssetTypeLabel(asset) }}
                  </span>
                </td>
                <td>{{ formatDate(asset.purchaseDate) }}</td>
                <td>{{ formatNumber(asset.quantity) }} {{ asset.assetType === 'STOCK' ? '주' : 'g' }}</td>
                <td>{{ formatCurrency(asset.purchasePrice) }}</td>
                <td class="amount">{{ formatCurrency(asset.totalAmount) }}</td>
                <td>{{ asset.memo || '-' }}</td>
                <td>
                  <button @click="deleteAsset(asset.id)" class="btn-delete-small">삭제</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 자산 등록 모달 -->
      <div v-if="showAddModal" class="modal-overlay" @click="closeModal">
        <div class="modal-content" @click.stop>
          <div class="modal-header">
            <h2>자산 등록</h2>
            <button @click="closeModal" class="modal-close">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <form @submit.prevent="addAsset" class="modal-form">
            <div class="form-group">
              <label>종류</label>
              <select v-model="newAsset.assetType" required @change="onAssetTypeChange">
                <option value="GOLD">금 (Gold)</option>
                <option value="SILVER">은 (Silver)</option>
                <option value="STOCK">주식 (Stock)</option>
                <option value="OTHER">기타 (Other)</option>
              </select>
            </div>

            <div v-if="newAsset.assetType === 'OTHER'" class="form-group">
              <label>자산명</label>
              <input type="text" v-model="newAsset.otherName" placeholder="예: 비트코인, 달러, 부동산 등" required/>
            </div>

            <div v-if="newAsset.assetType === 'STOCK'" class="form-group">
              <label>종목 검색</label>
              <input type="text" v-model="stockSearchKeyword" placeholder="종목명 또는 종목코드 입력 (2글자 이상)" @input="searchStocksDebounced"/>
              <p class="input-hint">검색어를 입력하면 관련 종목이 표시됩니다</p>
            </div>

            <div v-if="newAsset.assetType === 'STOCK' && stockSearchResults.length > 0" class="form-group">
              <label>검색 결과 ({{ stockSearchResults.length }}개)</label>
              <div class="stock-results">
                <div v-for="stock in stockSearchResults" :key="stock.stockCode" class="stock-item" :class="{ selected: newAsset.stockCode === stock.stockCode }" @click="selectStock(stock)">
                  <div class="stock-info">
                    <span class="stock-name">{{ stock.stockName }}</span>
                    <span class="stock-code">{{ stock.stockCode }}</span>
                  </div>
                  <span class="stock-price">{{ formatCurrency(stock.currentPrice) }}</span>
                </div>
              </div>
            </div>

            <div v-if="newAsset.assetType === 'STOCK' && newAsset.stockCode" class="form-group">
              <label>선택된 종목</label>
              <div class="selected-stock">
                <div class="stock-info">
                  <strong>{{ newAsset.stockName }}</strong>
                  <span>({{ newAsset.stockCode }})</span>
                </div>
                <button type="button" @click="clearStockSelection" class="btn-clear">취소</button>
              </div>
            </div>

            <div class="form-group">
              <label>구매일</label>
              <input type="date" v-model="newAsset.purchaseDate" required/>
            </div>

            <div class="form-group">
              <label>{{ getQuantityLabel() }}</label>
              <input type="number" step="0.0001" v-model="newAsset.quantity" required/>
            </div>

            <div class="form-group">
              <label>{{ getPriceLabel() }}</label>
              <input type="number" step="0.01" v-model="newAsset.purchasePrice" required/>
            </div>

            <div class="form-group">
              <label>메모 (선택)</label>
              <input type="text" v-model="newAsset.memo" placeholder="예: 장기 투자용"/>
            </div>

            <div v-if="errorMessage" class="alert alert-error">{{ errorMessage }}</div>

            <div class="modal-actions">
              <button type="button" @click="closeModal" class="btn btn-secondary">취소</button>
              <button type="submit" class="btn btn-primary" :disabled="newAsset.assetType === 'STOCK' && !newAsset.stockCode">등록</button>
            </div>
          </form>
        </div>
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

const selectStock = async (stock) => {
  newAsset.value.stockCode = stock.stockCode;
  newAsset.value.stockName = stock.stockName;

  if (stock.currentPrice && stock.currentPrice > 0) {
    newAsset.value.purchasePrice = stock.currentPrice;
  } else {
    try {
      const response = await stockAPI.getStockPrice(stock.stockCode);
      if (response.data.success && response.data.data) {
        newAsset.value.purchasePrice = response.data.data.currentPrice;
      }
    } catch (error) {
      console.error('Failed to fetch stock price:', error);
    }
  }
};

const clearStockSelection = () => {
  newAsset.value.stockCode = '';
  newAsset.value.stockName = '';
  newAsset.value.purchasePrice = '';
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
  if (asset.assetType === 'STOCK') return asset.stockName;
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
/* 요약 그리드 */
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 24px;
  margin-bottom: 30px;
}

.summary-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.summary-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.summary-card .card-header {
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.summary-card.gold .card-header {
  background: linear-gradient(135deg, #f7b733 0%, #fc4a1a 100%);
}

.summary-card.silver .card-header {
  background: linear-gradient(135deg, #c0c0c0 0%, #808080 100%);
}

.summary-card.stock .card-header {
  background: var(--primary-gradient);
}

.summary-card .card-header .card-icon {
  width: 48px;
  height: 48px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.summary-card .card-header h2 {
  margin: 0;
  font-size: 20px;
  color: white;
  font-weight: 600;
}

.summary-card .card-header .stock-code {
  margin-left: auto;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.2);
  padding: 4px 10px;
  border-radius: 20px;
  color: white;
}

.summary-card .card-body {
  padding: 24px;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
}

.stat-row .label {
  color: var(--text-muted);
  font-size: 14px;
}

.stat-row .value {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 15px;
}

.stat-row .value small {
  font-weight: 500;
  margin-left: 4px;
}

.stat-divider {
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--border-color), transparent);
  margin: 8px 0;
}

.stat-row.highlight {
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  margin: 0 -24px;
  padding: 12px 24px;
}

.stat-row.profit {
  border-top: 2px solid var(--border-light);
  margin-top: 8px;
  padding-top: 16px;
}

.stat-row.profit .label {
  font-weight: 600;
  color: var(--text-primary);
}

.stat-row.profit .value {
  font-size: 18px;
}

.stat-row.profit.positive .value {
  color: #e74c3c;
}

.stat-row.profit.negative .value {
  color: #3498db;
}

/* 액션 바 */
.action-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 24px;
}

/* 히스토리 섹션 */
.history-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 28px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.section-header {
  margin-bottom: 24px;
}

.section-header h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
  font-weight: 600;
}

/* 테이블 */
.table-container {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
}

thead {
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
}

th {
  padding: 16px;
  text-align: left;
  font-weight: 600;
  color: var(--text-muted);
  font-size: 13px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 2px solid var(--border-color);
}

td {
  padding: 16px;
  border-bottom: 1px solid var(--border-light);
  color: var(--text-primary);
  font-size: 14px;
}

tr:hover {
  background: rgba(102, 126, 234, 0.03);
}

.asset-type {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
}

.asset-type.gold {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #b8860b;
}

.asset-type.silver {
  background: linear-gradient(135deg, rgba(192, 192, 192, 0.2) 0%, rgba(128, 128, 128, 0.2) 100%);
  color: #708090;
}

.asset-type.stock {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.asset-type.other {
  background: linear-gradient(135deg, rgba(108, 117, 125, 0.15) 0%, rgba(73, 80, 87, 0.15) 100%);
  color: #6c757d;
}

td.amount {
  font-weight: 600;
  color: var(--primary-start);
}

.btn-delete-small {
  padding: 6px 14px;
  background: linear-gradient(135deg, #fee 0%, #fdd 100%);
  color: var(--danger);
  border: 1px solid #fcc;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-delete-small:hover {
  background: var(--danger);
  color: white;
  border-color: var(--danger);
}

/* 모달 폼 */
.modal-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.input-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
}

.stock-results {
  max-height: 240px;
  overflow-y: auto;
  border: 2px solid var(--border-color);
  border-radius: 12px;
  background: white;
}

.stock-item {
  padding: 14px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  border-bottom: 1px solid var(--border-light);
  transition: background-color 0.2s;
}

.stock-item:last-child {
  border-bottom: none;
}

.stock-item:hover {
  background: #f8f9fa;
}

.stock-item.selected {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
  border-left: 3px solid var(--primary-start);
}

.stock-item .stock-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stock-item .stock-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
}

.stock-item .stock-code {
  color: var(--text-muted);
  font-size: 12px;
}

.stock-item .stock-price {
  color: var(--primary-start);
  font-weight: 600;
  font-size: 14px;
}

.selected-stock {
  padding: 16px;
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
  border: 2px solid var(--primary-start);
  border-radius: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.selected-stock .stock-info {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--primary-start);
}

.selected-stock .stock-info strong {
  font-size: 16px;
}

.selected-stock .stock-info span {
  font-size: 13px;
  color: var(--text-muted);
}

.btn-clear {
  background: var(--danger);
  color: white;
  border: none;
  border-radius: 8px;
  padding: 8px 14px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-clear:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

/* 반응형 */
@media (max-width: 768px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .history-section {
    padding: 20px;
  }

  th, td {
    padding: 12px 8px;
    font-size: 13px;
  }
}
</style>
