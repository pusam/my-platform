<template>
  <div class="market-info-widget">
    <div class="widget-header">
      <h3>
        <span class="header-icon">📡</span>
        시장 HUD
      </h3>
      <span class="update-time" v-if="lastUpdated && !loading">{{ lastUpdated }} 기준</span>
    </div>

    <!-- 로딩 상태: 스켈레톤 UI -->
    <div v-if="loading" class="info-cards">
      <div class="info-card skeleton-card">
        <div class="skeleton-header"></div>
        <div class="skeleton-main"></div>
        <div class="skeleton-sub"></div>
        <div class="skeleton-badge"></div>
      </div>
      <div class="info-card skeleton-card">
        <div class="skeleton-header"></div>
        <div class="skeleton-items">
          <div class="skeleton-item"></div>
          <div class="skeleton-item"></div>
          <div class="skeleton-item"></div>
        </div>
      </div>
    </div>

    <!-- 데이터 로드 완료 -->
    <div v-else class="info-cards">
      <!-- 좌측: 시장 상태 요약 -->
      <div class="info-card status-card" :class="marketStatusClass">
        <div class="status-icon">{{ marketStatusIcon }}</div>
        <div class="status-content">
          <div class="status-label">시장 상태</div>
          <div class="status-main">{{ marketStatusTitle }}</div>
          <div class="status-interpretation">{{ marketStatusDescription }}</div>
        </div>
        <div class="adr-badge" :class="adrBadgeClass">
          {{ isCrash ? '⚠ 폭락' : ('ADR ' + (marketData?.combinedAdr?.toFixed(0) || '-')) }}
        </div>
      </div>

      <!-- 우측: 지수/환율 현황 -->
      <div class="info-card indices-card">
        <div class="indices-grid">
          <!-- KOSPI -->
          <div class="index-item">
            <span class="index-label">KOSPI</span>
            <span class="index-value" v-if="marketData?.kospiIndex">
              {{ formatNumber(marketData.kospiIndex, 2) }}
            </span>
            <span class="index-value" v-else>-</span>
            <span class="index-change" :class="getChangeClass(marketData?.kospiChange)">
              {{ formatChange(marketData?.kospiChange) }}
            </span>
          </div>

          <!-- KOSDAQ -->
          <div class="index-item">
            <span class="index-label">KOSDAQ</span>
            <span class="index-value" v-if="marketData?.kosdaqIndex">
              {{ formatNumber(marketData.kosdaqIndex, 2) }}
            </span>
            <span class="index-value" v-else>-</span>
            <span class="index-change" :class="getChangeClass(marketData?.kosdaqChange)">
              {{ formatChange(marketData?.kosdaqChange) }}
            </span>
          </div>

          <!-- 환율 -->
          <div class="index-item">
            <span class="index-label">USD/KRW</span>
            <span class="index-value" v-if="exchangeData?.rate">
              {{ formatNumber(exchangeData.rate, 0) }}
            </span>
            <span class="index-value delayed-text" v-else>데이터 지연</span>
            <span v-if="exchangeData?.changeRate != null" class="index-change" :class="getChangeClass(exchangeData?.changeRate, true)">
              {{ formatChange(exchangeData?.changeRate) }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { marketAPI, exchangeRateAPI } from '@/utils/api';
import { checkCrash, getMarketStatus } from '@/composables/useMarketStatus';
import { formatNumber, formatChange, getChangeClass } from '@/utils/marketFormatters';

const marketData = ref(null);
const exchangeData = ref(null);
const loading = ref(true);
const lastUpdated = ref('');
let refreshInterval = null;

// 폭락 감지 (공통 컴포저블 사용)
const isCrash = computed(() => checkCrash(marketData.value));

// 시장 상태 (공통 컴포저블 — ADR 기반 + 폭락 override)
const statusInfo = computed(() => getMarketStatus(isCrash.value, marketData.value?.combinedAdr));
const marketStatusClass = computed(() => statusInfo.value.status);
const marketStatusIcon = computed(() => statusInfo.value.icon);
const marketStatusTitle = computed(() => statusInfo.value.title);
const marketStatusDescription = computed(() => statusInfo.value.desc);
const adrBadgeClass = computed(() => statusInfo.value.badgeClass);

const fetchData = async () => {
  try {
    const [marketRes, exchangeRes] = await Promise.all([
      marketAPI.getSimpleStatus().catch(() => ({ data: null })),
      exchangeRateAPI.getCurrentRate().catch(() => ({ data: null }))
    ]);

    // market API는 { success, data } 래퍼 구조
    if (marketRes.data?.success && marketRes.data?.data) {
      marketData.value = marketRes.data.data;
    }
    // exchange rate API는 DTO 직접 반환
    if (exchangeRes.data) {
      exchangeData.value = exchangeRes.data;
    }

    lastUpdated.value = new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
  } catch (error) {
    console.error('시장 정보 조회 실패:', error);
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  fetchData();
  // 5분마다 갱신
  refreshInterval = setInterval(fetchData, 5 * 60 * 1000);
});

onUnmounted(() => {
  if (refreshInterval) {
    clearInterval(refreshInterval);
  }
});
</script>

<style scoped>
.market-info-widget {
  background: linear-gradient(135deg, #1a1a3a 0%, #2a2a5a 100%);
  border-radius: 16px;
  padding: 20px;
  border: 1px solid #3a3a6a;
  position: relative;
  overflow: hidden;
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.widget-header h3 {
  margin: 0;
  color: #fff;
  font-size: 1.1rem;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon {
  font-size: 1.2rem;
}

.update-time {
  font-size: 0.75rem;
  color: #888;
  background: var(--border-light);
  padding: 4px 10px;
  border-radius: 12px;
}

.info-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 700px) {
  .info-cards {
    grid-template-columns: 1fr;
  }
}

.info-card {
  background: var(--border-light);
  border-radius: 12px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: all 0.3s ease;
}

/* ===== 좌측: 시장 상태 카드 ===== */
.status-card {
  display: flex;
  align-items: center;
  gap: 14px;
  position: relative;
}

.status-card.overheated {
  border-color: rgba(239, 68, 68, 0.5);
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.15) 0%, rgba(239, 68, 68, 0.05) 100%);
}

.status-card.bullish {
  border-color: rgba(34, 197, 94, 0.5);
  background: linear-gradient(135deg, rgba(34, 197, 94, 0.15) 0%, rgba(34, 197, 94, 0.05) 100%);
}

.status-card.normal {
  border-color: rgba(156, 163, 175, 0.5);
  background: linear-gradient(135deg, rgba(156, 163, 175, 0.1) 0%, rgba(156, 163, 175, 0.02) 100%);
}

.status-card.bearish {
  border-color: rgba(245, 158, 11, 0.5);
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.15) 0%, rgba(245, 158, 11, 0.05) 100%);
}

.status-card.extreme-fear {
  border-color: rgba(59, 130, 246, 0.5);
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.15) 0%, rgba(59, 130, 246, 0.05) 100%);
}

.status-card.crash {
  border-color: rgba(220, 38, 38, 0.7);
  background: linear-gradient(135deg, rgba(220, 38, 38, 0.25) 0%, rgba(153, 27, 27, 0.15) 100%);
  animation: crashPulse 1.5s ease-in-out infinite;
}

@keyframes crashPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.status-icon {
  font-size: 2.2rem;
  line-height: 1;
}

.status-content {
  flex: 1;
}

.status-label {
  font-size: 0.75rem;
  color: #888;
  margin-bottom: 4px;
}

.status-main {
  font-size: 1.4rem;
  font-weight: 700;
  color: #fff;
  margin-bottom: 4px;
}

.status-interpretation {
  font-size: 0.85rem;
  color: #aaa;
}

.adr-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 600;
  font-family: 'Monaco', 'Consolas', monospace;
}

.badge-danger {
  background: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

.badge-success {
  background: rgba(34, 197, 94, 0.2);
  color: #22c55e;
}

.badge-neutral {
  background: rgba(156, 163, 175, 0.2);
  color: #9ca3af;
}

.badge-warning {
  background: rgba(245, 158, 11, 0.2);
  color: #f59e0b;
}

.badge-info {
  background: rgba(59, 130, 246, 0.2);
  color: #3b82f6;
}

.badge-crash {
  background: rgba(220, 38, 38, 0.3);
  color: #fca5a5;
  font-weight: 700;
  animation: crashPulse 1.5s ease-in-out infinite;
}

.delayed-text {
  font-size: 0.75rem !important;
  color: rgba(245, 158, 11, 0.7) !important;
  font-weight: 500 !important;
}

/* ===== 우측: 지수 현황 카드 ===== */
.indices-card {
  display: flex;
  align-items: center;
}

.indices-grid {
  display: flex;
  width: 100%;
  gap: 8px;
}

.index-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 8px 4px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
}

.index-label {
  font-size: 0.7rem;
  color: #888;
  margin-bottom: 4px;
  font-weight: 500;
}

.index-value {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  font-family: 'Monaco', 'Consolas', monospace;
  margin-bottom: 2px;
}

.index-change {
  font-size: 0.75rem;
  font-weight: 600;
  font-family: 'Monaco', 'Consolas', monospace;
}

.index-change.positive {
  color: #ef4444;
}

.index-change.negative {
  color: #3b82f6;
}

/* ===== 스켈레톤 로딩 UI ===== */
.skeleton-card {
  background: rgba(255, 255, 255, 0.03);
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.skeleton-header {
  width: 60%;
  height: 12px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  margin-bottom: 12px;
}

.skeleton-main {
  width: 80%;
  height: 24px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  margin-bottom: 8px;
}

.skeleton-sub {
  width: 100%;
  height: 14px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  margin-bottom: 12px;
}

.skeleton-badge {
  width: 50px;
  height: 20px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  margin-left: auto;
}

.skeleton-items {
  display: flex;
  gap: 8px;
}

.skeleton-item {
  flex: 1;
  height: 60px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 8px;
}
</style>
