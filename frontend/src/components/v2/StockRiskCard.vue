<template>
  <div class="risk-card" :class="cardClass">
    <!-- 헤더 -->
    <div class="risk-head">
      <div class="risk-title">
        <span class="risk-icon">{{ statusIcon }}</span>
        <div>
          <div class="risk-label">{{ statusLabel }}</div>
          <div class="risk-sublabel">{{ subLabel }}</div>
        </div>
      </div>
      <div class="risk-actions">
        <button v-if="canDeepAnalyze"
                class="deep-btn"
                :disabled="deepLoading"
                @click="loadDeepAnalysis">
          {{ deepLoading ? '분석 중…' : (detail ? '🔄 재분석' : '🔍 상세 분석') }}
        </button>
      </div>
    </div>

    <!-- 빠른 체크 결과 (mount 시 자동) -->
    <div v-if="quickLoading" class="quick-loading">공시 빠른 체크 중...</div>

    <!-- 상세 분석 결과 -->
    <div v-if="detail" class="risk-detail">
      <div v-if="detail.riskScore != null" class="risk-score-row">
        <span class="meta-label">리스크 점수</span>
        <span class="risk-score" :class="cardClass">{{ detail.riskScore }} / 100</span>
      </div>
      <div v-if="detail.reason" class="risk-reason">
        <span class="meta-label">사유</span>
        <span>{{ detail.reason }}</span>
      </div>

      <!-- 위험 공시 -->
      <div v-if="dangerousDisclosures.length" class="risk-section">
        <div class="risk-section-title">⚠️ 위험 공시 ({{ dangerousDisclosures.length }}건)</div>
        <ul class="risk-list">
          <li v-for="(d, i) in dangerousDisclosures.slice(0, 5)" :key="'d-'+i">
            <span class="disclosure-tag" v-if="d.matchedKeyword">{{ d.matchedKeyword }}</span>
            <span class="disclosure-name">{{ d.reportNm }}</span>
            <span class="disclosure-date">{{ d.rceptDt }}</span>
          </li>
        </ul>
      </div>

      <!-- 관련 뉴스 -->
      <div v-if="detail.relatedNews && detail.relatedNews.length" class="risk-section">
        <div class="risk-section-title">📰 관련 뉴스 ({{ detail.relatedNews.length }})</div>
        <ul class="risk-list">
          <li v-for="(n, i) in detail.relatedNews.slice(0, 5)" :key="'n-'+i" class="news-item">
            <a v-if="n.link" :href="n.link" target="_blank" rel="noopener" class="news-link">{{ n.title }}</a>
            <span v-else class="news-title">{{ n.title }}</span>
            <span class="news-date">{{ formatNewsDate(n.pubDate) }}</span>
          </li>
        </ul>
      </div>

      <!-- AI 분석 -->
      <div v-if="detail.aiAnalysis" class="risk-section ai-section">
        <div class="risk-section-title">🤖 AI 분석</div>
        <div class="ai-body" v-html="formatAi(detail.aiAnalysis)"></div>
      </div>

      <div class="risk-meta">
        분석 시각: {{ formatAnalyzedAt(detail.analyzedAt) }}
      </div>
    </div>
  </div>
</template>

<script>
import { riskAPI } from '../../utils/api'

export default {
  name: 'StockRiskCard',
  props: {
    stockName: { type: String, required: true },
    stockCode: { type: String, default: '' }
  },
  data() {
    return {
      quickLoading: false,
      hasDangerousQuick: null,   // null=미체크, true=위험, false=안전
      deepLoading: false,
      detail: null               // 상세 분석 결과 RiskAnalysisDto
    }
  },
  computed: {
    statusValue() {
      // 상세 결과 우선, 없으면 quick check 결과
      if (this.detail?.status) return this.detail.status
      if (this.hasDangerousQuick === true) return 'WARNING'
      if (this.hasDangerousQuick === false) return 'SAFE'
      return 'UNKNOWN'
    },
    cardClass() {
      const m = {
        SAFE: 'status-safe',
        WARNING: 'status-warning',
        DANGER: 'status-danger',
        UNKNOWN: 'status-unknown'
      }
      return m[this.statusValue] || 'status-unknown'
    },
    statusIcon() {
      const m = {
        SAFE: '🟢', WARNING: '🟡', DANGER: '🔴', UNKNOWN: '⚪'
      }
      return m[this.statusValue]
    },
    statusLabel() {
      const m = {
        SAFE: '리스크 안전',
        WARNING: '주의 필요',
        DANGER: '매수 금지 권장',
        UNKNOWN: '리스크 체크'
      }
      return m[this.statusValue]
    },
    subLabel() {
      if (this.detail) return '상세 분석 완료'
      if (this.quickLoading) return '빠른 체크 중...'
      if (this.hasDangerousQuick === true) return '위험 공시 발견 — 상세 분석 권장'
      if (this.hasDangerousQuick === false) return '위험 공시 없음 — 상세 분석은 버튼 클릭'
      return '버튼을 눌러 분석을 시작하세요'
    },
    canDeepAnalyze() {
      return !!this.stockName && !this.deepLoading
    },
    dangerousDisclosures() {
      const list = this.detail?.dangerousDisclosures
      return Array.isArray(list) ? list.filter(d => d.isDangerous !== false) : []
    }
  },
  watch: {
    stockName(v, prev) {
      if (v && v !== prev) {
        this.detail = null
        this.hasDangerousQuick = null
        this.runQuickCheck()
      }
    }
  },
  mounted() {
    if (this.stockName) this.runQuickCheck()
  },
  methods: {
    async runQuickCheck() {
      if (!this.stockName) return
      this.quickLoading = true
      try {
        const res = await riskAPI.quickCheck(this.stockName)
        const body = res?.data || res
        if (body?.success) {
          this.hasDangerousQuick = !!body.hasDangerousDisclosure
        }
      } catch (e) {
        // 실패 시 사용자에게 부담 안 주기 — 에러 무시, UI는 unknown
        console.warn('[Risk] quick check 실패', e?.message)
      } finally {
        this.quickLoading = false
      }
    },
    async loadDeepAnalysis() {
      if (!this.stockName) return
      this.deepLoading = true
      try {
        const res = await riskAPI.checkRisk(this.stockName)
        const body = res?.data || res
        if (body?.success && body?.data) {
          this.detail = body.data
        }
      } catch (e) {
        console.error('[Risk] 상세 분석 실패', e)
      } finally {
        this.deepLoading = false
      }
    },
    formatAi(text) {
      if (!text) return ''
      const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      return escaped
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>')
    },
    formatNewsDate(dt) {
      if (!dt) return ''
      try {
        const d = new Date(dt)
        return `${d.getMonth() + 1}/${d.getDate()}`
      } catch { return '' }
    },
    formatAnalyzedAt(dt) {
      if (!dt) return ''
      try {
        const d = new Date(dt)
        return d.toLocaleString('ko-KR', {
          month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
        })
      } catch { return '' }
    }
  }
}
</script>

<style scoped>
.risk-card {
  background: rgba(30, 30, 60, 0.5);
  border: 1px solid #2a2a5a;
  border-left: 4px solid rgba(255,255,255,0.2);
  border-radius: 12px;
  padding: 14px 18px;
  margin-bottom: 16px;
  color: #e2e8f0;
}
.risk-card.status-safe { border-left-color: #22c55e; background: rgba(34,197,94,0.06); }
.risk-card.status-warning { border-left-color: #f59e0b; background: rgba(245,158,11,0.06); }
.risk-card.status-danger { border-left-color: #ef4444; background: rgba(239,68,68,0.07); }
.risk-card.status-unknown { border-left-color: rgba(255,255,255,0.2); }

.risk-head {
  display: flex; justify-content: space-between; align-items: center;
  flex-wrap: wrap; gap: 10px;
}
.risk-title {
  display: flex; align-items: center; gap: 12px;
}
.risk-icon { font-size: 26px; line-height: 1; }
.risk-label {
  font-size: 16px; font-weight: 800; line-height: 1.2;
}
.risk-card.status-safe .risk-label { color: #22c55e; }
.risk-card.status-warning .risk-label { color: #fbbf24; }
.risk-card.status-danger .risk-label { color: #ef4444; }
.risk-sublabel {
  font-size: 12px; color: rgba(255,255,255,0.6);
  margin-top: 2px;
}

.deep-btn {
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.15);
  color: #fff;
  padding: 7px 14px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.deep-btn:hover:not(:disabled) { background: rgba(255,255,255,0.14); }
.deep-btn:disabled { opacity: 0.5; cursor: wait; }

.quick-loading {
  margin-top: 10px; font-size: 12px; color: rgba(255,255,255,0.5);
  font-style: italic;
}

.risk-detail {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255,255,255,0.08);
}
.risk-score-row, .risk-reason {
  display: flex; gap: 10px; margin-bottom: 8px;
  font-size: 13px;
}
.meta-label {
  color: rgba(255,255,255,0.55); font-weight: 600;
  min-width: 80px;
}
.risk-score { font-weight: 800; font-family: monospace; }
.risk-score.status-safe { color: #22c55e; }
.risk-score.status-warning { color: #fbbf24; }
.risk-score.status-danger { color: #ef4444; }

.risk-section { margin-top: 12px; }
.risk-section-title {
  font-size: 12px; font-weight: 700;
  color: rgba(255,255,255,0.85);
  margin-bottom: 6px;
}
.risk-list {
  list-style: none; padding: 0; margin: 0;
}
.risk-list li {
  padding: 6px 10px;
  background: rgba(0,0,0,0.2);
  border-radius: 6px;
  margin-bottom: 4px;
  font-size: 12px;
  display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap;
}
.disclosure-tag {
  background: rgba(239,68,68,0.2);
  color: #ef4444;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10.5px;
  font-weight: 700;
}
.disclosure-name { flex: 1; color: rgba(255,255,255,0.85); }
.disclosure-date {
  font-family: monospace;
  color: rgba(255,255,255,0.45);
  font-size: 10.5px;
}
.news-item .news-link, .news-item .news-title {
  flex: 1; color: rgba(255,255,255,0.85);
  font-size: 12px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.news-item .news-link { text-decoration: none; }
.news-item .news-link:hover { color: #a5b4fc; text-decoration: underline; }
.news-date {
  font-family: monospace; color: rgba(255,255,255,0.45);
  font-size: 10.5px;
}

.ai-section { margin-top: 14px; }
.ai-body {
  background: rgba(102,126,234,0.06);
  border: 1px solid rgba(102,126,234,0.18);
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  line-height: 1.7;
  color: rgba(255,255,255,0.85);
}
.ai-body strong { color: #fff; }

.risk-meta {
  margin-top: 10px;
  font-size: 10.5px;
  color: rgba(255,255,255,0.4);
}

@media (max-width: 600px) {
  .risk-card { padding: 12px 14px; }
  .risk-icon { font-size: 22px; }
  .risk-label { font-size: 14px; }
  .deep-btn { padding: 6px 10px; font-size: 11px; }
  .meta-label { min-width: 60px; }
}
</style>
