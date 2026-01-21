<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>섹터별 거래대금</h1>
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
        <span>오늘의 테마/섹터별 누적 거래대금을 확인하세요. 데이터는 5분마다 자동 갱신됩니다.</span>
      </div>

      <!-- 로딩 상태 -->
      <LoadingSpinner v-if="loading && !sectors.length" message="섹터별 거래대금을 불러오는 중..." />

      <!-- 섹터 카드 그리드 -->
      <div v-else class="sector-grid">
        <div
          v-for="sector in sectors"
          :key="sector.sectorCode"
          class="sector-card"
          :class="{ expanded: expandedSector === sector.sectorCode }"
          @click="toggleSector(sector.sectorCode)"
        >
          <!-- 카드 헤더 -->
          <div class="sector-header">
            <div class="sector-info">
              <div class="sector-icon" :style="{ background: sector.color }">
                <span>{{ sector.sectorName.charAt(0) }}</span>
              </div>
              <div class="sector-title">
                <h3>{{ sector.sectorName }}</h3>
                <span class="stock-count">{{ sector.stockCount }}종목</span>
              </div>
            </div>
            <div class="sector-value">
              <span class="trading-value">{{ formatTradingValue(sector.totalTradingValue) }}</span>
              <span class="percentage" :style="{ color: sector.color }">{{ sector.percentage?.toFixed(1) || 0 }}%</span>
            </div>
          </div>

          <!-- 프로그레스바 -->
          <div class="progress-container">
            <div class="progress-bar" :style="{ width: sector.percentage + '%', background: sector.color }"></div>
          </div>

          <!-- 확장 시 상위 종목 표시 -->
          <div v-if="expandedSector === sector.sectorCode" class="sector-detail">
            <h4>거래대금 상위 종목</h4>
            <div class="top-stocks">
              <div v-for="stock in sector.topStocks" :key="stock.stockCode" class="stock-row">
                <div class="stock-info">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.stockCode }}</span>
                </div>
                <div class="stock-data">
                  <span class="stock-price">{{ formatCurrency(stock.currentPrice) }}</span>
                  <span class="stock-change" :class="getChangeClass(stock.changeRate)">
                    {{ stock.changeRate > 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) || 0 }}%
                  </span>
                  <span class="stock-trading">{{ formatTradingValue(stock.tradingValue) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 총 거래대금 요약 -->
      <div v-if="sectors.length" class="summary-bar">
        <div class="summary-item">
          <span class="summary-label">총 거래대금</span>
          <span class="summary-value">{{ formatTradingValue(totalTradingValue) }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">조회 섹터</span>
          <span class="summary-value">{{ sectors.length }}개</span>
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
import { sectorAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const sectors = ref([]);
const loading = ref(false);
const expandedSector = ref(null);
const lastUpdate = ref('-');
let refreshInterval = null;

const totalTradingValue = computed(() => {
  return sectors.value.reduce((sum, s) => {
    const val = parseFloat(s.totalTradingValue) || 0;
    return sum + val;
  }, 0);
});

const loadData = async () => {
  try {
    loading.value = true;
    const response = await sectorAPI.getSectorTrading();
    if (response.data.success) {
      sectors.value = response.data.data || [];
      lastUpdate.value = new Date().toLocaleTimeString('ko-KR');
    }
  } catch (error) {
    console.error('섹터 데이터 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const refreshData = async () => {
  try {
    loading.value = true;
    await sectorAPI.refreshSectorTrading();
    await loadData();
  } catch (error) {
    console.error('새로고침 실패:', error);
  }
};

const toggleSector = (sectorCode) => {
  expandedSector.value = expandedSector.value === sectorCode ? null : sectorCode;
};

const formatTradingValue = (value) => {
  if (!value) return '0원';
  const num = parseFloat(value);
  if (num >= 1000000000000) {
    return (num / 1000000000000).toFixed(2) + '조';
  } else if (num >= 100000000) {
    return (num / 100000000).toFixed(0) + '억';
  } else if (num >= 10000) {
    return (num / 10000).toFixed(0) + '만';
  }
  return num.toLocaleString('ko-KR') + '원';
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0
  }).format(value);
};

const getChangeClass = (rate) => {
  if (!rate) return '';
  return rate > 0 ? 'positive' : rate < 0 ? 'negative' : '';
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
  color: var(--text-secondary);
  font-size: 14px;
}

.info-banner svg {
  color: #3B82F6;
  flex-shrink: 0;
}

/* 섹터 그리드 */
.sector-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 섹터 카드 */
.sector-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 20px 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
}

.sector-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
}

.sector-card.expanded {
  border-color: var(--primary-start);
}

[data-theme="dark"] .sector-card {
  background: rgba(31, 31, 35, 0.95);
}

/* 섹터 헤더 */
.sector-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.sector-info {
  display: flex;
  align-items: center;
  gap: 16px;
}

.sector-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
  font-size: 20px;
}

.sector-title h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.stock-count {
  font-size: 13px;
  color: var(--text-muted);
}

.sector-value {
  text-align: right;
}

.trading-value {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.percentage {
  font-size: 14px;
  font-weight: 600;
}

/* 프로그레스바 */
.progress-container {
  height: 8px;
  background: var(--border-light);
  border-radius: 4px;
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s ease;
}

/* 섹터 상세 */
.sector-detail {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--border-light);
}

.sector-detail h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.top-stocks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stock-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--background-secondary);
  border-radius: 10px;
}

.stock-row .stock-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stock-row .stock-name {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 14px;
}

.stock-row .stock-code {
  font-size: 12px;
  color: var(--text-muted);
}

.stock-row .stock-data {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stock-price {
  font-weight: 600;
  color: var(--text-primary);
}

.stock-change {
  font-weight: 600;
  font-size: 13px;
}

.stock-change.positive {
  color: #EF4444;
}

.stock-change.negative {
  color: #3B82F6;
}

.stock-trading {
  font-weight: 500;
  color: var(--text-secondary);
  font-size: 13px;
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
  color: var(--text-muted);
  margin-bottom: 8px;
}

.summary-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
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
@media (max-width: 768px) {
  .sector-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .sector-value {
    text-align: left;
  }

  .trading-value {
    font-size: 20px;
  }

  .summary-bar {
    flex-direction: column;
    gap: 16px;
  }

  .stock-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .stock-row .stock-data {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
