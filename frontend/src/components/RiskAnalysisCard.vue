<template>
  <div class="risk-analysis-card" :class="{ danger: isDanger, warning: isWarning }">
    <div class="card-header">
      <h3>
        <span class="header-icon">🛡️</span>
        AI 리스크 분석
      </h3>
      <div class="risk-badge" :class="badgeClass">
        {{ badgeText }}
      </div>
    </div>

    <!-- 로딩 상태 -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <span>리스크 분석 중...</span>
    </div>

    <!-- 분석 결과 -->
    <div v-else-if="riskData" class="analysis-content">
      <!-- 위험도 게이지 -->
      <div class="risk-gauge-section">
        <div class="gauge-label">
          <span>위험도 점수</span>
          <span class="score" :class="scoreClass">{{ riskData.riskScore }}점</span>
        </div>
        <div class="gauge-container">
          <div class="gauge-track">
            <div
              class="gauge-fill"
              :style="{ width: riskData.riskScore + '%' }"
              :class="gaugeClass"
            ></div>
            <div class="gauge-markers">
              <div class="marker" style="left: 30%"><span>30</span></div>
              <div class="marker" style="left: 70%"><span>70</span></div>
            </div>
          </div>
          <div class="gauge-labels">
            <span class="safe">안전</span>
            <span class="warning">주의</span>
            <span class="danger">위험</span>
          </div>
        </div>
      </div>

      <!-- 3단 상태 표시 -->
      <div class="status-grid">
        <!-- 공시(DART) 상태 -->
        <div class="status-item" :class="{ alert: hasDangerousDisclosure }">
          <div class="status-icon">✅</div>
          <div class="status-content">
            <span class="status-label">공시 (DART)</span>
            <span class="status-value" :class="{ danger: hasDangerousDisclosure }">
              {{ disclosureStatus }}
            </span>
          </div>
        </div>

        <!-- 뉴스 상태 -->
        <div class="status-item" :class="{ alert: hasNegativeNews }">
          <div class="status-icon">📰</div>
          <div class="status-content">
            <span class="status-label">뉴스</span>
            <span class="status-value" :class="{ danger: hasNegativeNews, positive: hasPositiveNews }">
              {{ newsStatus }}
            </span>
          </div>
        </div>

        <!-- AI 소견 -->
        <div class="status-item ai-summary">
          <div class="status-icon">🤖</div>
          <div class="status-content">
            <span class="status-label">AI 소견</span>
            <span class="status-value ai-reason">
              {{ riskData.reason || '분석 결과 없음' }}
            </span>
          </div>
        </div>
      </div>

      <!-- 위험 공시 목록 (있는 경우) -->
      <div v-if="riskData.dangerousDisclosures?.length" class="disclosure-list">
        <h4>⚠️ 위험 공시 발견</h4>
        <ul>
          <li v-for="(d, idx) in riskData.dangerousDisclosures.slice(0, 3)" :key="idx">
            <span class="disclosure-date">{{ formatDate(d.rceptDt) }}</span>
            <span class="disclosure-title">{{ d.reportNm }}</span>
            <span v-if="d.matchedKeyword" class="matched-keyword">{{ d.matchedKeyword }}</span>
          </li>
        </ul>
      </div>

      <!-- 관련 뉴스 (접힘) -->
      <div v-if="riskData.relatedNews?.length" class="news-section">
        <button @click="showNews = !showNews" class="news-toggle">
          <span>관련 뉴스 {{ riskData.relatedNews.length }}건</span>
          <span class="toggle-icon">{{ showNews ? '▲' : '▼' }}</span>
        </button>
        <ul v-if="showNews" class="news-list">
          <li v-for="(news, idx) in riskData.relatedNews.slice(0, 5)" :key="idx">
            <a :href="news.link" target="_blank" rel="noopener">
              {{ news.title }}
            </a>
            <span class="news-date">{{ formatNewsDate(news.pubDate) }}</span>
          </li>
        </ul>
      </div>

      <!-- 분석 시각 -->
      <div class="analysis-time">
        분석 시각: {{ formatDateTime(riskData.analyzedAt) }}
      </div>
    </div>

    <!-- API 미설정 상태 -->
    <div v-else-if="apiNotConfigured" class="api-not-configured">
      <div class="info-icon">ℹ️</div>
      <p>DART/Naver API가 설정되지 않았습니다.</p>
      <p class="sub">application.yml에 API 키를 설정해주세요.</p>
    </div>

    <!-- 에러 상태 -->
    <div v-else-if="error" class="error-state">
      <div class="error-icon">⚠️</div>
      <p>{{ error }}</p>
      <button @click="$emit('retry')" class="retry-button">다시 시도</button>
    </div>

    <!-- 초기 상태 -->
    <div v-else class="initial-state">
      <p>종목을 검색하면 리스크 분석이 자동으로 수행됩니다.</p>
    </div>

    <!-- 매수 경고 모달 -->
    <Teleport to="body">
      <div v-if="showBuyWarningModal" class="modal-overlay" @click.self="closeBuyWarning">
        <div class="buy-warning-modal">
          <div class="modal-header danger">
            <span class="warning-icon">🚨</span>
            <h3>치명적인 악재 감지</h3>
          </div>
          <div class="modal-body">
            <p class="warning-text">
              현재 이 종목의 리스크 점수는 <strong>{{ riskData?.riskScore }}점</strong>입니다.
            </p>
            <p class="reason-text">{{ riskData?.reason }}</p>
            <p class="confirm-text">그래도 매수를 진행하시겠습니까?</p>
          </div>
          <div class="modal-actions">
            <button @click="closeBuyWarning" class="cancel-button">취소</button>
            <button @click="confirmBuy" class="confirm-button danger">위험 감수하고 매수</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, watch, defineEmits, defineProps } from 'vue';

const props = defineProps({
  stockName: {
    type: String,
    default: ''
  },
  riskData: {
    type: Object,
    default: null
  },
  loading: {
    type: Boolean,
    default: false
  },
  error: {
    type: String,
    default: ''
  },
  apiNotConfigured: {
    type: Boolean,
    default: false
  }
});

const emit = defineEmits(['retry', 'buy-confirmed', 'buy-cancelled']);

const showNews = ref(false);
const showBuyWarningModal = ref(false);

// 계산된 속성들
const isDanger = computed(() => props.riskData?.riskScore >= 70);
const isWarning = computed(() => props.riskData?.riskScore >= 30 && props.riskData?.riskScore < 70);

const badgeClass = computed(() => {
  if (!props.riskData) return '';
  const score = props.riskData.riskScore;
  if (score >= 70) return 'danger blink';
  if (score >= 30) return 'warning';
  return 'safe';
});

const badgeText = computed(() => {
  if (!props.riskData) return '';
  const score = props.riskData.riskScore;
  if (score >= 70) return '위험';
  if (score >= 30) return '주의';
  return '안전';
});

const scoreClass = computed(() => {
  if (!props.riskData) return '';
  const score = props.riskData.riskScore;
  if (score >= 70) return 'danger';
  if (score >= 30) return 'warning';
  return 'safe';
});

const gaugeClass = computed(() => {
  if (!props.riskData) return '';
  const score = props.riskData.riskScore;
  if (score >= 70) return 'danger';
  if (score >= 30) return 'warning';
  return 'safe';
});

const hasDangerousDisclosure = computed(() => {
  return props.riskData?.dangerousDisclosures?.length > 0;
});

const disclosureStatus = computed(() => {
  if (!props.riskData) return '-';
  if (hasDangerousDisclosure.value) {
    const keywords = props.riskData.dangerousDisclosures
      .map(d => d.matchedKeyword)
      .filter(Boolean)
      .slice(0, 2)
      .join(', ');
    return keywords ? `${keywords} 발견` : '위험 공시 발견';
  }
  return 'Clean';
});

const hasNegativeNews = computed(() => {
  if (!props.riskData?.relatedNews) return false;
  const riskKeywords = ['악재', '하락', '폭락', '적자', '손실', '검찰', '횡령', '배임'];
  return props.riskData.relatedNews.some(news =>
    riskKeywords.some(kw => news.title?.includes(kw) || news.description?.includes(kw))
  );
});

const hasPositiveNews = computed(() => {
  if (!props.riskData?.relatedNews || hasNegativeNews.value) return false;
  const positiveKeywords = ['호재', '상승', '급등', '흑자', '실적개선'];
  return props.riskData.relatedNews.some(news =>
    positiveKeywords.some(kw => news.title?.includes(kw) || news.description?.includes(kw))
  );
});

const newsStatus = computed(() => {
  if (!props.riskData?.relatedNews?.length) return '뉴스 없음';
  if (hasNegativeNews.value) return '악재 발생';
  if (hasPositiveNews.value) return '호재 우세';
  return '중립';
});

// 메서드
const formatDate = (dateStr) => {
  if (!dateStr) return '';
  // YYYYMMDD 형식
  if (dateStr.length === 8) {
    return `${dateStr.slice(0, 4)}.${dateStr.slice(4, 6)}.${dateStr.slice(6, 8)}`;
  }
  return dateStr;
};

const formatNewsDate = (dateStr) => {
  if (!dateStr) return '';
  try {
    const date = new Date(dateStr);
    return date.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
  } catch {
    return dateStr;
  }
};

const formatDateTime = (dateTimeStr) => {
  if (!dateTimeStr) return '-';
  try {
    const date = new Date(dateTimeStr);
    return date.toLocaleString('ko-KR', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  } catch {
    return dateTimeStr;
  }
};

// 매수 경고 모달 관련
const showBuyWarning = () => {
  if (props.riskData?.riskScore >= 80) {
    showBuyWarningModal.value = true;
    return true; // 모달 표시됨
  }
  return false; // 모달 표시 안함 (바로 매수 가능)
};

const closeBuyWarning = () => {
  showBuyWarningModal.value = false;
  emit('buy-cancelled');
};

const confirmBuy = () => {
  showBuyWarningModal.value = false;
  emit('buy-confirmed');
};

// 외부에서 호출 가능하도록 expose
defineExpose({
  showBuyWarning,
  isDanger,
  riskScore: computed(() => props.riskData?.riskScore || 0)
});
</script>

<style scoped>
.risk-analysis-card {
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border-radius: 16px;
  padding: 24px;
  border: 1px solid #2a2a4a;
  transition: all 0.3s ease;
}

.risk-analysis-card.danger {
  border-color: #ef4444;
  box-shadow: 0 0 30px rgba(239, 68, 68, 0.2);
}

.risk-analysis-card.warning {
  border-color: #f59e0b;
  box-shadow: 0 0 20px rgba(245, 158, 11, 0.15);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.card-header h3 {
  margin: 0;
  color: #fff;
  font-size: 1.25rem;
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon {
  font-size: 1.4rem;
}

/* 리스크 뱃지 */
.risk-badge {
  padding: 6px 16px;
  border-radius: 20px;
  font-size: 0.9rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.risk-badge.safe {
  background: linear-gradient(135deg, #22c55e 0%, #16a34a 100%);
  color: #fff;
}

.risk-badge.warning {
  background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
  color: #000;
}

.risk-badge.danger {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  color: #fff;
}

.risk-badge.blink {
  animation: blink-danger 1s ease-in-out infinite;
}

@keyframes blink-danger {
  0%, 100% {
    opacity: 1;
    box-shadow: 0 0 20px rgba(239, 68, 68, 0.8);
  }
  50% {
    opacity: 0.7;
    box-shadow: 0 0 30px rgba(239, 68, 68, 1);
  }
}

/* 로딩 상태 */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 40px;
  color: #888;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #2a2a4a;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 게이지 섹션 */
.risk-gauge-section {
  margin-bottom: 24px;
}

.gauge-label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
  color: #ccc;
  font-size: 0.95rem;
}

.gauge-label .score {
  font-size: 1.5rem;
  font-weight: 700;
}

.gauge-label .score.safe { color: #22c55e; }
.gauge-label .score.warning { color: #f59e0b; }
.gauge-label .score.danger { color: #ef4444; }

.gauge-container {
  position: relative;
}

.gauge-track {
  height: 16px;
  background: linear-gradient(90deg,
    #22c55e 0%,
    #22c55e 30%,
    #f59e0b 30%,
    #f59e0b 70%,
    #ef4444 70%,
    #ef4444 100%
  );
  border-radius: 8px;
  overflow: hidden;
  position: relative;
  opacity: 0.3;
}

.gauge-fill {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  border-radius: 8px;
  transition: width 0.8s ease-out;
}

.gauge-fill.safe {
  background: linear-gradient(90deg, #22c55e, #16a34a);
  box-shadow: 0 0 15px rgba(34, 197, 94, 0.5);
}

.gauge-fill.warning {
  background: linear-gradient(90deg, #22c55e 0%, #f59e0b 70%);
  box-shadow: 0 0 15px rgba(245, 158, 11, 0.5);
}

.gauge-fill.danger {
  background: linear-gradient(90deg, #22c55e 0%, #f59e0b 30%, #ef4444 100%);
  box-shadow: 0 0 20px rgba(239, 68, 68, 0.6);
}

.gauge-markers {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 100%;
}

.gauge-markers .marker {
  position: absolute;
  top: -20px;
  transform: translateX(-50%);
  font-size: 0.7rem;
  color: #666;
}

.gauge-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 0.75rem;
  padding: 0 4px;
}

.gauge-labels .safe { color: #22c55e; }
.gauge-labels .warning { color: #f59e0b; }
.gauge-labels .danger { color: #ef4444; }

/* 상태 그리드 */
.status-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.status-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  background: #0f0f23;
  border-radius: 10px;
  border: 1px solid #2a2a4a;
  transition: all 0.3s;
}

.status-item.alert {
  border-color: #ef4444;
  background: rgba(239, 68, 68, 0.1);
}

.status-icon {
  font-size: 1.3rem;
  flex-shrink: 0;
}

.status-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.status-label {
  font-size: 0.8rem;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.status-value {
  color: #fff;
  font-weight: 600;
  font-size: 0.95rem;
}

.status-value.danger {
  color: #ef4444;
}

.status-value.positive {
  color: #22c55e;
}

.status-item.ai-summary {
  background: linear-gradient(135deg, #1a1a3a 0%, #252550 100%);
}

.ai-reason {
  line-height: 1.5;
  font-size: 0.9rem !important;
}

/* 위험 공시 목록 */
.disclosure-list {
  margin-bottom: 16px;
  padding: 16px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 10px;
}

.disclosure-list h4 {
  margin: 0 0 12px 0;
  color: #ef4444;
  font-size: 0.95rem;
}

.disclosure-list ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.disclosure-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 0.85rem;
  color: #ccc;
}

.disclosure-date {
  color: #888;
  font-size: 0.8rem;
  flex-shrink: 0;
}

.disclosure-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.matched-keyword {
  padding: 2px 8px;
  background: #ef4444;
  color: #fff;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 600;
  flex-shrink: 0;
}

/* 뉴스 섹션 */
.news-section {
  margin-bottom: 16px;
}

.news-toggle {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #0f0f23;
  border: 1px solid #2a2a4a;
  border-radius: 8px;
  color: #ccc;
  font-size: 0.9rem;
  cursor: pointer;
  transition: all 0.3s;
}

.news-toggle:hover {
  border-color: #4a4a8a;
}

.toggle-icon {
  font-size: 0.7rem;
}

.news-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 12px;
  background: #0f0f23;
  border-radius: 0 0 8px 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.news-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.news-list a {
  color: #88a4ff;
  text-decoration: none;
  font-size: 0.85rem;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.news-list a:hover {
  text-decoration: underline;
}

.news-date {
  color: #666;
  font-size: 0.75rem;
  flex-shrink: 0;
}

/* 분석 시각 */
.analysis-time {
  text-align: right;
  font-size: 0.75rem;
  color: #666;
  margin-top: 16px;
}

/* API 미설정 / 에러 / 초기 상태 */
.api-not-configured,
.error-state,
.initial-state {
  text-align: center;
  padding: 30px;
  color: #888;
}

.info-icon,
.error-icon {
  font-size: 2rem;
  margin-bottom: 12px;
}

.api-not-configured .sub {
  font-size: 0.85rem;
  color: #666;
  margin-top: 8px;
}

.retry-button {
  margin-top: 16px;
  padding: 10px 24px;
  background: #4a4a8a;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s;
}

.retry-button:hover {
  background: #5a5a9a;
}

/* 매수 경고 모달 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.buy-warning-modal {
  background: linear-gradient(135deg, #1a1a3a 0%, #0f0f23 100%);
  border: 2px solid #ef4444;
  border-radius: 20px;
  max-width: 450px;
  width: 90%;
  overflow: hidden;
  animation: slideUp 0.3s ease;
  box-shadow: 0 0 60px rgba(239, 68, 68, 0.3);
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.modal-header {
  padding: 20px 24px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.modal-header.danger {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
}

.warning-icon {
  font-size: 1.8rem;
}

.modal-header h3 {
  margin: 0;
  color: #fff;
  font-size: 1.3rem;
}

.modal-body {
  padding: 24px;
}

.warning-text {
  font-size: 1rem;
  color: #ccc;
  margin: 0 0 16px;
}

.warning-text strong {
  color: #ef4444;
  font-size: 1.2rem;
}

.reason-text {
  padding: 16px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 10px;
  color: #ff8a8a;
  font-size: 0.95rem;
  margin: 0 0 20px;
  line-height: 1.5;
}

.confirm-text {
  color: #fff;
  font-size: 1rem;
  font-weight: 600;
  margin: 0;
}

.modal-actions {
  display: flex;
  gap: 12px;
  padding: 0 24px 24px;
}

.cancel-button,
.confirm-button {
  flex: 1;
  padding: 14px;
  border: none;
  border-radius: 10px;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.cancel-button {
  background: #2a2a4a;
  color: #ccc;
}

.cancel-button:hover {
  background: #3a3a5a;
}

.confirm-button.danger {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  color: #fff;
}

.confirm-button.danger:hover {
  box-shadow: 0 0 20px rgba(239, 68, 68, 0.5);
  transform: translateY(-2px);
}

/* 반응형 */
@media (max-width: 768px) {
  .risk-analysis-card {
    padding: 16px;
  }

  .card-header {
    flex-direction: column;
    gap: 12px;
    align-items: flex-start;
  }

  .disclosure-list li {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .disclosure-title {
    white-space: normal;
  }
}
</style>
