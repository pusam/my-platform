<template>
  <div :class="['page-container', { embedded: embedded }]">
    <div class="page-content">
      <!-- 헤더 -->
      <header v-if="!embedded" class="common-header">
        <BackButton />
        <h1>섹터별 거래대금</h1>
        <div class="header-actions">
          <button @click="refreshData" class="btn btn-refresh" :disabled="loading">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" :class="{ spinning: loading }">
              <path d="M21 12a9 9 0 11-9-9"/>
              <polyline points="21 3 21 9 15 9"/>
            </svg>
            새로고침
          </button>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 데이터 갱신 상태 -->
      <div class="freshness-bar">
        <DataFreshness :lastUpdated="lastUpdated" :isRefreshing="isRefreshing" :nextRefreshIn="nextRefreshIn" @refresh="manualRefresh" />
      </div>

      <!-- 설명 -->
      <div class="info-banner">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="16" x2="12" y2="12"/>
          <line x1="12" y1="8" x2="12.01" y2="8"/>
        </svg>
        <span>오늘의 테마/섹터별 누적 거래대금을 확인하세요. 데이터는 5분마다 자동 갱신됩니다.</span>
      </div>

      <!-- 기간 선택 탭 -->
      <div class="period-tabs">
        <button
          v-for="tab in periodTabs"
          :key="tab.value"
          :class="['period-tab', { active: selectedPeriod === tab.value }]"
          @click="changePeriod(tab.value)"
        >
          <span class="tab-icon">{{ tab.icon }}</span>
          <span class="tab-label">{{ tab.label }}</span>
          <!-- 5분파워 탭 활성화 시 빨간 점 깜빡임 -->
          <span v-if="tab.value === 'MIN_5' && selectedPeriod === 'MIN_5'" class="live-dot"></span>
        </button>
      </div>

      <!-- 로딩 상태 -->
      <LoadingSpinner v-if="loading" message="섹터별 거래대금을 불러오는 중..." />

      <!-- 정규장 개장(09:00) 전 — 빈 데이터를 "수집 실패" 로 오해하지 않게 안내 -->
      <div v-else-if="!sectors.length && isPreMarket" class="collecting-state premarket">
        <p>🕘 정규장 개장 대기 중 (09:00 시작)</p>
        <p class="collecting-hint">섹터 거래대금은 KRX 정규장 기준 — 9시 첫 스냅샷부터 채워집니다 (NXT 프리마켓 08:00~08:50 제외)</p>
      </div>

      <!-- 데이터 수집 중 (로딩 끝났는데 데이터 없음) -->
      <div v-else-if="!sectors.length" class="collecting-state">
        <div class="collecting-spinner"></div>
        <p>데이터를 수집하고 있습니다...</p>
        <p class="collecting-hint">잠시 후 자동으로 새로고침됩니다</p>
      </div>

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
            <div class="sector-badge" :style="{ background: sector.color + '20', color: sector.color }">
              전체 대비 {{ sector.percentage?.toFixed(1) || 0 }}%
            </div>
          </div>

          <!-- 섹터 총 거래대금 강조 박스 -->
          <div class="sector-total-box" :style="{ borderColor: sector.color + '40', background: sector.color + '08' }">
            <div class="total-info">
              <span class="total-label">{{ sector.sectorName }} 거래대금</span>
              <span class="total-value" :style="{ color: sector.color }">{{ formatTradingValue(sector.totalTradingValue) }}</span>
            </div>
            <div class="total-chart">
              <div class="chart-track">
                <div class="mini-bar" :style="{ width: Math.min(sector.percentage * 2, 100) + '%', background: sector.color }"></div>
              </div>
              <span class="chart-percentage" :style="{ color: sector.color }">{{ sector.percentage?.toFixed(1) || 0 }}%</span>
            </div>
          </div>

          <!-- 확장 시 상위 종목 표시 -->
          <div v-if="expandedSector === sector.sectorCode" class="sector-detail">
            <h4>거래대금 상위 종목</h4>
            <div class="top-stocks">
              <div v-for="stock in sector.topStocks" :key="stock.stockCode" class="stock-row"
                   @click.stop="goToStock(stock.stockCode)">
                <div class="stock-info">
                  <span class="stock-name">{{ stock.stockName || stock.stockCode }}</span>
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
import { formatTradingValue } from '../utils/marketFormatters';
import LoadingSpinner from '../components/LoadingSpinner.vue';
import BackButton from '../components/BackButton.vue';
import DataFreshness from '../components/DataFreshness.vue';
import { useAutoRefresh } from '../composables/useAutoRefresh.js';

const props = defineProps({
  embedded: { type: Boolean, default: false }
});

const router = useRouter();

const sectors = ref([]);
const loading = ref(false);
const expandedSector = ref(null);
const lastUpdate = ref('-');
const selectedPeriod = ref('TODAY');

// 기간 선택 탭
const periodTabs = [
  { value: 'TODAY', label: '오늘누적', icon: '📊' },
  { value: 'MIN_5', label: '5분파워', icon: '⚡' },
  { value: 'MIN_30', label: '30분파워', icon: '🔥' }
];

const totalTradingValue = computed(() => {
  return sectors.value.reduce((sum, s) => {
    const val = parseFloat(s.totalTradingValue) || 0;
    return sum + val;
  }, 0);
});

// 정규장 개장(09:00) 전 평일 — 사용자에게 "곧 시작" 안내 노출.
// 백엔드 스냅샷 스케줄러가 09:00부터 시작하므로 8~9시는 의도적으로 빈 상태.
// 시계 갱신용 reactive ref (1분마다 업데이트).
const nowTick = ref(new Date());
let tickInterval = null;
const isPreMarket = computed(() => {
  const d = nowTick.value;
  const day = d.getDay();
  if (day === 0 || day === 6) return false;
  const mins = d.getHours() * 60 + d.getMinutes();
  return mins >= 480 && mins < 540; // 08:00 ~ 08:59
});

let retryTimeout = null;
let retryCount = 0;
const MAX_RETRIES = 3;

const loadData = async () => {
  try {
    loading.value = true;
    const response = await sectorAPI.getSectorTrading(selectedPeriod.value);
    if (response.data.success) {
      sectors.value = response.data.data || [];
      lastUpdate.value = new Date().toLocaleTimeString('ko-KR');

      // 데이터가 비어있으면 5초 후 자동 재시도 (최대 3회)
      if (sectors.value.length === 0 && retryCount < MAX_RETRIES) {
        retryCount++;
        if (retryTimeout) clearTimeout(retryTimeout);
        retryTimeout = setTimeout(() => {
          loadData();
        }, 5000);
      } else {
        retryCount = 0;
      }
    }
  } catch (error) {
    console.error('섹터 데이터 로드 실패:', error);
  } finally {
    loading.value = false;
  }
};

const changePeriod = async (period) => {
  selectedPeriod.value = period;
  await loadData();
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

// formatTradingValue는 공통 유틸리티 (marketFormatters.js)에서 import

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

const goToStock = (stockCode) => {
  router.push('/stock/' + stockCode);
};

const goBack = () => {
  router.back();
};

const logout = () => {
  UserManager.logout();
  router.push('/login');
};

// 페이지 가시성에 따라 polling 자동 일시정지/재개
const startPolling = () => {
  if (!tickInterval) {
    tickInterval = setInterval(() => { nowTick.value = new Date(); }, 60 * 1000);
  }
};
const stopPolling = () => {
  if (tickInterval) { clearInterval(tickInterval); tickInterval = null; }
};
const onVisibilityChange = () => {
  if (document.hidden) {
    stopPolling();
  } else {
    startPolling();
  }
};

// 데이터 자동 갱신 (60초)
const { lastUpdated, isRefreshing, nextRefreshIn, manualRefresh } = useAutoRefresh(
  async () => { await loadData(); },
  { interval: 60 * 1000 }
);

onMounted(() => {
  startPolling();
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onUnmounted(() => {
  stopPolling();
  document.removeEventListener('visibilitychange', onVisibilityChange);
  if (retryTimeout) {
    clearTimeout(retryTimeout);
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

/* 기간 선택 탭 */
.period-tabs {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  padding: 8px;
  background: #f3f4f6;
  border-radius: 16px;
  justify-content: center;
}

.period-tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 28px;
  border: none;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  transition: all 0.3s ease;
  font-size: 15px;
  font-weight: 600;
  color: #4b5563;
}

.period-tab:hover {
  background: rgba(59, 130, 246, 0.1);
  color: #1f2937;
}

.period-tab.active {
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  box-shadow: 0 4px 15px rgba(59, 130, 246, 0.3);
}

.tab-icon {
  font-size: 18px;
}

.tab-label {
  font-weight: 600;
}

/* 5분파워 라이브 점 (빨간색 깜빡임) */
.live-dot {
  width: 8px;
  height: 8px;
  background: #EF4444;
  border-radius: 50%;
  animation: pulse-dot 1.5s ease-in-out infinite;
  box-shadow: 0 0 8px rgba(239, 68, 68, 0.6);
}

@keyframes pulse-dot {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
    box-shadow: 0 0 8px rgba(239, 68, 68, 0.6);
  }
  50% {
    opacity: 0.5;
    transform: scale(0.8);
    box-shadow: 0 0 4px rgba(239, 68, 68, 0.3);
  }
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
  border-color: #3b82f6;
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
  color: #1f2937;
}

.stock-count {
  font-size: 13px;
  color: #6b7280;
}

.sector-badge {
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
}

/* 섹터 총 거래대금 강조 박스 */
.sector-total-box {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-radius: 14px;
  border: 2px solid;
  margin-bottom: 16px;
}

.total-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.total-label {
  font-size: 13px;
  font-weight: 500;
  color: #6b7280;
}

.total-value {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.5px;
}

.total-chart {
  display: flex;
  align-items: center;
  gap: 12px;
}

.chart-track {
  width: 140px;
  height: 12px;
  background: linear-gradient(135deg, #e5e7eb 0%, #f3f4f6 100%);
  border-radius: 6px;
  overflow: hidden;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.06);
}

.mini-bar {
  height: 100%;
  border-radius: 6px;
  transition: width 0.5s ease;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.15);
}

.chart-percentage {
  font-size: 14px;
  font-weight: 700;
  min-width: 45px;
  text-align: right;
}

/* 섹터 상세 */
.sector-detail {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #f3f4f6;
}

.sector-detail h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: #6b7280;
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
  background: #f3f4f6;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.2s;
}

.stock-row:hover {
  background: #e5e7eb;
}

.stock-row .stock-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stock-row .stock-name {
  font-weight: 600;
  color: #1f2937;
  font-size: 14px;
}

.stock-row .stock-code {
  font-size: 12px;
  color: #9ca3af;
}

.stock-row .stock-data {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stock-price {
  font-weight: 600;
  color: #1f2937;
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
  color: #4b5563;
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

/* 데이터 수집 중 상태 */
.collecting-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  text-align: center;
}

.collecting-spinner {
  width: 50px;
  height: 50px;
  border: 4px solid #e5e7eb;
  border-top-color: #3B82F6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 20px;
}

.collecting-state p {
  color: #4b5563;
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}

.collecting-state .collecting-hint {
  color: #9ca3af;
  font-size: 14px;
  font-weight: 400;
  margin-top: 8px;
}

/* 반응형 */
@media (max-width: 768px) {
  .sector-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .sector-total-box {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .total-value {
    font-size: 24px;
  }

  .total-chart {
    width: 100%;
    justify-content: space-between;
  }

  .chart-track {
    flex: 1;
    max-width: none;
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

@media (max-width: 480px) {
  .sector-card {
    padding: 12px;
  }

  .sector-title h3 {
    font-size: 15px;
  }

  .sector-total-box {
    padding: 12px;
  }

  .total-value {
    font-size: 18px;
  }

  .summary-value {
    font-size: 16px;
  }

  .summary-bar {
    padding: 14px;
    gap: 12px;
  }
}

.page-container.embedded {
  min-height: auto;
  padding: 0;
  background: none;
}

.freshness-bar { display: flex; justify-content: flex-end; margin: -4px 0 12px; }
@media (max-width: 480px) { .freshness-bar { justify-content: center; } }
</style>
