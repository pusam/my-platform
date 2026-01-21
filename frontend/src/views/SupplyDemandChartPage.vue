<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>수급 차트</h1>
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

      <!-- 설명 배너 -->
      <div class="info-banner discovery">
        <div class="banner-icon">🔍</div>
        <div class="banner-text">
          <strong>"어? 얘네 왜 이렇게 사지?" 종목을 발견하세요</strong>
          <p>외국인/기관 순매수가 쌓이는 종목, 프로그램 매수가 터지는데 주가가 아직 안 오른 종목들을 자동 탐지합니다.</p>
        </div>
      </div>

      <!-- 탭 메뉴 -->
      <div class="tab-menu">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          :class="['tab-btn', { active: activeTab === tab.value }]"
          @click="activeTab = tab.value"
        >
          <span class="tab-icon">{{ tab.icon }}</span>
          {{ tab.label }}
        </button>
      </div>

      <!-- 로딩 -->
      <LoadingSpinner v-if="loading" message="수급 데이터를 분석하는 중..." />

      <!-- 수급 순위 탭 -->
      <div v-else-if="activeTab === 'ranking'" class="tab-content">
        <!-- 정렬 옵션 -->
        <div class="sort-options">
          <button
            v-for="opt in sortOptions"
            :key="opt.value"
            :class="['sort-btn', { active: sortBy === opt.value }]"
            @click="changeSortBy(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>

        <!-- 순위 테이블 -->
        <div class="ranking-table">
          <div class="table-header">
            <span class="col-rank">순위</span>
            <span class="col-name">종목명</span>
            <span class="col-price">현재가</span>
            <span class="col-change">등락률</span>
            <span class="col-foreign">외국인</span>
            <span class="col-inst">기관</span>
            <span class="col-total">합계</span>
          </div>
          <div
            v-for="(stock, index) in filteredRankingData"
            :key="stock.stockCode || index"
            class="table-row"
            :class="{ 'buy-highlight': getTotalNetBuy(stock) > 10, 'sell-highlight': getTotalNetBuy(stock) < -10 }"
          >
            <span class="col-rank">
              <span class="rank-badge" :class="getRankClass(index)">{{ index + 1 }}</span>
            </span>
            <span class="col-name">
              <strong>{{ stock.stockName || stock.stockCode }}</strong>
              <small>{{ stock.stockCode }}</small>
            </span>
            <span class="col-price">{{ formatCurrency(stock.currentPrice) }}</span>
            <span class="col-change" :class="getChangeClass(stock.changeRate)">
              {{ stock.changeRate > 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) || 0 }}%
            </span>
            <span class="col-foreign" :class="getValueClass(stock.foreignNetBuy)">
              {{ formatBillion(stock.foreignNetBuy) }}
            </span>
            <span class="col-inst" :class="getValueClass(stock.institutionNetBuy)">
              {{ formatBillion(stock.institutionNetBuy) }}
            </span>
            <span class="col-total" :class="getValueClass(getTotalNetBuy(stock))">
              <strong>{{ formatBillion(getTotalNetBuy(stock)) }}</strong>
            </span>
          </div>
        </div>
      </div>

      <!-- 이상 종목 탐지 탭 -->
      <div v-else-if="activeTab === 'anomaly'" class="tab-content">
        <!-- 프로그램 매집 + 주가 횡보 -->
        <div class="anomaly-section" v-if="anomalyData.programAccumulating?.length">
          <div class="section-header">
            <span class="section-icon">💥</span>
            <h3>프로그램 매집 중 (주가 횡보)</h3>
            <span class="section-desc">프로그램 순매수가 쌓이는데 주가가 아직 안 움직임 → 조만간 터질 수 있음!</span>
          </div>
          <div class="stock-cards">
            <div v-for="(stock, idx) in anomalyData.programAccumulating" :key="stock?.stockCode || idx" class="anomaly-card program">
              <div class="card-top">
                <span class="stock-name">{{ stock.stockName || stock.stockCode }}</span>
                <span class="change-badge" :class="getChangeClass(stock.changeRate)">
                  {{ stock.changeRate?.toFixed(2) || 0 }}%
                </span>
              </div>
              <div class="card-value">
                <span class="label">프로그램</span>
                <span class="value positive">+{{ stock.programNetBuy?.toFixed(1) }}억</span>
              </div>
              <div class="card-bar">
                <div class="bar-fill" :style="{ width: getBarWidth(stock.programNetBuy, 50) + '%' }"></div>
              </div>
            </div>
          </div>
        </div>

        <!-- 외국인+기관 쌍끌이 -->
        <div class="anomaly-section" v-if="anomalyData.dualBuying?.length">
          <div class="section-header">
            <span class="section-icon">🐳</span>
            <h3>외국인+기관 쌍끌이 매수</h3>
            <span class="section-desc">큰 손들이 동시에 매수 중인 종목</span>
          </div>
          <div class="stock-cards">
            <div v-for="(stock, idx) in anomalyData.dualBuying" :key="stock?.stockCode || idx" class="anomaly-card dual">
              <div class="card-top">
                <span class="stock-name">{{ stock.stockName || stock.stockCode }}</span>
                <span class="change-badge" :class="getChangeClass(stock.changeRate)">
                  {{ stock.changeRate > 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) || 0 }}%
                </span>
              </div>
              <div class="dual-bars">
                <div class="bar-item">
                  <span class="label">외국인</span>
                  <span class="value positive">+{{ stock.foreignNetBuy?.toFixed(1) }}억</span>
                </div>
                <div class="bar-item">
                  <span class="label">기관</span>
                  <span class="value positive">+{{ stock.institutionNetBuy?.toFixed(1) }}억</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 외국인 대량 매집 -->
        <div class="anomaly-section" v-if="anomalyData.foreignHeavy?.length">
          <div class="section-header">
            <span class="section-icon">🌍</span>
            <h3>외국인 대량 매집</h3>
            <span class="section-desc">외국인 순매수 20억 이상</span>
          </div>
          <div class="stock-cards">
            <div v-for="(stock, idx) in anomalyData.foreignHeavy" :key="stock?.stockCode || idx" class="anomaly-card foreign">
              <div class="card-top">
                <span class="stock-name">{{ stock.stockName || stock.stockCode }}</span>
                <span class="change-badge" :class="getChangeClass(stock.changeRate)">
                  {{ stock.changeRate > 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) || 0 }}%
                </span>
              </div>
              <div class="card-value big">
                <span class="value positive">+{{ stock.foreignNetBuy?.toFixed(1) }}억</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 개인 역행 -->
        <div class="anomaly-section" v-if="anomalyData.retailContrarian?.length">
          <div class="section-header">
            <span class="section-icon">🔄</span>
            <h3>개인 역행 (기관/외국인은 매수)</h3>
            <span class="section-desc">개인은 던지는데 기관/외국인이 받는 종목 → 개미 털기?</span>
          </div>
          <div class="stock-cards">
            <div v-for="(stock, idx) in anomalyData.retailContrarian" :key="stock?.stockCode || idx" class="anomaly-card contrarian">
              <div class="card-top">
                <span class="stock-name">{{ stock.stockName || stock.stockCode }}</span>
                <span class="change-badge" :class="getChangeClass(stock.changeRate)">
                  {{ stock.changeRate > 0 ? '+' : '' }}{{ stock.changeRate?.toFixed(2) || 0 }}%
                </span>
              </div>
              <div class="contrarian-info">
                <div class="info-row negative">
                  <span class="label">개인</span>
                  <span class="value">{{ stock.individualNetBuy?.toFixed(1) }}억</span>
                </div>
                <div class="info-row positive">
                  <span class="label">외+기</span>
                  <span class="value">+{{ (stock.foreignNetBuy + stock.institutionNetBuy)?.toFixed(1) }}억</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 데이터 없음 -->
        <div v-if="!hasAnomalyData" class="empty-anomaly">
          <div class="empty-icon">🤷</div>
          <p>현재 탐지된 이상 종목이 없습니다.</p>
          <small>장 중에 다시 확인해 보세요.</small>
        </div>
      </div>

      <!-- 마지막 업데이트 -->
      <div class="update-info">
        <span>마지막 업데이트: {{ lastUpdate }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { investorAPI } from '../utils/api';
import { UserManager } from '../utils/auth';
import LoadingSpinner from '../components/LoadingSpinner.vue';

const router = useRouter();

const loading = ref(false);
const activeTab = ref('ranking');
const sortBy = ref('TOTAL');
const rankingData = ref([]);
const anomalyData = ref({});
const lastUpdate = ref('-');
let refreshInterval = null;

const tabs = [
  { value: 'ranking', label: '수급 순위', icon: '📊' },
  { value: 'anomaly', label: '이상 종목 탐지', icon: '🔍' }
];

const sortOptions = [
  { value: 'TOTAL', label: '외국인+기관' },
  { value: 'FOREIGN', label: '외국인' },
  { value: 'INSTITUTION', label: '기관' }
];

const hasAnomalyData = computed(() => {
  return Object.values(anomalyData.value).some(arr => arr && arr.length > 0);
});

// null 데이터 필터링
const filteredRankingData = computed(() => {
  return (rankingData.value || []).filter(stock => stock && stock.stockCode);
});

const loadRankingData = async () => {
  try {
    const response = await investorAPI.getRanking('', sortBy.value);
    if (response.data.success && response.data.data) {
      // null과 stockCode가 없는 항목 필터링
      rankingData.value = response.data.data.filter(item => item && item.stockCode);
    } else {
      rankingData.value = [];
    }
  } catch (error) {
    console.error('수급 순위 로드 실패:', error);
    rankingData.value = [];
  }
};

const loadAnomalyData = async () => {
  try {
    const response = await investorAPI.getAnomalyStocks();
    if (response.data.success && response.data.data) {
      // 각 카테고리별로 null 필터링
      const data = response.data.data;
      anomalyData.value = {
        programAccumulating: (data.programAccumulating || []).filter(s => s && s.stockCode),
        dualBuying: (data.dualBuying || []).filter(s => s && s.stockCode),
        foreignHeavy: (data.foreignHeavy || []).filter(s => s && s.stockCode),
        retailContrarian: (data.retailContrarian || []).filter(s => s && s.stockCode)
      };
    } else {
      anomalyData.value = {};
    }
  } catch (error) {
    console.error('이상 종목 로드 실패:', error);
    anomalyData.value = {};
  }
};

const loadData = async () => {
  loading.value = true;
  try {
    await Promise.all([loadRankingData(), loadAnomalyData()]);
    lastUpdate.value = new Date().toLocaleTimeString('ko-KR');
  } finally {
    loading.value = false;
  }
};

const refreshData = () => loadData();

const changeSortBy = async (value) => {
  sortBy.value = value;
  await loadRankingData();
};

const getTotalNetBuy = (stock) => {
  return (stock.foreignNetBuy || 0) + (stock.institutionNetBuy || 0);
};

const formatCurrency = (value) => {
  if (!value) return '0원';
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', minimumFractionDigits: 0 }).format(value);
};

const formatBillion = (value) => {
  if (!value) return '0억';
  const num = parseFloat(value);
  return (num >= 0 ? '+' : '') + num.toFixed(1) + '억';
};

const getChangeClass = (rate) => {
  if (!rate) return '';
  return rate > 0 ? 'positive' : rate < 0 ? 'negative' : '';
};

const getValueClass = (value) => {
  if (!value) return '';
  return value > 0 ? 'positive' : value < 0 ? 'negative' : '';
};

const getRankClass = (index) => {
  if (index === 0) return 'gold';
  if (index === 1) return 'silver';
  if (index === 2) return 'bronze';
  return '';
};

const getBarWidth = (value, max) => {
  if (!value || !max) return 0;
  return Math.min((value / max) * 100, 100);
};

const goBack = () => router.back();
const logout = () => {
  UserManager.logout();
  router.push('/login');
};

onMounted(() => {
  loadData();
  refreshInterval = setInterval(loadData, 3 * 60 * 1000); // 3분마다 갱신
});

onUnmounted(() => {
  if (refreshInterval) clearInterval(refreshInterval);
});
</script>

<style scoped>
/* 배너 */
.info-banner.discovery {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.1) 0%, rgba(239, 68, 68, 0.1) 100%);
  border: 2px solid rgba(245, 158, 11, 0.2);
  border-radius: 16px;
  margin-bottom: 24px;
}

.banner-icon {
  font-size: 48px;
}

.banner-text strong {
  display: block;
  font-size: 18px;
  color: #1f2937;
  margin-bottom: 4px;
}

.banner-text p {
  margin: 0;
  font-size: 14px;
  color: #4b5563;
  line-height: 1.5;
}

/* 탭 메뉴 */
.tab-menu {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  background: #f3f4f6;
  padding: 8px;
  border-radius: 16px;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 20px;
  border: none;
  border-radius: 12px;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
  color: #4b5563;
  cursor: pointer;
  transition: all 0.3s;
}

.tab-btn:hover {
  background: rgba(59, 130, 246, 0.1);
}

.tab-btn.active {
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  color: white;
  box-shadow: 0 4px 15px rgba(59, 130, 246, 0.3);
}

.tab-icon {
  font-size: 18px;
}

/* 정렬 옵션 */
.sort-options {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}

.sort-btn {
  padding: 10px 20px;
  border: 2px solid #e5e7eb;
  border-radius: 10px;
  background: white;
  font-weight: 600;
  color: #4b5563;
  cursor: pointer;
  transition: all 0.2s;
}

.sort-btn:hover {
  border-color: #3b82f6;
  color: #3b82f6;
}

.sort-btn.active {
  background: linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%);
  border-color: transparent;
  color: white;
}

/* 순위 테이블 */
.ranking-table {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.table-header {
  display: grid;
  grid-template-columns: 60px 1fr 100px 80px 90px 90px 100px;
  padding: 16px 20px;
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  font-weight: 700;
  font-size: 13px;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.table-row {
  display: grid;
  grid-template-columns: 60px 1fr 100px 80px 90px 90px 100px;
  padding: 16px 20px;
  border-bottom: 1px solid #f3f4f6;
  align-items: center;
  transition: background 0.2s;
}

.table-row:hover {
  background: rgba(59, 130, 246, 0.03);
}

.table-row.buy-highlight {
  background: linear-gradient(90deg, rgba(239, 68, 68, 0.05) 0%, transparent 100%);
}

.table-row.sell-highlight {
  background: linear-gradient(90deg, rgba(59, 130, 246, 0.05) 0%, transparent 100%);
}

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  font-weight: 700;
  font-size: 14px;
  background: #f1f5f9;
  color: #4b5563;
}

.rank-badge.gold {
  background: linear-gradient(135deg, #fbbf24 0%, #f59e0b 100%);
  color: white;
}

.rank-badge.silver {
  background: linear-gradient(135deg, #9ca3af 0%, #6b7280 100%);
  color: white;
}

.rank-badge.bronze {
  background: linear-gradient(135deg, #d97706 0%, #b45309 100%);
  color: white;
}

.col-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.col-name strong {
  font-size: 15px;
  color: #1f2937;
}

.col-name small {
  font-size: 12px;
  color: #9ca3af;
}

.col-price {
  font-weight: 600;
  color: #1f2937;
}

.col-change, .col-foreign, .col-inst, .col-total {
  font-weight: 600;
  font-size: 14px;
}

.positive { color: #ef4444; }
.negative { color: #3b82f6; }

/* 이상 종목 섹션 */
.anomaly-section {
  margin-bottom: 32px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.section-icon {
  font-size: 28px;
}

.section-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
}

.section-desc {
  width: 100%;
  margin-left: 40px;
  font-size: 14px;
  color: #6b7280;
}

/* 이상 종목 카드 */
.stock-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.anomaly-card {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.08);
  transition: all 0.3s;
}

.anomaly-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 25px rgba(0, 0, 0, 0.12);
}

.anomaly-card.program {
  border-left: 4px solid #8b5cf6;
}

.anomaly-card.dual {
  border-left: 4px solid #10b981;
}

.anomaly-card.foreign {
  border-left: 4px solid #f59e0b;
}

.anomaly-card.contrarian {
  border-left: 4px solid #ef4444;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.stock-name {
  font-weight: 700;
  font-size: 16px;
  color: #1f2937;
}

.change-badge {
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
}

.change-badge.positive {
  background: rgba(239, 68, 68, 0.1);
}

.change-badge.negative {
  background: rgba(59, 130, 246, 0.1);
}

.card-value {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.card-value .label {
  font-size: 13px;
  color: #6b7280;
}

.card-value .value {
  font-size: 20px;
  font-weight: 700;
  color: #1f2937;
}

.card-value.big .value {
  font-size: 28px;
}

.card-bar {
  height: 8px;
  background: #f3f4f6;
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #8b5cf6 0%, #a78bfa 100%);
  border-radius: 4px;
  transition: width 0.5s ease;
}

.dual-bars {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bar-item {
  display: flex;
  justify-content: space-between;
}

.bar-item .label {
  font-size: 13px;
  color: #6b7280;
}

.bar-item .value {
  font-weight: 600;
  color: #1f2937;
}

.contrarian-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 8px;
}

.info-row.positive {
  background: rgba(239, 68, 68, 0.1);
}

.info-row.negative {
  background: rgba(59, 130, 246, 0.1);
}

.info-row .label {
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.info-row .value {
  font-weight: 700;
  color: inherit;
}

/* 빈 상태 */
.empty-anomaly {
  text-align: center;
  padding: 60px 20px;
  background: #f3f4f6;
  border-radius: 16px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.empty-anomaly p {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 600;
  color: #374151;
}

.empty-anomaly small {
  color: #6b7280;
}

/* 업데이트 정보 */
.update-info {
  text-align: center;
  padding: 16px;
  color: #6b7280;
  font-size: 13px;
}

/* 새로고침 버튼 */
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
}

.btn-refresh svg.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 반응형 */
@media (max-width: 1024px) {
  .table-header, .table-row {
    grid-template-columns: 50px 1fr 80px 70px 80px 80px 90px;
    font-size: 13px;
  }
}

@media (max-width: 768px) {
  .ranking-table {
    overflow-x: auto;
  }

  .table-header, .table-row {
    min-width: 700px;
  }

  .stock-cards {
    grid-template-columns: 1fr;
  }

  .tab-menu {
    flex-direction: column;
  }

  .sort-options {
    flex-wrap: wrap;
  }
}
</style>
