<template>
  <div class="briefing-card">
    <div class="briefing-head">
      <span class="briefing-title">🌅 오늘의 브리핑</span>
      <span class="briefing-time" v-if="updateTime">{{ updateTime }}</span>
    </div>

    <!-- 권고 (헤드라인) -->
    <div class="recommendation" :class="recommendation.cls">
      <span class="rec-icon">{{ recommendation.icon }}</span>
      <div class="rec-content">
        <div class="rec-label">{{ recommendation.label }}</div>
        <div class="rec-reason">{{ recommendation.reason }}</div>
      </div>
    </div>

    <!-- 상세 4줄 -->
    <div class="briefing-lines">
      <!-- 시장 -->
      <div class="line">
        <span class="line-icon">📊</span>
        <span class="line-text" v-if="marketLine">{{ marketLine }}</span>
        <span class="line-text muted" v-else>시장 데이터 로딩 중…</span>
      </div>

      <!-- 수급 -->
      <div class="line">
        <span class="line-icon">💰</span>
        <span class="line-text" v-if="supplyLine">{{ supplyLine }}</span>
        <span class="line-text muted" v-else>수급 데이터 로딩 중…</span>
      </div>

      <!-- 매수 후보 -->
      <div class="line">
        <span class="line-icon">🏆</span>
        <span class="line-text" v-if="recLine">{{ recLine }}</span>
        <span class="line-text muted" v-else>AI 추천 분석 중…</span>
      </div>

      <!-- 리스크 -->
      <div class="line" v-if="riskLine">
        <span class="line-icon">⚠️</span>
        <span class="line-text">{{ riskLine }}</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'SectionBriefing',
  props: {
    marketData: { type: Object, default: null },
    globalData: { type: Object, default: () => ({}) },
    topRecommendations: { type: Array, default: () => [] },
    supplyPanelData: { type: Object, default: null },
    watchlistItems: { type: Array, default: () => [] },
    watchlistRisks: { type: Object, default: () => ({}) }
  },
  computed: {
    updateTime() {
      const d = new Date()
      return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
    },

    avgMarketChange() {
      if (!this.marketData) return null
      const k = Number(this.marketData.kospiChangeRate)
      const q = Number(this.marketData.kosdaqChangeRate)
      const valid = [k, q].filter(v => Number.isFinite(v))
      if (!valid.length) return null
      return valid.reduce((a, b) => a + b, 0) / valid.length
    },

    marketMood() {
      const v = this.avgMarketChange
      if (v == null) return null
      if (v >= 1.0) return '활황'
      if (v >= 0.3) return '강세'
      if (v >= -0.3) return '보합'
      if (v >= -1.5) return '약세'
      return '급락'
    },

    marketLine() {
      if (!this.marketData) return null
      const k = this.marketData.kospiChangeRate
      const q = this.marketData.kosdaqChangeRate
      const fmt = (x) => x == null ? '-' : (Number(x) >= 0 ? '+' : '') + Number(x).toFixed(2) + '%'
      const parts = [`KOSPI ${fmt(k)}`, `KOSDAQ ${fmt(q)}`]
      if (this.globalData?.nasdaqFutures?.changeRate != null) {
        parts.push(`나스닥선물 ${fmt(this.globalData.nasdaqFutures.changeRate)}`)
      }
      const mood = this.marketMood
      return parts.join(' · ') + (mood ? ` (${mood})` : '')
    },

    supplyLine() {
      if (!this.supplyPanelData?.daily?.length) return null
      const totalNet = this.supplyPanelData.daily.reduce((s, d) => s + (Number(d.amount) || 0), 0)
      const sign = totalNet >= 0 ? '+' : ''
      const total = `외인+기관 ${sign}${totalNet.toLocaleString()}억`
      const rally = this.supplyPanelData.rallySignal
      if (rally) {
        const dot = rally.type === 'real' ? '🟢' : '🔴'
        return `${total} · ${dot} ${rally.message}`
      }
      return total
    },

    recCounts() {
      const list = (this.topRecommendations || []).filter(r => (r.validCount ?? 5) >= 3)
      return {
        strong: list.filter(r => (r.totalScore || 0) >= 75),
        buy: list.filter(r => (r.totalScore || 0) >= 60 && (r.totalScore || 0) < 75)
      }
    },

    recLine() {
      const { strong, buy } = this.recCounts
      if (!strong.length && !buy.length) return null
      const parts = []
      if (strong.length) parts.push(`🔴 강력매수 ${strong.length}`)
      if (buy.length) parts.push(`🟡 매수고려 ${buy.length}`)
      const top = strong[0] || buy[0]
      const tail = top ? ` · Top: ${top.stockName}(${top.totalScore})` : ''
      return parts.join(' / ') + tail
    },

    riskCounts() {
      const risks = Object.values(this.watchlistRisks || {})
      return {
        danger: risks.filter(r => r?.riskLevel === 'DANGER').length,
        warning: risks.filter(r => r?.riskLevel === 'WARNING').length
      }
    },

    riskLine() {
      const { danger, warning } = this.riskCounts
      const marketCrash = this.avgMarketChange != null && this.avgMarketChange < -1.5
      if (!danger && !warning && !marketCrash) return null
      const parts = []
      if (marketCrash) parts.push('시장 급락')
      if (danger) parts.push(`관심종목 위험 ${danger}`)
      if (warning) parts.push(`경고 ${warning}`)
      return parts.join(' · ')
    },

    recommendation() {
      const m = this.avgMarketChange
      const { strong } = this.recCounts
      const { danger } = this.riskCounts
      const supplyPositive = this.supplyPanelData?.rallySignal?.type === 'real'
      const supplyNegative = this.supplyPanelData?.rallySignal?.type === 'fake'

      // 우선순위 룰
      if (m != null && m < -1.5) {
        return { cls: 'rec-defensive', icon: '🛡️', label: '방어', reason: '시장 급락 — 현금 비중 확보' }
      }
      if (danger >= 3 || (supplyNegative && m != null && m < 0)) {
        return { cls: 'rec-defensive', icon: '🛡️', label: '방어', reason: '관심종목 위험 다수 / 수급 부정적' }
      }
      if (m != null && m >= 0.3 && strong.length >= 2 && danger === 0) {
        const sup = supplyPositive ? '+ 외인 매수' : ''
        return { cls: 'rec-strong', icon: '🚀', label: '적극 매수', reason: `모멘텀 양호${sup}` }
      }
      if (strong.length >= 1 && danger <= 1) {
        return { cls: 'rec-buy', icon: '🎯', label: '선별 매수', reason: '강력매수 후보 존재 — 분할 진입 검토' }
      }
      if (m != null && m >= -0.5) {
        return { cls: 'rec-hold', icon: '👀', label: '관망', reason: '명확한 시그널 부족 — 기다림' }
      }
      return { cls: 'rec-hold', icon: '👀', label: '관망', reason: '시장 약세 — 진입 타이밍 대기' }
    }
  }
}
</script>

<style scoped>
.briefing-card {
  background: linear-gradient(135deg, rgba(124,58,237,0.08), rgba(79,70,229,0.05));
  border: 1px solid rgba(124,58,237,0.25);
  border-radius: 16px;
  padding: 16px 20px;
  color: #fff;
}
.briefing-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.briefing-title {
  font-size: 13px;
  font-weight: 600;
  color: rgba(255,255,255,0.7);
  letter-spacing: 0.3px;
}
.briefing-time {
  font-size: 11px;
  color: rgba(255,255,255,0.4);
}

/* 권고 헤드라인 */
.recommendation {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border-radius: 12px;
  margin-bottom: 12px;
  background: rgba(255,255,255,0.04);
  border-left: 3px solid rgba(255,255,255,0.2);
}
.rec-icon { font-size: 28px; line-height: 1; }
.rec-content { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.rec-label { font-size: 18px; font-weight: 700; line-height: 1.2; }
.rec-reason { font-size: 12px; color: rgba(255,255,255,0.65); }

.recommendation.rec-strong {
  background: rgba(239,68,68,0.12);
  border-left-color: #ef4444;
}
.recommendation.rec-strong .rec-label { color: #ef4444; }
.recommendation.rec-buy {
  background: rgba(245,158,11,0.12);
  border-left-color: #f59e0b;
}
.recommendation.rec-buy .rec-label { color: #fbbf24; }
.recommendation.rec-hold {
  background: rgba(148,163,184,0.10);
  border-left-color: #94a3b8;
}
.recommendation.rec-hold .rec-label { color: #cbd5e1; }
.recommendation.rec-defensive {
  background: rgba(59,130,246,0.12);
  border-left-color: #3b82f6;
}
.recommendation.rec-defensive .rec-label { color: #60a5fa; }

/* 상세 라인 */
.briefing-lines {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: rgba(255,255,255,0.85);
  padding: 4px 0;
}
.line-icon { width: 20px; flex-shrink: 0; text-align: center; font-size: 14px; }
.line-text { flex: 1; }
.line-text.muted { color: rgba(255,255,255,0.4); font-style: italic; }

@media (max-width: 600px) {
  .briefing-card { padding: 14px 16px; }
  .rec-label { font-size: 16px; }
  .line { font-size: 12.5px; }
}
</style>
