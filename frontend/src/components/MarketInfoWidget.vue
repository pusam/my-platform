<template>
  <div class="market-info-widget">
    <div class="widget-header">
      <h3>시장 정보</h3>
      <span class="update-time" v-if="lastUpdated">{{ lastUpdated }}</span>
    </div>

    <div class="info-cards">
      <!-- ADR (등락비율) -->
      <div class="info-card adr-card" :class="adrClass">
        <div class="info-label">
          <span class="icon">📊</span>
          ADR (20일)
        </div>
        <div class="info-value">
          <span class="main-value" v-if="marketData?.combinedAdr">
            {{ marketData.combinedAdr.toFixed(1) }}
          </span>
          <span class="main-value" v-else>-</span>
        </div>
        <div class="info-status" :class="adrStatusClass">
          {{ adrStatusText }}
        </div>
      </div>

      <!-- USD/KRW 환율 -->
      <div class="info-card exchange-card" :class="exchangeClass">
        <div class="info-label">
          <span class="icon">💱</span>
          USD/KRW
        </div>
        <div class="info-value">
          <span class="main-value" v-if="exchangeData?.rate">
            {{ formatNumber(exchangeData.rate) }}
          </span>
          <span class="main-value" v-else>-</span>
          <span class="change-value" v-if="exchangeData?.change" :class="exchangeTrendClass">
            {{ exchangeData.change >= 0 ? '+' : '' }}{{ exchangeData.change.toFixed(2) }}
            ({{ exchangeData.changeRate >= 0 ? '+' : '' }}{{ exchangeData.changeRate?.toFixed(2) }}%)
          </span>
        </div>
        <div class="info-status" :class="exchangeSignalClass">
          {{ exchangeSignalText }}
        </div>
      </div>
    </div>

    <div class="loading-overlay" v-if="loading">
      <span class="spinner"></span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { marketAPI, exchangeRateAPI } from '@/utils/api';

const marketData = ref(null);
const exchangeData = ref(null);
const loading = ref(true);
const lastUpdated = ref('');
let refreshInterval = null;

// ADR 클래스
const adrClass = computed(() => {
  if (!marketData.value?.combinedAdr) return '';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return 'overheated';
  if (adr <= 60) return 'extreme-fear';
  if (adr <= 80) return 'oversold';
  return 'normal';
});

const adrStatusClass = computed(() => {
  if (!marketData.value?.overallCondition) return 'neutral';
  const condition = marketData.value.overallCondition;
  switch (condition) {
    case 'OVERHEATED': return 'danger';
    case 'EXTREME_FEAR': return 'danger';
    case 'OVERSOLD': return 'warning';
    default: return 'normal';
  }
});

const adrStatusText = computed(() => {
  if (!marketData.value?.combinedAdr) return '데이터 없음';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return '과열 - 매수 주의';
  if (adr <= 60) return '극심한 공포 - 기회 탐색';
  if (adr <= 80) return '침체 - 바닥 탐색';
  return '정상';
});

// 환율 클래스
const exchangeClass = computed(() => {
  if (!exchangeData.value?.signal) return '';
  switch (exchangeData.value.signal) {
    case 'FOREIGN_SELL': return 'sell-pressure';
    case 'FOREIGN_BUY': return 'buy-pressure';
    default: return 'neutral';
  }
});

const exchangeTrendClass = computed(() => {
  if (!exchangeData.value?.trend) return '';
  return exchangeData.value.trend === 'UP' ? 'up' : 'down';
});

const exchangeSignalClass = computed(() => {
  if (!exchangeData.value?.signal) return 'neutral';
  switch (exchangeData.value.signal) {
    case 'FOREIGN_SELL': return 'warning';
    case 'FOREIGN_BUY': return 'success';
    default: return 'neutral';
  }
});

const exchangeSignalText = computed(() => {
  if (!exchangeData.value?.signal) return '-';
  switch (exchangeData.value.signal) {
    case 'FOREIGN_SELL': return '외국인 매도 압력';
    case 'FOREIGN_BUY': return '외국인 매수 유입';
    default: return '중립';
  }
});

const formatNumber = (num) => {
  if (!num) return '-';
  return num.toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};

const fetchData = async () => {
  try {
    const [marketRes, exchangeRes] = await Promise.all([
      marketAPI.getSimpleStatus().catch(() => ({ data: null })),
      exchangeRateAPI.getCurrentRate().catch(() => ({ data: null }))
    ]);

    if (marketRes.data) {
      marketData.value = marketRes.data;
    }
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
}

.update-time {
  font-size: 0.75rem;
  color: #888;
}

.info-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 600px) {
  .info-cards {
    grid-template-columns: 1fr;
  }
}

.info-card {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: all 0.3s ease;
}

.info-card:hover {
  background: rgba(255, 255, 255, 0.08);
  transform: translateY(-2px);
}

/* ADR 카드 색상 */
.info-card.overheated {
  border-color: rgba(239, 68, 68, 0.5);
  background: rgba(239, 68, 68, 0.1);
}

.info-card.oversold {
  border-color: rgba(59, 130, 246, 0.5);
  background: rgba(59, 130, 246, 0.1);
}

.info-card.extreme-fear {
  border-color: rgba(147, 51, 234, 0.5);
  background: rgba(147, 51, 234, 0.1);
}

/* 환율 카드 색상 */
.info-card.sell-pressure {
  border-color: rgba(245, 158, 11, 0.5);
  background: rgba(245, 158, 11, 0.1);
}

.info-card.buy-pressure {
  border-color: rgba(16, 185, 129, 0.5);
  background: rgba(16, 185, 129, 0.1);
}

.info-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #aaa;
  font-size: 0.85rem;
  margin-bottom: 8px;
}

.icon {
  font-size: 1rem;
}

.info-value {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.main-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: #fff;
  font-family: 'Monaco', 'Consolas', monospace;
}

.change-value {
  font-size: 0.85rem;
  font-family: 'Monaco', 'Consolas', monospace;
}

.change-value.up {
  color: #ef4444;
}

.change-value.down {
  color: #3b82f6;
}

.info-status {
  margin-top: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 0.75rem;
  font-weight: 600;
  display: inline-block;
}

.info-status.danger {
  background: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

.info-status.warning {
  background: rgba(245, 158, 11, 0.2);
  color: #f59e0b;
}

.info-status.success {
  background: rgba(16, 185, 129, 0.2);
  color: #10b981;
}

.info-status.normal {
  background: rgba(163, 163, 163, 0.2);
  color: #a3a3a3;
}

.info-status.neutral {
  background: rgba(163, 163, 163, 0.2);
  color: #a3a3a3;
}

/* Loading */
.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(26, 26, 58, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
}

.spinner {
  width: 24px;
  height: 24px;
  border: 3px solid rgba(255, 255, 255, 0.1);
  border-top-color: #9f7aea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
