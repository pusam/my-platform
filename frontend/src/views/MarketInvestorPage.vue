<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>시장 투자자 매매동향</h1>
        <div class="header-actions">
          <button @click="refreshData" class="btn btn-refresh" :disabled="loading">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :class="{ spinning: loading }">
              <path d="M21 12a9 9 0 11-9-9"/>
              <polyline points="21 3 21 9 15 9"/>
            </svg>
            새로고침
          </button>
          <button @click="goBack" class="btn btn-back">돌아가기</button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 설명 -->
      <div class="info-banner">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="16" x2="12" y2="12"/>
          <line x1="12" y1="8" x2="12.01" y2="8"/>
        </svg>
        <span>코스피/코스닥 시장의 외국인, 기관, 개인 투자자별 매수/매도 금액과 상위 종목을 확인하세요.</span>
      </div>

      <!-- 시장 선택 탭 -->
      <div class="market-tabs">
        <button
          v-for="tab in marketTabs"
          :key="tab.value"
          :class="['market-tab', { active: selectedMarket === tab.value }]"
          @click="selectMarket(tab.value)"
        >
          <span class="tab-icon">{{ tab.icon }}</span>
          <span class="tab-label">{{ tab.label }}</span>
        </button>
      </div>

      <!-- 로딩 상태 -->
      <LoadingSpinner v-if="loading && !currentData" message="투자자 매매동향을 불러오는 중..." />

      <!-- 지수 정보 -->
      <div v-if="currentData" class="index-summary">
        <div class="index-info">
          <h2>{{ currentData.marketName }}</h2>
          <span class="index-value">{{ formatNumber(currentData.indexValue, 2) }}</span>
        </div>
        <div class="index-change" :class="getChangeClass(currentData.indexChange)">
          <span>{{ currentData.indexChange >= 0 ? '+' : '' }}{{ formatNumber(currentData.indexChange, 2) }}</span>
          <span>({{ currentData.indexChangeRate >= 0 ? '+' : '' }}{{ formatNumber(currentData.indexChangeRate, 2) }}%)</span>
        </div>
      </div>

      <!-- 투자자 카드 그리드 -->
      <div v-if="currentData" class="investor-grid">
        <!-- 외국인 카드 -->
        <div
          class="investor-card"
          :class="{ expanded: expandedInvestor === 'foreign' }"
          @click="toggleInvestor('foreign')"
        >
          <div class="card-header">
            <div class="investor-info">
              <div class="investor-icon foreign">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="2" y1="12" x2="22" y2="12"/>
                  <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
                </svg>
              </div>
              <div class="investor-title">
                <h3>외국인</h3>
                <span class="net-buy-badge" :class="getNetBuyClass(currentData.foreignNetBuy)">
                  순매수 {{ formatNetBuy(currentData.foreignNetBuy) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 매수/매도 금액 박스 -->
          <div class="trading-box">
            <div class="trading-row buy">
              <span class="trading-label">매수</span>
              <span class="trading-value buy-value">{{ formatAmount(currentData.foreignBuy) }}</span>
            </div>
            <div class="trading-row sell">
              <span class="trading-label">매도</span>
              <span class="trading-value sell-value">{{ formatAmount(currentData.foreignSell) }}</span>
            </div>
            <div class="trading-row net">
              <span class="trading-label">순매수</span>
              <span class="trading-value" :class="getNetBuyClass(currentData.foreignNetBuy)">
                {{ formatNetBuy(currentData.foreignNetBuy) }}
              </span>
            </div>
          </div>

          <!-- 확장 시 상위 종목 표시 -->
          <div v-if="expandedInvestor === 'foreign'" class="investor-detail">
            <div class="top-stocks-row">
              <div class="top-stocks-column">
                <h4 class="buy-title">상위 매수 종목</h4>
                <div v-if="currentData.foreignTopBuy?.length > 0" class="stock-list">
                  <div v-for="stock in currentData.foreignTopBuy" :key="stock.stockCode" class="stock-row">
                    <div class="stock-info">
                      <span class="stock-name">{{ stock.stockName }}</span>
                      <span class="stock-change" :class="getNetBuyClass(stock.changeRate)">
                        {{ formatChangeRate(stock.changeRate) }}
                      </span>
                    </div>
                    <span class="stock-amount buy-amount">+{{ formatStockAmount(stock.netBuyAmount) }}</span>
                  </div>
                </div>
                <div v-else class="no-data">데이터 없음</div>
              </div>
              <div class="top-stocks-column">
                <h4 class="sell-title">상위 매도 종목</h4>
                <div v-if="currentData.foreignTopSell?.length > 0" class="stock-list">
                  <div v-for="stock in currentData.foreignTopSell" :key="stock.stockCode" class="stock-row">
                    <div class="stock-info">
                      <span class="stock-name">{{ stock.stockName }}</span>
                      <span class="stock-change" :class="getNetBuyClass(stock.changeRate)">
                        {{ formatChangeRate(stock.changeRate) }}
                      </span>
                    </div>
                    <span class="stock-amount sell-amount">-{{ formatStockAmount(Math.abs(stock.netBuyAmount)) }}</span>
                  </div>
                </div>
                <div v-else class="no-data">데이터 없음</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 기관 카드 -->
        <div
          class="investor-card"
          :class="{ expanded: expandedInvestor === 'institution' }"
          @click="toggleInvestor('institution')"
        >
          <div class="card-header">
            <div class="investor-info">
              <div class="investor-icon institution">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M3 21h18"/>
                  <path d="M5 21V7l8-4 8 4v14"/>
                  <path d="M9 9h1M14 9h1M9 13h1M14 13h1M9 17h1M14 17h1"/>
                </svg>
              </div>
              <div class="investor-title">
                <h3>기관</h3>
                <span class="net-buy-badge" :class="getNetBuyClass(currentData.institutionNetBuy)">
                  순매수 {{ formatNetBuy(currentData.institutionNetBuy) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 매수/매도 금액 박스 -->
          <div class="trading-box">
            <div class="trading-row buy">
              <span class="trading-label">매수</span>
              <span class="trading-value buy-value">{{ formatAmount(currentData.institutionBuy) }}</span>
            </div>
            <div class="trading-row sell">
              <span class="trading-label">매도</span>
              <span class="trading-value sell-value">{{ formatAmount(currentData.institutionSell) }}</span>
            </div>
            <div class="trading-row net">
              <span class="trading-label">순매수</span>
              <span class="trading-value" :class="getNetBuyClass(currentData.institutionNetBuy)">
                {{ formatNetBuy(currentData.institutionNetBuy) }}
              </span>
            </div>
          </div>

          <!-- 확장 시 상위 종목 표시 -->
          <div v-if="expandedInvestor === 'institution'" class="investor-detail">
            <div class="top-stocks-row">
              <div class="top-stocks-column">
                <h4 class="buy-title">상위 매수 종목</h4>
                <div v-if="currentData.institutionTopBuy?.length > 0" class="stock-list">
                  <div v-for="stock in currentData.institutionTopBuy" :key="stock.stockCode" class="stock-row">
                    <div class="stock-info">
                      <span class="stock-name">{{ stock.stockName }}</span>
                      <span class="stock-change" :class="getNetBuyClass(stock.changeRate)">
                        {{ formatChangeRate(stock.changeRate) }}
                      </span>
                    </div>
                    <span class="stock-amount buy-amount">+{{ formatStockAmount(stock.netBuyAmount) }}</span>
                  </div>
                </div>
                <div v-else class="no-data">데이터 없음</div>
              </div>
              <div class="top-stocks-column">
                <h4 class="sell-title">상위 매도 종목</h4>
                <div v-if="currentData.institutionTopSell?.length > 0" class="stock-list">
                  <div v-for="stock in currentData.institutionTopSell" :key="stock.stockCode" class="stock-row">
                    <div class="stock-info">
                      <span class="stock-name">{{ stock.stockName }}</span>
                      <span class="stock-change" :class="getNetBuyClass(stock.changeRate)">
                        {{ formatChangeRate(stock.changeRate) }}
                      </span>
                    </div>
                    <span class="stock-amount sell-amount">-{{ formatStockAmount(Math.abs(stock.netBuyAmount)) }}</span>
                  </div>
                </div>
                <div v-else class="no-data">데이터 없음</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 개인 카드 -->
        <div
          class="investor-card"
          :class="{ expanded: expandedInvestor === 'individual' }"
          @click="toggleInvestor('individual')"
        >
          <div class="card-header">
            <div class="investor-info">
              <div class="investor-icon individual">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                  <circle cx="12" cy="7" r="4"/>
                </svg>
              </div>
              <div class="investor-title">
                <h3>개인</h3>
                <span class="net-buy-badge" :class="getNetBuyClass(currentData.individualNetBuy)">
                  순매수 {{ formatNetBuy(currentData.individualNetBuy) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 매수/매도 금액 박스 -->
          <div class="trading-box">
            <div class="trading-row buy">
              <span class="trading-label">매수</span>
              <span class="trading-value buy-value">{{ formatAmount(currentData.individualBuy) }}</span>
            </div>
            <div class="trading-row sell">
              <span class="trading-label">매도</span>
              <span class="trading-value sell-value">{{ formatAmount(currentData.individualSell) }}</span>
            </div>
            <div class="trading-row net">
              <span class="trading-label">순매수</span>
              <span class="trading-value" :class="getNetBuyClass(currentData.individualNetBuy)">
                {{ formatNetBuy(currentData.individualNetBuy) }}
              </span>
            </div>
          </div>

          <!-- 개인은 상위 종목 데이터 없음 안내 -->
          <div v-if="expandedInvestor === 'individual'" class="investor-detail">
            <div class="no-data-message">개인 투자자 상위 종목 데이터는 제공되지 않습니다.</div>
          </div>
        </div>
      </div>

      <!-- 기타 투자자 요약 -->
      <div v-if="currentData && (currentData.pensionNetBuy || currentData.investTrustNetBuy)" class="sub-investors">
        <div class="sub-investor" v-if="currentData.pensionNetBuy">
          <span class="label">연기금</span>
          <span class="value" :class="getNetBuyClass(currentData.pensionNetBuy)">
            {{ formatNetBuy(currentData.pensionNetBuy) }}
          </span>
        </div>
        <div class="sub-investor" v-if="currentData.investTrustNetBuy">
          <span class="label">투자신탁</span>
          <span class="value" :class="getNetBuyClass(currentData.investTrustNetBuy)">
            {{ formatNetBuy(currentData.investTrustNetBuy) }}
          </span>
        </div>
      </div>

      <!-- 요약 바 -->
      <div v-if="currentData" class="summary-bar">
        <div class="summary-item">
          <span class="summary-label">조회 시장</span>
          <span class="summary-value">{{ currentData.marketName }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">마지막 업데이트</span>
          <span class="summary-value">{{ lastUpdate }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { marketInvestorAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const marketData = ref({ kospi: null, kosdaq: null });
const loading = ref(false);
const selectedMarket = ref('KOSPI');
const expandedInvestor = ref(null);
const lastUpdate = ref('-');
let refreshInterval = null;

const marketTabs = [
  { value: 'KOSPI', label: '코스피', icon: '📈' },
  { value: 'KOSDAQ', label: '코스닥', icon: '📊' }
];

const currentData = computed(() => {
  return selectedMarket.value === 'KOSPI' ? marketData.value.kospi : marketData.value.kosdaq;
});

const loadData = async () => {
  try {
    loading.value = true;
    const response = await marketInvestorAPI.getAll();
    if (response.data.success) {
      marketData.value = response.data.data || {};
      lastUpdate.value = new Date().toLocaleTimeString('ko-KR');
    }
  } catch (error) {
    console.error('투자자 동향 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const selectMarket = (market) => {
  selectedMarket.value = market;
  expandedInvestor.value = null;
};

const toggleInvestor = (investor) => {
  expandedInvestor.value = expandedInvestor.value === investor ? null : investor;
};

const refreshData = async () => {
  try {
    loading.value = true;
    await marketInvestorAPI.clearCache();
    await loadData();
  } catch (error) {
    console.error('새로고침 실패:', error);
  }
};

const formatNumber = (value, decimals = 0) => {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
};

const formatAmount = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  if (Math.abs(num) >= 10000) {
    return (num / 10000).toFixed(1) + '조';
  }
  return Math.abs(num).toFixed(0) + '억';
};

const formatNetBuy = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  const sign = num >= 0 ? '+' : '';
  if (Math.abs(num) >= 10000) {
    return sign + (num / 10000).toFixed(2) + '조';
  }
  return sign + num.toFixed(0) + '억';
};

const formatStockAmount = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Math.abs(Number(value));
  if (num >= 1000) {
    return (num / 1000).toFixed(1) + '천억';
  }
  return num.toFixed(0) + '억';
};

const formatChangeRate = (value) => {
  if (value === null || value === undefined) return '-';
  const num = Number(value);
  const sign = num >= 0 ? '+' : '';
  return sign + num.toFixed(2) + '%';
};

const getNetBuyClass = (value) => {
  if (value === null || value === undefined) return '';
  return Number(value) >= 0 ? 'positive' : 'negative';
};

const getChangeClass = (value) => {
  if (value === null || value === undefined) return '';
  return Number(value) >= 0 ? 'up' : 'down';
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
  refreshInterval = setInterval(loadData, 5 * 60 * 1000);
});

onUnmounted(() => {
  if (refreshInterval) {
    clearInterval(refreshInterval);
  }
});
</script>

<style scoped>
/* 정보 배너 */
.info-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(147, 51, 234, 0.1) 100%);
  border: 1px solid rgba(59, 130, 246, 0.2);
  border-radius: 12px;
  margin-bottom: 24px;
  color: #4b5563;
  font-size: 14px;
}

.info-banner svg {
  color: #3B82F6;
  flex-shrink: 0;
}

/* 시장 선택 탭 */
.market-tabs {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  padding: 8px;
  background: #f3f4f6;
  border-radius: 16px;
  justify-content: center;
}

.market-tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 32px;
  border: none;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  transition: all 0.3s ease;
  font-size: 15px;
  font-weight: 600;
  color: #4b5563;
}

.market-tab:hover {
  background: rgba(59, 130, 246, 0.1);
  color: #1f2937;
}

.market-tab.active {
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  box-shadow: 0 4px 15px rgba(59, 130, 246, 0.3);
}

/* 지수 요약 */
.index-summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 28px;
  background: linear-gradient(135deg, #1f2937 0%, #374151 100%);
  border-radius: 16px;
  margin-bottom: 24px;
  color: white;
}

.index-info {
  display: flex;
  align-items: center;
  gap: 16px;
}

.index-info h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 500;
  opacity: 0.9;
}

.index-value {
  font-size: 28px;
  font-weight: 700;
}

.index-change {
  text-align: right;
  font-size: 18px;
  font-weight: 600;
}

.index-change.up {
  color: #EF4444;
}

.index-change.down {
  color: #3B82F6;
}

/* 투자자 카드 그리드 */
.investor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 20px;
  margin-bottom: 24px;
}

.investor-card {
  background: white;
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
}

.investor-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
}

.investor-card.expanded {
  border-color: #3B82F6;
}

.card-header {
  margin-bottom: 20px;
}

.investor-info {
  display: flex;
  align-items: center;
  gap: 14px;
}

.investor-icon {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.investor-icon.foreign {
  background: linear-gradient(135deg, #EF4444 0%, #F97316 100%);
  color: white;
}

.investor-icon.institution {
  background: linear-gradient(135deg, #3B82F6 0%, #6366F1 100%);
  color: white;
}

.investor-icon.individual {
  background: linear-gradient(135deg, #10B981 0%, #34D399 100%);
  color: white;
}

.investor-title h3 {
  margin: 0 0 6px 0;
  font-size: 20px;
  font-weight: 700;
  color: #1f2937;
}

.net-buy-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
}

.net-buy-badge.positive {
  background: rgba(239, 68, 68, 0.1);
  color: #EF4444;
}

.net-buy-badge.negative {
  background: rgba(59, 130, 246, 0.1);
  color: #3B82F6;
}

/* 매수/매도 박스 */
.trading-box {
  background: #f9fafb;
  border-radius: 14px;
  padding: 16px 20px;
}

.trading-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
}

.trading-row:not(:last-child) {
  border-bottom: 1px solid #e5e7eb;
}

.trading-label {
  font-size: 14px;
  color: #6b7280;
  font-weight: 500;
}

.trading-value {
  font-size: 18px;
  font-weight: 700;
}

.buy-value {
  color: #EF4444;
}

.sell-value {
  color: #3B82F6;
}

.trading-value.positive {
  color: #EF4444;
}

.trading-value.negative {
  color: #3B82F6;
}

/* 상세 확장 영역 */
.investor-detail {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #e5e7eb;
}

.top-stocks-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.top-stocks-column h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 8px;
  display: inline-block;
}

.buy-title {
  background: rgba(239, 68, 68, 0.1);
  color: #EF4444;
}

.sell-title {
  background: rgba(59, 130, 246, 0.1);
  color: #3B82F6;
}

.stock-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stock-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: #f9fafb;
  border-radius: 10px;
}

.stock-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stock-name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
}

.stock-change {
  font-size: 11px;
}

.stock-change.positive {
  color: #EF4444;
}

.stock-change.negative {
  color: #3B82F6;
}

.stock-amount {
  font-size: 14px;
  font-weight: 700;
}

.buy-amount {
  color: #EF4444;
}

.sell-amount {
  color: #3B82F6;
}

.no-data {
  padding: 20px;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  background: #f9fafb;
  border-radius: 10px;
}

.no-data-message {
  padding: 24px;
  text-align: center;
  color: #6b7280;
  font-size: 14px;
  background: #f9fafb;
  border-radius: 12px;
}

/* 기타 투자자 */
.sub-investors {
  display: flex;
  gap: 24px;
  padding: 20px 24px;
  background: #f9fafb;
  border-radius: 12px;
  margin-bottom: 24px;
}

.sub-investor {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sub-investor .label {
  font-size: 14px;
  color: #6b7280;
  font-weight: 500;
}

.sub-investor .value {
  font-size: 18px;
  font-weight: 700;
}

.sub-investor .value.positive {
  color: #EF4444;
}

.sub-investor .value.negative {
  color: #3B82F6;
}

/* 요약 바 */
.summary-bar {
  display: flex;
  justify-content: center;
  gap: 48px;
  padding: 24px;
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
  border-radius: 16px;
}

.summary-item {
  text-align: center;
}

.summary-label {
  display: block;
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 8px;
}

.summary-value {
  font-size: 20px;
  font-weight: 700;
  color: #1f2937;
}

/* 버튼 */
.btn-refresh {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-refresh:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
}

.btn-refresh:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-refresh svg.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 반응형 */
@media (max-width: 1024px) {
  .investor-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .index-summary {
    flex-direction: column;
    text-align: center;
    gap: 12px;
  }

  .index-info {
    flex-direction: column;
    gap: 8px;
  }

  .index-change {
    text-align: center;
  }

  .summary-bar {
    flex-direction: column;
    gap: 16px;
  }

  .sub-investors {
    flex-direction: column;
    gap: 12px;
  }

  .top-stocks-row {
    grid-template-columns: 1fr;
  }
}

/* 다크 모드 */
[data-theme="dark"] .investor-card {
  background: rgba(31, 31, 35, 0.95);
}

[data-theme="dark"] .investor-title h3 {
  color: #f9fafb;
}

[data-theme="dark"] .trading-box {
  background: rgba(255, 255, 255, 0.05);
}

[data-theme="dark"] .trading-row:not(:last-child) {
  border-bottom-color: rgba(255, 255, 255, 0.1);
}

[data-theme="dark"] .trading-label {
  color: #9ca3af;
}

[data-theme="dark"] .investor-detail {
  border-top-color: rgba(255, 255, 255, 0.1);
}

[data-theme="dark"] .stock-row {
  background: rgba(255, 255, 255, 0.05);
}

[data-theme="dark"] .stock-name {
  color: #f9fafb;
}

[data-theme="dark"] .no-data,
[data-theme="dark"] .no-data-message {
  background: rgba(255, 255, 255, 0.05);
  color: #9ca3af;
}

[data-theme="dark"] .sub-investors {
  background: rgba(31, 31, 35, 0.6);
}

[data-theme="dark"] .summary-value {
  color: #f9fafb;
}
</style>
