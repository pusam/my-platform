<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>리스크 분석</h1>
        <div class="header-actions">
          <button @click="goBack" class="btn btn-secondary">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="15 18 9 12 15 6"/>
            </svg>
            대시보드
          </button>
        </div>
      </header>

      <!-- 검색 섹션 -->
      <section class="search-section">
        <div class="search-box">
          <div class="search-input-wrapper">
            <svg class="search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              v-model="stockName"
              type="text"
              placeholder="종목명을 입력하세요 (예: 삼성전자)"
              @keyup.enter="analyzeRisk"
              :disabled="loading"
            />
          </div>
          <button @click="analyzeRisk" class="btn btn-primary" :disabled="loading || !stockName.trim()">
            <span v-if="loading" class="spinner"></span>
            <span v-else>분석하기</span>
          </button>
        </div>
        <p class="search-hint">DART 공시, 네이버 뉴스, AI 분석을 종합하여 투자 리스크를 분석합니다.</p>
      </section>

      <!-- 로딩 상태 -->
      <section v-if="loading" class="loading-section">
        <div class="loading-content">
          <div class="loading-spinner"></div>
          <p>{{ stockName }} 종목의 리스크를 분석하고 있습니다...</p>
          <div class="loading-steps">
            <div class="step" :class="{ active: loadingStep >= 1 }">
              <span class="step-icon">1</span>
              <span>DART 공시 확인</span>
            </div>
            <div class="step" :class="{ active: loadingStep >= 2 }">
              <span class="step-icon">2</span>
              <span>뉴스 검색</span>
            </div>
            <div class="step" :class="{ active: loadingStep >= 3 }">
              <span class="step-icon">3</span>
              <span>AI 분석</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 에러 상태 -->
      <section v-else-if="error" class="error-section">
        <div class="error-content">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <h3>분석 중 오류가 발생했습니다</h3>
          <p>{{ error }}</p>
          <button @click="analyzeRisk" class="btn btn-primary">다시 시도</button>
        </div>
      </section>

      <!-- 결과 섹션 -->
      <section v-else-if="result" class="result-section">
        <!-- 리스크 게이지 카드 -->
        <div class="risk-gauge-card" :class="statusClass">
          <div class="gauge-header">
            <h2>{{ result.stockName }}</h2>
            <span class="status-badge" :class="statusClass">
              {{ statusText }}
            </span>
          </div>

          <div class="gauge-container">
            <div class="gauge">
              <svg viewBox="0 0 200 120" class="gauge-svg">
                <!-- 배경 아크 -->
                <path
                  d="M 20 100 A 80 80 0 0 1 180 100"
                  fill="none"
                  stroke="#e5e7eb"
                  stroke-width="16"
                  stroke-linecap="round"
                />
                <!-- 컬러 아크 -->
                <path
                  d="M 20 100 A 80 80 0 0 1 180 100"
                  fill="none"
                  :stroke="gaugeGradientUrl"
                  stroke-width="16"
                  stroke-linecap="round"
                  :stroke-dasharray="gaugeArcLength"
                  :stroke-dashoffset="gaugeDashOffset"
                  class="gauge-arc"
                />
                <defs>
                  <linearGradient id="gaugeGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stop-color="#22c55e" />
                    <stop offset="50%" stop-color="#eab308" />
                    <stop offset="100%" stop-color="#ef4444" />
                  </linearGradient>
                </defs>
              </svg>
              <div class="gauge-value">
                <span class="score">{{ result.riskScore }}</span>
                <span class="label">/100</span>
              </div>
            </div>
            <div class="gauge-labels">
              <span class="safe">안전</span>
              <span class="warning">주의</span>
              <span class="danger">위험</span>
            </div>
          </div>

          <div class="risk-reason">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="16" x2="12" y2="12"/>
              <line x1="12" y1="8" x2="12.01" y2="8"/>
            </svg>
            <span>{{ result.reason || '특별한 위험 요소가 발견되지 않았습니다.' }}</span>
          </div>

          <!-- 매수 불가 경고 -->
          <div v-if="result.status === 'DANGER'" class="danger-warning">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            <div>
              <strong>매수 금지</strong>
              <p>이 종목은 현재 리스크가 높아 매수를 권장하지 않습니다.</p>
            </div>
          </div>
        </div>

        <!-- 3열 그리드: DART / 뉴스 / AI 분석 -->
        <div class="analysis-grid">
          <!-- DART 공시 -->
          <div class="analysis-card dart-card">
            <div class="card-header">
              <div class="header-icon dart">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                  <polyline points="14,2 14,8 20,8"/>
                  <line x1="16" y1="13" x2="8" y2="13"/>
                  <line x1="16" y1="17" x2="8" y2="17"/>
                </svg>
              </div>
              <h3>DART 공시</h3>
              <span class="badge" :class="dartStatusClass">
                {{ dartStatusText }}
              </span>
            </div>
            <div class="card-body">
              <div v-if="result.dangerousDisclosures && result.dangerousDisclosures.length > 0" class="disclosure-list">
                <div
                  v-for="(disclosure, index) in result.dangerousDisclosures"
                  :key="index"
                  class="disclosure-item"
                  :class="{ dangerous: disclosure.isDangerous }"
                >
                  <div class="disclosure-header">
                    <span class="disclosure-date">{{ disclosure.rceptDt }}</span>
                    <span v-if="disclosure.isDangerous" class="danger-tag">
                      {{ disclosure.matchedKeyword }}
                    </span>
                  </div>
                  <div class="disclosure-title">{{ disclosure.reportNm }}</div>
                  <div class="disclosure-corp">{{ disclosure.corpName }}</div>
                </div>
              </div>
              <div v-else class="empty-state">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2">
                  <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
                  <polyline points="22 4 12 14.01 9 11.01"/>
                </svg>
                <p>위험 공시가 발견되지 않았습니다</p>
              </div>
            </div>
          </div>

          <!-- 관련 뉴스 -->
          <div class="analysis-card news-card">
            <div class="card-header">
              <div class="header-icon news">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"/>
                  <path d="M7 7h10M7 11h10M7 15h7"/>
                </svg>
              </div>
              <h3>관련 뉴스</h3>
              <span class="badge neutral">
                {{ result.relatedNews?.length || 0 }}건
              </span>
            </div>
            <div class="card-body">
              <div v-if="result.relatedNews && result.relatedNews.length > 0" class="news-list">
                <a
                  v-for="(news, index) in result.relatedNews.slice(0, 5)"
                  :key="index"
                  :href="news.link || news.originalLink"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="news-item"
                >
                  <div class="news-title">{{ news.title }}</div>
                  <div class="news-date">{{ news.pubDate }}</div>
                </a>
              </div>
              <div v-else class="empty-state">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2">
                  <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"/>
                  <path d="M7 7h10M7 11h10M7 15h7"/>
                </svg>
                <p>관련 뉴스가 없습니다</p>
              </div>
            </div>
          </div>

          <!-- AI 분석 -->
          <div class="analysis-card ai-card">
            <div class="card-header">
              <div class="header-icon ai">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12 2a2 2 0 012 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 017 7h1a1 1 0 011 1v3a1 1 0 01-1 1h-1v1a2 2 0 01-2 2H5a2 2 0 01-2-2v-1H2a1 1 0 01-1-1v-3a1 1 0 011-1h1a7 7 0 017-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 012-2z"/>
                  <circle cx="8.5" cy="14.5" r="1.5"/>
                  <circle cx="15.5" cy="14.5" r="1.5"/>
                </svg>
              </div>
              <h3>AI 분석</h3>
              <span class="badge ai">AI</span>
            </div>
            <div class="card-body">
              <div v-if="result.aiAnalysis" class="ai-analysis-content">
                <p>{{ result.aiAnalysis }}</p>
              </div>
              <div v-else class="empty-state">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2">
                  <path d="M12 2a2 2 0 012 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 017 7h1a1 1 0 011 1v3a1 1 0 01-1 1h-1v1a2 2 0 01-2 2H5a2 2 0 01-2-2v-1H2a1 1 0 01-1-1v-3a1 1 0 011-1h1a7 7 0 017-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 012-2z"/>
                </svg>
                <p>AI 분석 결과가 없습니다</p>
              </div>
            </div>
          </div>
        </div>

        <!-- 분석 시각 -->
        <div class="analysis-footer">
          <span>분석 시각: {{ formatDate(result.analyzedAt) }}</span>
        </div>
      </section>

      <!-- 초기 상태 -->
      <section v-else class="empty-section">
        <div class="empty-content">
          <svg width="80" height="80" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.5">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            <path d="M12 8v4"/>
            <path d="M12 16h.01"/>
          </svg>
          <h3>종목 리스크를 분석해보세요</h3>
          <p>위에 종목명을 입력하면 DART 공시, 뉴스, AI 분석을 통해<br/>투자 리스크를 종합적으로 분석합니다.</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import { riskAPI } from '../utils/api';

export default {
  name: 'RiskAnalysisPage',
  data() {
    return {
      stockName: '',
      loading: false,
      loadingStep: 0,
      error: null,
      result: null
    };
  },
  computed: {
    statusClass() {
      if (!this.result) return '';
      switch (this.result.status) {
        case 'SAFE': return 'safe';
        case 'WARNING': return 'warning';
        case 'DANGER': return 'danger';
        default: return '';
      }
    },
    statusText() {
      if (!this.result) return '';
      switch (this.result.status) {
        case 'SAFE': return '안전';
        case 'WARNING': return '주의';
        case 'DANGER': return '위험';
        default: return this.result.status;
      }
    },
    dartStatusClass() {
      if (!this.result?.dangerousDisclosures) return 'neutral';
      const hasDangerous = this.result.dangerousDisclosures.some(d => d.isDangerous);
      return hasDangerous ? 'danger' : 'safe';
    },
    dartStatusText() {
      if (!this.result?.dangerousDisclosures) return '0건';
      const dangerCount = this.result.dangerousDisclosures.filter(d => d.isDangerous).length;
      if (dangerCount > 0) return `위험 ${dangerCount}건`;
      return this.result.dangerousDisclosures.length > 0
        ? `${this.result.dangerousDisclosures.length}건`
        : '정상';
    },
    gaugeGradientUrl() {
      return 'url(#gaugeGradient)';
    },
    gaugeArcLength() {
      return 251.2; // 반원의 둘레 (π * 80)
    },
    gaugeDashOffset() {
      if (!this.result) return this.gaugeArcLength;
      const progress = this.result.riskScore / 100;
      return this.gaugeArcLength * (1 - progress);
    }
  },
  methods: {
    goBack() {
      this.$router.push('/user');
    },
    async analyzeRisk() {
      if (!this.stockName.trim()) return;

      this.loading = true;
      this.loadingStep = 0;
      this.error = null;
      this.result = null;

      // 로딩 단계 시뮬레이션
      const stepInterval = setInterval(() => {
        if (this.loadingStep < 3) {
          this.loadingStep++;
        }
      }, 1500);

      try {
        const response = await riskAPI.checkRisk(this.stockName.trim());

        if (response.data.success) {
          this.result = response.data.data;
        } else {
          this.error = response.data.message || '분석에 실패했습니다.';
        }
      } catch (err) {
        console.error('리스크 분석 실패:', err);
        this.error = err.response?.data?.message || '서버 오류가 발생했습니다.';
      } finally {
        clearInterval(stepInterval);
        this.loading = false;
        this.loadingStep = 3;
      }
    },
    formatDate(dateStr) {
      if (!dateStr) return '-';
      const date = new Date(dateStr);
      return date.toLocaleString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      });
    }
  }
};
</script>

<style scoped>
/* 검색 섹션 */
.search-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 32px;
  margin-bottom: 24px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.search-box {
  display: flex;
  gap: 12px;
}

.search-input-wrapper {
  flex: 1;
  position: relative;
}

.search-icon {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  color: #9ca3af;
}

.search-input-wrapper input {
  width: 100%;
  padding: 16px 16px 16px 48px;
  font-size: 16px;
  border: 2px solid #e5e7eb;
  border-radius: 12px;
  outline: none;
  transition: all 0.3s ease;
}

.search-input-wrapper input:focus {
  border-color: #667eea;
  box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.1);
}

.search-hint {
  margin: 12px 0 0;
  font-size: 13px;
  color: #6b7280;
}

/* 버튼 스타일 */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  font-size: 15px;
  font-weight: 600;
  border: none;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.3);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-secondary {
  background: #f3f4f6;
  color: #374151;
}

.btn-secondary:hover {
  background: #e5e7eb;
}

/* 스피너 */
.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 로딩 섹션 */
.loading-section {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  padding: 60px 40px;
  text-align: center;
}

.loading-spinner {
  width: 60px;
  height: 60px;
  border: 4px solid #e5e7eb;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 24px;
}

.loading-content p {
  font-size: 18px;
  color: #374151;
  margin-bottom: 32px;
}

.loading-steps {
  display: flex;
  justify-content: center;
  gap: 48px;
}

.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  opacity: 0.4;
  transition: opacity 0.3s ease;
}

.step.active {
  opacity: 1;
}

.step-icon {
  width: 32px;
  height: 32px;
  background: #e5e7eb;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #6b7280;
  transition: all 0.3s ease;
}

.step.active .step-icon {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.step span:last-child {
  font-size: 13px;
  color: #6b7280;
}

/* 에러 섹션 */
.error-section {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  padding: 60px 40px;
  text-align: center;
}

.error-content h3 {
  color: #ef4444;
  margin: 16px 0 8px;
}

.error-content p {
  color: #6b7280;
  margin-bottom: 24px;
}

/* 결과 섹션 */
.result-section {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 리스크 게이지 카드 */
.risk-gauge-card {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  padding: 32px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  border: 2px solid transparent;
}

.risk-gauge-card.safe {
  border-color: rgba(34, 197, 94, 0.3);
}

.risk-gauge-card.warning {
  border-color: rgba(234, 179, 8, 0.3);
}

.risk-gauge-card.danger {
  border-color: rgba(239, 68, 68, 0.3);
}

.gauge-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.gauge-header h2 {
  font-size: 24px;
  font-weight: 700;
  margin: 0;
}

.status-badge {
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 600;
}

.status-badge.safe {
  background: rgba(34, 197, 94, 0.1);
  color: #16a34a;
}

.status-badge.warning {
  background: rgba(234, 179, 8, 0.1);
  color: #ca8a04;
}

.status-badge.danger {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.gauge-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 24px;
}

.gauge {
  position: relative;
  width: 240px;
  height: 140px;
}

.gauge-svg {
  width: 100%;
  height: 100%;
}

.gauge-arc {
  transition: stroke-dashoffset 1s ease-out;
}

.gauge-value {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  text-align: center;
}

.gauge-value .score {
  font-size: 48px;
  font-weight: 700;
  color: #1f2937;
}

.gauge-value .label {
  font-size: 18px;
  color: #9ca3af;
}

.gauge-labels {
  display: flex;
  justify-content: space-between;
  width: 240px;
  margin-top: 8px;
}

.gauge-labels span {
  font-size: 12px;
  font-weight: 600;
}

.gauge-labels .safe { color: #22c55e; }
.gauge-labels .warning { color: #eab308; }
.gauge-labels .danger { color: #ef4444; }

.risk-reason {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 12px;
  color: #4b5563;
}

.danger-warning {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-top: 16px;
  padding: 20px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 12px;
  color: #dc2626;
}

.danger-warning strong {
  font-size: 16px;
  display: block;
  margin-bottom: 4px;
}

.danger-warning p {
  margin: 0;
  font-size: 14px;
  opacity: 0.9;
}

/* 분석 그리드 */
.analysis-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

@media (max-width: 1200px) {
  .analysis-grid {
    grid-template-columns: 1fr;
  }
}

.analysis-card {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px;
  border-bottom: 1px solid #f1f5f9;
}

.header-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-icon.dart {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
}

.header-icon.news {
  background: rgba(34, 197, 94, 0.1);
  color: #22c55e;
}

.header-icon.ai {
  background: rgba(139, 92, 246, 0.1);
  color: #8b5cf6;
}

.card-header h3 {
  flex: 1;
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}

.badge {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
}

.badge.safe {
  background: rgba(34, 197, 94, 0.1);
  color: #16a34a;
}

.badge.warning {
  background: rgba(234, 179, 8, 0.1);
  color: #ca8a04;
}

.badge.danger {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.badge.neutral {
  background: #f1f5f9;
  color: #64748b;
}

.badge.ai {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.card-body {
  padding: 16px 20px 20px;
  max-height: 300px;
  overflow-y: auto;
}

/* 공시 목록 */
.disclosure-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.disclosure-item {
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
  border-left: 3px solid #e5e7eb;
}

.disclosure-item.dangerous {
  background: rgba(239, 68, 68, 0.05);
  border-left-color: #ef4444;
}

.disclosure-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.disclosure-date {
  font-size: 12px;
  color: #6b7280;
}

.danger-tag {
  font-size: 11px;
  padding: 2px 8px;
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
  border-radius: 4px;
}

.disclosure-title {
  font-size: 14px;
  font-weight: 500;
  color: #1f2937;
  margin-bottom: 4px;
}

.disclosure-corp {
  font-size: 12px;
  color: #6b7280;
}

/* 뉴스 목록 */
.news-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.news-item {
  display: block;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
  text-decoration: none;
  transition: all 0.2s ease;
}

.news-item:hover {
  background: #f1f5f9;
  transform: translateX(4px);
}

.news-title {
  font-size: 14px;
  color: #1f2937;
  line-height: 1.4;
  margin-bottom: 4px;
}

.news-date {
  font-size: 12px;
  color: #9ca3af;
}

/* AI 분석 */
.ai-analysis-content {
  line-height: 1.7;
  color: #4b5563;
}

/* 빈 상태 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #9ca3af;
  text-align: center;
}

.empty-state p {
  margin-top: 12px;
  font-size: 14px;
}

/* 분석 푸터 */
.analysis-footer {
  text-align: center;
  padding: 12px;
  font-size: 13px;
  color: #9ca3af;
}

/* 초기 상태 */
.empty-section {
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  padding: 80px 40px;
}

.empty-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.empty-content h3 {
  font-size: 20px;
  color: #374151;
  margin: 24px 0 12px;
}

.empty-content p {
  font-size: 15px;
  color: #6b7280;
  line-height: 1.6;
}

/* 반응형 */
@media (max-width: 768px) {
  .search-box {
    flex-direction: column;
  }

  .loading-steps {
    flex-direction: column;
    gap: 16px;
  }

  .gauge {
    width: 200px;
    height: 120px;
  }

  .gauge-value .score {
    font-size: 36px;
  }

  .gauge-labels {
    width: 200px;
  }
}
</style>
