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

const marketData = ref(null);
const exchangeData = ref(null);
const loading = ref(true);
const lastUpdated = ref('');
let refreshInterval = null;

// ★ 폭락 감지: KOSPI/KOSDAQ -3% 이하면 ADR 무시하고 강제 폭락
const isCrash = computed(() => {
  const d = marketData.value;
  if (!d) return false;
  const kospiRate = d.kospiChange ?? d.kospiChangeRate ?? d.kospi?.indexChangeRate ?? null;
  const kosdaqRate = d.kosdaqChange ?? d.kosdaqChangeRate ?? d.kosdaq?.indexChangeRate ?? null;
  if (kospiRate !== null && Number(kospiRate) <= -3) return true;
  if (kosdaqRate !== null && Number(kosdaqRate) <= -3) return true;
  // 백엔드 diagnosis 문자열 체크
  const diag = d.diagnosis || d.marketStatus || '';
  return diag.includes('폭락') || diag.includes('패닉') || diag.includes('CRASH');
});

// 시장 상태 계산 (폭락 override 포함)
const marketStatusClass = computed(() => {
  if (isCrash.value) return 'crash';
  if (!marketData.value?.combinedAdr) return '';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return 'overheated';
  if (adr >= 100) return 'bullish';
  if (adr >= 80) return 'normal';
  if (adr >= 60) return 'bearish';
  return 'extreme-fear';
});

const marketStatusIcon = computed(() => {
  if (isCrash.value) return '🚨';
  if (!marketData.value?.combinedAdr) return '📊';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return '🔥';
  if (adr >= 100) return '📈';
  if (adr >= 80) return '➡️';
  if (adr >= 60) return '📉';
  return '💎';
});

const marketStatusTitle = computed(() => {
  if (isCrash.value) return '폭락장';
  if (!marketData.value?.combinedAdr) return '데이터 없음';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return '과열';
  if (adr >= 100) return '강세';
  if (adr >= 80) return '보합';
  if (adr >= 60) return '약세';
  return '침체';
});

const marketStatusDescription = computed(() => {
  if (isCrash.value) return '관망 및 리스크 관리 필수';
  if (!marketData.value?.combinedAdr) return '시장 데이터를 불러오지 못했습니다.';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return '추격 매수 주의, 익절 고려';
  if (adr >= 100) return '상승 추세, 눌림목 매수 유효';
  if (adr >= 80) return '방향성 탐색 중';
  if (adr >= 60) return '하락 추세, 반등 대기';
  return '저점 매수 기회 탐색';
});

const adrBadgeClass = computed(() => {
  if (isCrash.value) return 'badge-crash';
  if (!marketData.value?.combinedAdr) return '';
  const adr = marketData.value.combinedAdr;
  if (adr >= 120) return 'badge-danger';
  if (adr >= 100) return 'badge-success';
  if (adr >= 80) return 'badge-neutral';
  if (adr >= 60) return 'badge-warning';
  return 'badge-info';
});

// 유틸리티
const formatNumber = (num, decimals = 2) => {
  if (num == null) return '-';
  return num.toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
};

const formatChange = (change) => {
  if (change == null) return '-';
  const sign = change >= 0 ? '+' : '';
  return `${sign}${change.toFixed(2)}%`;
};

const getChangeClass = (change, inverse = false) => {
  if (change == null) return '';
  // inverse: 환율은 상승이 부정적
  if (inverse) {
    return change >= 0 ? 'negative' : 'positive';
  }
  return change >= 0 ? 'positive' : 'negative';
};

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
  background: rgba(255, 255, 255, 0.05);
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
  background: rgba(255, 255, 255, 0.05);
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
