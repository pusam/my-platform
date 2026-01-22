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
        <span>코스피/코스닥 시장의 외국인/기관/개인 투자자별 순매수 동향을 확인하세요. 데이터는 5분마다 자동 갱신됩니다.</span>
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

      <!-- 메인 컨텐츠 -->
      <div v-else-if="currentData" class="investor-content">
        <!-- 지수 정보 -->
        <div class="index-card">
          <div class="index-info">
            <h2>{{ currentData.marketName }}</h2>
            <div class="index-value">{{ formatNumber(currentData.indexValue, 2) }}</div>
          </div>
          <div class="index-change" :class="getChangeClass(currentData.indexChange)">
            <span class="change-value">{{ currentData.indexChange >= 0 ? '+' : '' }}{{ formatNumber(currentData.indexChange, 2) }}</span>
            <span class="change-rate">({{ currentData.indexChangeRate >= 0 ? '+' : '' }}{{ formatNumber(currentData.indexChangeRate, 2) }}%)</span>
          </div>
        </div>

        <!-- 투자자별 순매수 현황 카드 -->
        <div class="investor-cards">
          <!-- 외국인 -->
          <div class="investor-card foreign" :class="getNetBuyClass(currentData.foreignNetBuy)">
            <div class="card-header">
              <div class="investor-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="2" y1="12" x2="22" y2="12"/>
                  <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
                </svg>
              </div>
              <h3>외국인</h3>
            </div>
            <div class="net-buy-value" :class="getNetBuyClass(currentData.foreignNetBuy)">
              {{ formatNetBuy(currentData.foreignNetBuy) }}
            </div>
            <div class="buy-sell-detail">
              <div class="buy">
                <span class="label">매수</span>
                <span class="value">{{ formatAmount(currentData.foreignBuy) }}</span>
              </div>
              <div class="sell">
                <span class="label">매도</span>
                <span class="value">{{ formatAmount(currentData.foreignSell) }}</span>
              </div>
            </div>
            <div class="ratio-bar">
              <div class="ratio-fill" :style="{ width: currentData.foreignRatio + '%' }"></div>
              <span class="ratio-text">{{ formatNumber(currentData.foreignRatio, 1) }}%</span>
            </div>
          </div>

          <!-- 기관 -->
          <div class="investor-card institution" :class="getNetBuyClass(currentData.institutionNetBuy)">
            <div class="card-header">
              <div class="investor-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M3 21h18"/>
                  <path d="M5 21V7l8-4 8 4v14"/>
                  <path d="M9 9h1"/>
                  <path d="M14 9h1"/>
                  <path d="M9 13h1"/>
                  <path d="M14 13h1"/>
                  <path d="M9 17h1"/>
                  <path d="M14 17h1"/>
                </svg>
              </div>
              <h3>기관</h3>
            </div>
            <div class="net-buy-value" :class="getNetBuyClass(currentData.institutionNetBuy)">
              {{ formatNetBuy(currentData.institutionNetBuy) }}
            </div>
            <div class="buy-sell-detail">
              <div class="buy">
                <span class="label">매수</span>
                <span class="value">{{ formatAmount(currentData.institutionBuy) }}</span>
              </div>
              <div class="sell">
                <span class="label">매도</span>
                <span class="value">{{ formatAmount(currentData.institutionSell) }}</span>
              </div>
            </div>
            <div class="ratio-bar institution-bar">
              <div class="ratio-fill" :style="{ width: currentData.institutionRatio + '%' }"></div>
              <span class="ratio-text">{{ formatNumber(currentData.institutionRatio, 1) }}%</span>
            </div>
          </div>

          <!-- 개인 -->
          <div class="investor-card individual" :class="getNetBuyClass(currentData.individualNetBuy)">
            <div class="card-header">
              <div class="investor-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                  <circle cx="12" cy="7" r="4"/>
                </svg>
              </div>
              <h3>개인</h3>
            </div>
            <div class="net-buy-value" :class="getNetBuyClass(currentData.individualNetBuy)">
              {{ formatNetBuy(currentData.individualNetBuy) }}
            </div>
            <div class="buy-sell-detail">
              <div class="buy">
                <span class="label">매수</span>
                <span class="value">{{ formatAmount(currentData.individualBuy) }}</span>
              </div>
              <div class="sell">
                <span class="label">매도</span>
                <span class="value">{{ formatAmount(currentData.individualSell) }}</span>
              </div>
            </div>
            <div class="ratio-bar individual-bar">
              <div class="ratio-fill" :style="{ width: currentData.individualRatio + '%' }"></div>
              <span class="ratio-text">{{ formatNumber(currentData.individualRatio, 1) }}%</span>
            </div>
          </div>
        </div>

        <!-- 기타 투자자 (연기금, 투자신탁) -->
        <div class="sub-investors" v-if="currentData.pensionNetBuy || currentData.investTrustNetBuy">
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

        <!-- 일별 추이 차트 -->
        <div class="trends-section" v-if="currentData.dailyTrends && currentData.dailyTrends.length > 0">
          <h3>최근 투자자별 순매수 추이</h3>
          <div class="trends-chart">
            <div class="chart-legend">
              <span class="legend-item foreign"><span class="dot"></span>외국인</span>
              <span class="legend-item institution"><span class="dot"></span>기관</span>
              <span class="legend-item individual"><span class="dot"></span>개인</span>
            </div>
            <div class="chart-content">
              <div
                v-for="(trend, index) in displayTrends"
                :key="index"
                class="chart-bar-group"
              >
                <div class="bar-container">
                  <div
                    class="bar foreign"
                    :class="{ negative: trend.foreignNetBuy < 0 }"
                    :style="{ height: getBarHeight(trend.foreignNetBuy) + 'px' }"
                  ></div>
                  <div
                    class="bar institution"
                    :class="{ negative: trend.institutionNetBuy < 0 }"
                    :style="{ height: getBarHeight(trend.institutionNetBuy) + 'px' }"
                  ></div>
                  <div
                    class="bar individual"
                    :class="{ negative: trend.individualNetBuy < 0 }"
                    :style="{ height: getBarHeight(trend.individualNetBuy) + 'px' }"
                  ></div>
                </div>
                <span class="date-label">{{ formatDate(trend.date) }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 일별 상세 테이블 -->
        <div class="trends-table" v-if="currentData.dailyTrends && currentData.dailyTrends.length > 0">
          <h3>일별 상세 데이터</h3>
          <table>
            <thead>
              <tr>
                <th>일자</th>
                <th>외국인</th>
                <th>기관</th>
                <th>개인</th>
                <th>지수</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="trend in displayTrends" :key="trend.date">
                <td>{{ formatDateFull(trend.date) }}</td>
                <td :class="getNetBuyClass(trend.foreignNetBuy)">{{ formatNetBuy(trend.foreignNetBuy) }}</td>
                <td :class="getNetBuyClass(trend.institutionNetBuy)">{{ formatNetBuy(trend.institutionNetBuy) }}</td>
                <td :class="getNetBuyClass(trend.individualNetBuy)">{{ formatNetBuy(trend.individualNetBuy) }}</td>
                <td>
                  <span v-if="trend.indexValue">{{ formatNumber(trend.indexValue, 2) }}</span>
                  <span v-else>-</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 요약 바 -->
      <div v-if="currentData" class="summary-bar">
        <div class="summary-item">
          <span class="summary-label">조회 시장</span>
          <span class="summary-value">{{ currentData.marketName }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">일별 기록</span>
          <span class="summary-value">{{ currentData.dailyTrends?.length || 0 }}일</span>
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
const lastUpdate = ref('-');
let refreshInterval = null;

// 시장 선택 탭
const marketTabs = [
  { value: 'KOSPI', label: '코스피', icon: '📈' },
  { value: 'KOSDAQ', label: '코스닥', icon: '📊' }
];

const currentData = computed(() => {
  return selectedMarket.value === 'KOSPI' ? marketData.value.kospi : marketData.value.kosdaq;
});

const displayTrends = computed(() => {
  if (!currentData.value?.dailyTrends) return [];
  return currentData.value.dailyTrends.slice(0, 10).reverse();
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
  return num.toFixed(0) + '억';
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

const getNetBuyClass = (value) => {
  if (value === null || value === undefined) return '';
  return Number(value) >= 0 ? 'positive' : 'negative';
};

const getChangeClass = (value) => {
  if (value === null || value === undefined) return '';
  return Number(value) >= 0 ? 'up' : 'down';
};

const formatDate = (date) => {
  if (!date) return '';
  const d = new Date(date);
  return `${d.getMonth() + 1}/${d.getDate()}`;
};

const formatDateFull = (date) => {
  if (!date) return '';
  const d = new Date(date);
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`;
};

const getBarHeight = (value) => {
  if (value === null || value === undefined) return 0;
  const maxValue = getMaxTrendValue();
  if (maxValue === 0) return 0;
  return Math.abs(Number(value)) / maxValue * 60;
};

const getMaxTrendValue = () => {
  if (!currentData.value?.dailyTrends) return 1000;
  let max = 0;
  for (const trend of currentData.value.dailyTrends) {
    max = Math.max(max, Math.abs(trend.foreignNetBuy || 0));
    max = Math.max(max, Math.abs(trend.institutionNetBuy || 0));
    max = Math.max(max, Math.abs(trend.individualNetBuy || 0));
  }
  return max || 1000;
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
  // 5분마다 자동 갱신
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

.tab-icon {
  font-size: 18px;
}

/* 지수 카드 */
.index-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px 32px;
  background: linear-gradient(135deg, #1f2937 0%, #374151 100%);
  border-radius: 16px;
  margin-bottom: 24px;
  color: white;
}

.index-info h2 {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 500;
  opacity: 0.8;
}

.index-value {
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -1px;
}

.index-change {
  text-align: right;
}

.index-change.up .change-value,
.index-change.up .change-rate {
  color: #EF4444;
}

.index-change.down .change-value,
.index-change.down .change-rate {
  color: #3B82F6;
}

.change-value {
  display: block;
  font-size: 24px;
  font-weight: 700;
}

.change-rate {
  font-size: 16px;
  opacity: 0.9;
}

/* 투자자 카드 */
.investor-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 24px;
}

.investor-card {
  background: white;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  border: 2px solid transparent;
  transition: all 0.3s ease;
}

.investor-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
}

.investor-card.positive {
  border-color: rgba(239, 68, 68, 0.3);
}

.investor-card.negative {
  border-color: rgba(59, 130, 246, 0.3);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.investor-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.investor-card.foreign .investor-icon {
  background: linear-gradient(135deg, #EF4444 0%, #F97316 100%);
  color: white;
}

.investor-card.institution .investor-icon {
  background: linear-gradient(135deg, #3B82F6 0%, #6366F1 100%);
  color: white;
}

.investor-card.individual .investor-icon {
  background: linear-gradient(135deg, #10B981 0%, #34D399 100%);
  color: white;
}

.card-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
}

.net-buy-value {
  font-size: 32px;
  font-weight: 800;
  margin-bottom: 16px;
  letter-spacing: -0.5px;
}

.net-buy-value.positive {
  color: #EF4444;
}

.net-buy-value.negative {
  color: #3B82F6;
}

.buy-sell-detail {
  display: flex;
  justify-content: space-between;
  padding: 12px 0;
  border-top: 1px solid #f3f4f6;
  border-bottom: 1px solid #f3f4f6;
  margin-bottom: 16px;
}

.buy-sell-detail .buy,
.buy-sell-detail .sell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.buy-sell-detail .label {
  font-size: 12px;
  color: #6b7280;
}

.buy-sell-detail .buy .value {
  color: #EF4444;
  font-weight: 600;
}

.buy-sell-detail .sell .value {
  color: #3B82F6;
  font-weight: 600;
}

.ratio-bar {
  height: 24px;
  background: #f3f4f6;
  border-radius: 12px;
  position: relative;
  overflow: hidden;
}

.ratio-fill {
  height: 100%;
  border-radius: 12px;
  transition: width 0.5s ease;
}

.investor-card.foreign .ratio-fill {
  background: linear-gradient(90deg, #EF4444 0%, #F97316 100%);
}

.investor-card.institution .ratio-fill,
.ratio-bar.institution-bar .ratio-fill {
  background: linear-gradient(90deg, #3B82F6 0%, #6366F1 100%);
}

.investor-card.individual .ratio-fill,
.ratio-bar.individual-bar .ratio-fill {
  background: linear-gradient(90deg, #10B981 0%, #34D399 100%);
}

.ratio-text {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 12px;
  font-weight: 600;
  color: #374151;
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

/* 일별 추이 차트 */
.trends-section {
  background: white;
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.trends-section h3 {
  margin: 0 0 20px 0;
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.chart-legend {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
  justify-content: center;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #4b5563;
}

.legend-item .dot {
  width: 12px;
  height: 12px;
  border-radius: 4px;
}

.legend-item.foreign .dot {
  background: #EF4444;
}

.legend-item.institution .dot {
  background: #3B82F6;
}

.legend-item.individual .dot {
  background: #10B981;
}

.chart-content {
  display: flex;
  justify-content: space-around;
  align-items: flex-end;
  height: 120px;
  padding-top: 40px;
  border-bottom: 1px solid #e5e7eb;
}

.chart-bar-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.bar-container {
  display: flex;
  gap: 4px;
  align-items: flex-end;
  height: 80px;
}

.bar {
  width: 12px;
  border-radius: 4px 4px 0 0;
  transition: height 0.3s ease;
}

.bar.foreign {
  background: #EF4444;
}

.bar.institution {
  background: #3B82F6;
}

.bar.individual {
  background: #10B981;
}

.bar.negative {
  opacity: 0.5;
}

.date-label {
  font-size: 11px;
  color: #9ca3af;
}

/* 일별 테이블 */
.trends-table {
  background: white;
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.trends-table h3 {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.trends-table table {
  width: 100%;
  border-collapse: collapse;
}

.trends-table th,
.trends-table td {
  padding: 12px 16px;
  text-align: right;
  border-bottom: 1px solid #f3f4f6;
}

.trends-table th {
  font-size: 13px;
  font-weight: 600;
  color: #6b7280;
  background: #f9fafb;
}

.trends-table th:first-child,
.trends-table td:first-child {
  text-align: left;
}

.trends-table td {
  font-size: 14px;
  color: #1f2937;
}

.trends-table td.positive {
  color: #EF4444;
  font-weight: 600;
}

.trends-table td.negative {
  color: #3B82F6;
  font-weight: 600;
}

/* 요약 바 */
.summary-bar {
  display: flex;
  justify-content: center;
  gap: 48px;
  margin-top: 32px;
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
  .investor-cards {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .index-card {
    flex-direction: column;
    text-align: center;
    gap: 16px;
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

  .chart-content {
    overflow-x: auto;
    justify-content: flex-start;
    gap: 16px;
    padding: 40px 12px 12px;
  }

  .trends-table {
    overflow-x: auto;
  }
}

/* 다크 모드 */
[data-theme="dark"] .investor-card {
  background: rgba(31, 31, 35, 0.95);
}

[data-theme="dark"] .card-header h3 {
  color: #f9fafb;
}

[data-theme="dark"] .trends-section,
[data-theme="dark"] .trends-table {
  background: rgba(31, 31, 35, 0.95);
}

[data-theme="dark"] .trends-section h3,
[data-theme="dark"] .trends-table h3 {
  color: #f9fafb;
}

[data-theme="dark"] .trends-table td {
  color: #f9fafb;
}

[data-theme="dark"] .sub-investors {
  background: rgba(31, 31, 35, 0.6);
}
</style>
