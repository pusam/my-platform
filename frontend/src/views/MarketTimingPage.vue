<template>
  <div class="market-timing-page">
    <div class="page-header">
      <button @click="goBack" class="back-button">← 돌아가기</button>
      <h1>시장 지표 (Market Timing)</h1>
      <p class="subtitle">ADR(등락비율) 기반 시장 상태 분석</p>
    </div>

    <!-- 시장 상태 카드 -->
    <div class="status-overview">
      <div class="status-card main-status" :class="getConditionClass(marketData?.overallCondition)">
        <div class="status-icon">
          {{ getConditionEmoji(marketData?.overallCondition) }}
        </div>
        <div class="status-content">
          <div class="status-label">종합 시장 상태</div>
          <div class="status-value">{{ marketData?.overallCondition?.emoji || '데이터 없음' }}</div>
          <div class="adr-value" v-if="marketData?.combinedAdr">
            ADR(20일): <strong>{{ formatNumber(marketData.combinedAdr, 1) }}</strong>
          </div>
        </div>
        <div class="status-date" v-if="marketData?.analysisDate">
          {{ formatDate(marketData.analysisDate) }} 기준
        </div>
      </div>
    </div>

    <!-- ADR 설명 -->
    <div class="adr-guide">
      <h3>ADR (Advance-Decline Ratio) 이란?</h3>
      <p>최근 20일간 상승 종목 수 합계 / 하락 종목 수 합계 × 100</p>
      <div class="adr-levels">
        <div class="level overheated">
          <span class="level-emoji">🔥</span>
          <span class="level-range">ADR ≥ 120</span>
          <span class="level-label">과열 - 현금 확보 필요</span>
        </div>
        <div class="level normal">
          <span class="level-emoji">☁️</span>
          <span class="level-range">80 < ADR < 120</span>
          <span class="level-label">보통 - 정상 범위</span>
        </div>
        <div class="level oversold">
          <span class="level-emoji">💧</span>
          <span class="level-range">ADR ≤ 80</span>
          <span class="level-label">침체 - 저점 매수 기회</span>
        </div>
        <div class="level extreme-fear">
          <span class="level-emoji">🥶</span>
          <span class="level-range">ADR ≤ 60</span>
          <span class="level-label">극심한 공포 - 적극 매수 검토</span>
        </div>
      </div>
    </div>

    <!-- 시장별 상세 현황 -->
    <div class="market-details" v-if="marketData">
      <div class="market-card" v-if="marketData.kospi">
        <div class="market-header">
          <h3>KOSPI</h3>
          <span class="market-condition" :class="getConditionClass(marketData.kospi.condition)">
            {{ marketData.kospi.condition?.emoji || '-' }}
          </span>
        </div>
        <div class="market-stats">
          <div class="stat-row">
            <span class="stat-label">지수</span>
            <span class="stat-value">
              {{ formatNumber(marketData.kospi.indexClose, 2) }}
              <span :class="marketData.kospi.indexChangeRate >= 0 ? 'positive' : 'negative'">
                ({{ marketData.kospi.indexChangeRate >= 0 ? '+' : '' }}{{ formatNumber(marketData.kospi.indexChangeRate, 2) }}%)
              </span>
            </span>
          </div>
          <div class="stat-row">
            <span class="stat-label">상승</span>
            <span class="stat-value rising">{{ marketData.kospi.advancingCount || 0 }}개</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">하락</span>
            <span class="stat-value falling">{{ marketData.kospi.decliningCount || 0 }}개</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">보합</span>
            <span class="stat-value">{{ marketData.kospi.unchangedCount || 0 }}개</span>
          </div>
          <div class="stat-row highlight">
            <span class="stat-label">당일 등락비</span>
            <span class="stat-value">{{ formatNumber(marketData.kospi.dailyRatio, 1) }}</span>
          </div>
          <div class="stat-row highlight">
            <span class="stat-label">ADR(20일)</span>
            <span class="stat-value" :class="getAdrClass(marketData.kospi.adr20)">
              {{ formatNumber(marketData.kospi.adr20, 1) }}
            </span>
          </div>
        </div>
      </div>

      <div class="market-card" v-if="marketData.kosdaq">
        <div class="market-header">
          <h3>KOSDAQ</h3>
          <span class="market-condition" :class="getConditionClass(marketData.kosdaq.condition)">
            {{ marketData.kosdaq.condition?.emoji || '-' }}
          </span>
        </div>
        <div class="market-stats">
          <div class="stat-row">
            <span class="stat-label">지수</span>
            <span class="stat-value">
              {{ formatNumber(marketData.kosdaq.indexClose, 2) }}
              <span :class="marketData.kosdaq.indexChangeRate >= 0 ? 'positive' : 'negative'">
                ({{ marketData.kosdaq.indexChangeRate >= 0 ? '+' : '' }}{{ formatNumber(marketData.kosdaq.indexChangeRate, 2) }}%)
              </span>
            </span>
          </div>
          <div class="stat-row">
            <span class="stat-label">상승</span>
            <span class="stat-value rising">{{ marketData.kosdaq.advancingCount || 0 }}개</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">하락</span>
            <span class="stat-value falling">{{ marketData.kosdaq.decliningCount || 0 }}개</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">보합</span>
            <span class="stat-value">{{ marketData.kosdaq.unchangedCount || 0 }}개</span>
          </div>
          <div class="stat-row highlight">
            <span class="stat-label">당일 등락비</span>
            <span class="stat-value">{{ formatNumber(marketData.kosdaq.dailyRatio, 1) }}</span>
          </div>
          <div class="stat-row highlight">
            <span class="stat-label">ADR(20일)</span>
            <span class="stat-value" :class="getAdrClass(marketData.kosdaq.adr20)">
              {{ formatNumber(marketData.kosdaq.adr20, 1) }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 진단 및 전략 -->
    <div class="diagnosis-section" v-if="marketData">
      <div class="diagnosis-card">
        <h3>📊 시장 진단</h3>
        <p>{{ marketData.diagnosis || '데이터를 수집해주세요.' }}</p>
      </div>
      <div class="strategy-card">
        <h3>💡 투자 전략 제안</h3>
        <p>{{ marketData.strategy || '데이터 수집 후 분석을 이용해주세요.' }}</p>
      </div>
    </div>

    <!-- 데이터 관리 -->
    <div class="data-management">
      <h3>데이터 관리</h3>
      <div class="management-actions">
        <button @click="collectData" :disabled="isCollecting" class="btn-collect">
          {{ isCollecting ? '수집 중...' : '📥 시장 데이터 수집' }}
        </button>
        <button @click="fetchData" :disabled="loading" class="btn-refresh">
          🔄 새로고침
        </button>
      </div>
      <p class="management-note">
        * 매일 장 마감 후(15:30 이후) 데이터를 수집하면 당일 시장 현황이 반영됩니다.
      </p>
    </div>

    <!-- 로딩 -->
    <div v-if="loading" class="loading-overlay">
      <div class="spinner"></div>
      <p>데이터 로딩 중...</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { marketAPI } from '../utils/api';

const router = useRouter();
const loading = ref(false);
const isCollecting = ref(false);
const marketData = ref(null);

const goBack = () => {
  router.push('/dashboard');
};

const fetchData = async () => {
  loading.value = true;
  try {
    const response = await marketAPI.getStatus();
    if (response.data.success) {
      marketData.value = response.data.data;
    }
  } catch (error) {
    console.error('시장 데이터 조회 실패:', error);
  } finally {
    loading.value = false;
  }
};

const collectData = async () => {
  isCollecting.value = true;
  try {
    const response = await marketAPI.collectData();
    if (response.data.success) {
      marketData.value = response.data.data;
      alert('시장 데이터 수집이 완료되었습니다.');
    } else {
      alert('수집 실패: ' + response.data.message);
    }
  } catch (error) {
    console.error('시장 데이터 수집 실패:', error);
    alert('시장 데이터 수집에 실패했습니다.');
  } finally {
    isCollecting.value = false;
  }
};

const formatNumber = (value, decimals = 0) => {
  if (value === null || value === undefined) return '-';
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
};

const formatDate = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  return date.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  });
};

const getConditionClass = (condition) => {
  if (!condition) return '';
  switch (condition) {
    case 'OVERHEATED': return 'condition-overheated';
    case 'NORMAL': return 'condition-normal';
    case 'OVERSOLD': return 'condition-oversold';
    case 'EXTREME_FEAR': return 'condition-extreme-fear';
    default: return '';
  }
};

const getConditionEmoji = (condition) => {
  if (!condition) return '❓';
  switch (condition) {
    case 'OVERHEATED': return '🔥';
    case 'NORMAL': return '☁️';
    case 'OVERSOLD': return '💧';
    case 'EXTREME_FEAR': return '🥶';
    default: return '❓';
  }
};

const getAdrClass = (adr) => {
  if (adr === null || adr === undefined) return '';
  if (adr >= 120) return 'adr-overheated';
  if (adr <= 60) return 'adr-extreme-fear';
  if (adr <= 80) return 'adr-oversold';
  return 'adr-normal';
};

onMounted(() => {
  fetchData();
});
</script>

<style scoped>
.market-timing-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 2rem;
  background: var(--bg-primary, #0f0f23);
  min-height: 100vh;
  color: var(--text-primary, #e4e4e7);
}

.page-header {
  margin-bottom: 2rem;
}

.back-button {
  background: transparent;
  border: 1px solid var(--border-color, #3f3f46);
  color: var(--text-secondary, #a1a1aa);
  padding: 0.5rem 1rem;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 1rem;
  transition: all 0.2s;
}

.back-button:hover {
  background: var(--hover-bg, #27272a);
  color: var(--text-primary, #e4e4e7);
}

.page-header h1 {
  font-size: 2rem;
  margin: 0 0 0.5rem 0;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.subtitle {
  color: var(--text-muted, #71717a);
  margin: 0;
}

/* 메인 상태 카드 */
.status-overview {
  margin-bottom: 2rem;
}

.main-status {
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  border-radius: 16px;
  padding: 2rem;
  display: flex;
  align-items: center;
  gap: 2rem;
  border: 2px solid transparent;
}

.main-status.condition-overheated {
  border-color: #ef4444;
  background: linear-gradient(135deg, #1a1a2e 0%, #2d1f1f 100%);
}

.main-status.condition-normal {
  border-color: #6b7280;
  background: linear-gradient(135deg, #1a1a2e 0%, #1f2937 100%);
}

.main-status.condition-oversold {
  border-color: #3b82f6;
  background: linear-gradient(135deg, #1a1a2e 0%, #1e293b 100%);
}

.main-status.condition-extreme-fear {
  border-color: #06b6d4;
  background: linear-gradient(135deg, #1a1a2e 0%, #0f172a 100%);
}

.status-icon {
  font-size: 4rem;
}

.status-content {
  flex: 1;
}

.status-label {
  font-size: 0.875rem;
  color: var(--text-muted, #71717a);
  margin-bottom: 0.25rem;
}

.status-value {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.adr-value {
  font-size: 1.25rem;
  color: var(--text-secondary, #a1a1aa);
}

.adr-value strong {
  color: var(--accent-color, #667eea);
  font-size: 1.5rem;
}

.status-date {
  color: var(--text-muted, #71717a);
  font-size: 0.875rem;
}

/* ADR 가이드 */
.adr-guide {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  margin-bottom: 2rem;
  border: 1px solid var(--border-color, #27272a);
}

.adr-guide h3 {
  margin: 0 0 0.5rem 0;
  color: var(--text-primary, #e4e4e7);
}

.adr-guide > p {
  color: var(--text-muted, #71717a);
  margin: 0 0 1rem 0;
  font-size: 0.9rem;
}

.adr-levels {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.level {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  border-radius: 8px;
  background: var(--bg-secondary, #27272a);
}

.level-emoji {
  font-size: 1.5rem;
}

.level-range {
  font-weight: 600;
  color: var(--text-primary, #e4e4e7);
  font-size: 0.85rem;
}

.level-label {
  font-size: 0.8rem;
  color: var(--text-muted, #71717a);
}

.level.overheated { border-left: 3px solid #ef4444; }
.level.normal { border-left: 3px solid #6b7280; }
.level.oversold { border-left: 3px solid #3b82f6; }
.level.extreme-fear { border-left: 3px solid #06b6d4; }

/* 시장별 상세 */
.market-details {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.market-card {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  border: 1px solid var(--border-color, #27272a);
}

.market-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border-color, #27272a);
}

.market-header h3 {
  margin: 0;
  font-size: 1.25rem;
}

.market-condition {
  padding: 0.25rem 0.75rem;
  border-radius: 20px;
  font-size: 0.8rem;
}

.market-stats {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-row.highlight {
  padding: 0.5rem;
  background: var(--bg-secondary, #27272a);
  border-radius: 6px;
  margin-top: 0.5rem;
}

.stat-label {
  color: var(--text-muted, #71717a);
}

.stat-value {
  font-weight: 600;
}

.stat-value.rising { color: #ef4444; }
.stat-value.falling { color: #3b82f6; }
.stat-value .positive { color: #ef4444; }
.stat-value .negative { color: #3b82f6; }

.adr-overheated { color: #ef4444; }
.adr-normal { color: #6b7280; }
.adr-oversold { color: #3b82f6; }
.adr-extreme-fear { color: #06b6d4; }

/* 진단 섹션 */
.diagnosis-section {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.diagnosis-card,
.strategy-card {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  border: 1px solid var(--border-color, #27272a);
}

.diagnosis-card h3,
.strategy-card h3 {
  margin: 0 0 1rem 0;
  font-size: 1.1rem;
}

.diagnosis-card p,
.strategy-card p {
  margin: 0;
  color: var(--text-secondary, #a1a1aa);
  line-height: 1.6;
}

/* 데이터 관리 */
.data-management {
  background: var(--card-bg, #18181b);
  border-radius: 12px;
  padding: 1.5rem;
  border: 1px solid var(--border-color, #27272a);
}

.data-management h3 {
  margin: 0 0 1rem 0;
}

.management-actions {
  display: flex;
  gap: 1rem;
  margin-bottom: 1rem;
}

.btn-collect,
.btn-refresh {
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  border: none;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.2s;
}

.btn-collect {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-collect:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.btn-collect:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-refresh {
  background: var(--bg-secondary, #27272a);
  color: var(--text-primary, #e4e4e7);
  border: 1px solid var(--border-color, #3f3f46);
}

.btn-refresh:hover:not(:disabled) {
  background: var(--hover-bg, #3f3f46);
}

.management-note {
  color: var(--text-muted, #71717a);
  font-size: 0.85rem;
  margin: 0;
}

/* 로딩 */
.loading-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid var(--border-color, #3f3f46);
  border-top-color: var(--accent-color, #667eea);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 반응형 */
@media (max-width: 768px) {
  .market-timing-page {
    padding: 1rem;
  }

  .main-status {
    flex-direction: column;
    text-align: center;
    gap: 1rem;
    padding: 1.5rem;
  }

  .status-icon {
    font-size: 3rem;
  }

  .adr-levels {
    grid-template-columns: 1fr;
  }

  .level {
    flex-direction: column;
    text-align: center;
    gap: 0.5rem;
  }

  .management-actions {
    flex-direction: column;
  }
}
</style>
