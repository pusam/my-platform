<template>
  <div class="briefing-headline" :class="recommendation.cls">
    <span class="rec-icon">{{ recommendation.icon }}</span>
    <div class="rec-content">
      <div class="rec-label">{{ recommendation.label }}</div>
      <div class="rec-reason">{{ recommendation.reason }}</div>
    </div>
    <div v-if="cautions.length" class="rec-cautions">
      <span v-for="(c, i) in cautions" :key="'c-'+i" class="caution-chip">⚠️ {{ c }}</span>
    </div>
  </div>
</template>

<script>
export default {
  name: 'StockBriefingHeadline',
  props: {
    diagnosisData: { type: Object, default: null },
    aiAnalysis: { type: Object, default: null }
  },
  computed: {
    fundScore() {
      const s = this.diagnosisData?.overallScore
      return Number.isFinite(Number(s)) ? Number(s) : null
    },
    aiScore() {
      const s = this.aiAnalysis?.overallScore
      return Number.isFinite(Number(s)) ? Number(s) : null
    },
    aiRec() {
      // BUY / TRADING_BUY / HOLD / WAIT_AND_BUY / SELL
      return (this.aiAnalysis?.recommendation || '').toUpperCase()
    },
    rsi() {
      const v = this.diagnosisData?.technicalAnalysis?.rsi14
      return Number.isFinite(Number(v)) ? Number(v) : null
    },
    foreignNet() {
      const v = this.diagnosisData?.supplyDemand?.foreignNet5Days
      return Number.isFinite(Number(v)) ? Number(v) : null
    },
    instNet() {
      const v = this.diagnosisData?.supplyDemand?.instNet5Days
      return Number.isFinite(Number(v)) ? Number(v) : null
    },
    isFundBullish() { return this.fundScore != null && this.fundScore >= 65 },
    isFundBearish() { return this.fundScore != null && this.fundScore < 40 },
    isAiBuy() { return ['BUY', 'TRADING_BUY'].includes(this.aiRec) },
    isAiSell() { return this.aiRec === 'SELL' },
    isSupplyPositive() {
      return (this.foreignNet != null && this.foreignNet > 0)
          || (this.instNet != null && this.instNet > 0)
    },
    isSupplyNegative() {
      return (this.foreignNet != null && this.foreignNet < 0)
          && (this.instNet != null && this.instNet < 0)
    },
    cautions() {
      const list = []
      if (this.rsi != null && this.rsi >= 70) list.push('RSI 과열')
      if (this.rsi != null && this.rsi <= 30) list.push('RSI 침체')
      if (this.isSupplyNegative) list.push('외인·기관 동반 매도')
      const warnings = this.diagnosisData?.warnings
      if (Array.isArray(warnings) && warnings.length) {
        // 펀더멘털 경고가 3개 이상이면 첫 1개만 헤드라인에 노출
        list.push(warnings[0].slice(0, 30))
      }
      return list.slice(0, 2)
    },
    recommendation() {
      const fund = this.fundScore
      const ai = this.aiRec
      const supplyPos = this.isSupplyPositive
      const supplyNeg = this.isSupplyNegative
      const overheated = this.rsi != null && this.rsi >= 75

      // 데이터 부족
      if (fund == null && !ai) {
        return { cls: 'rec-loading', icon: '⏳', label: '분석 중', reason: '데이터를 수집하고 있습니다' }
      }

      // 매도/회피 우선
      if (this.isAiSell || this.isFundBearish) {
        return { cls: 'rec-defensive', icon: '🛡️', label: '회피',
                 reason: this.isFundBearish ? '펀더멘털 부진' : 'AI 매도 신호' }
      }

      // 적극 매수
      if (this.isFundBullish && this.isAiBuy && supplyPos && !overheated) {
        return { cls: 'rec-strong', icon: '🚀', label: '적극 매수',
                 reason: '펀더멘털·AI·수급 모두 긍정' }
      }

      // 선별 매수
      if (this.isAiBuy && (this.isFundBullish || supplyPos)) {
        const why = supplyPos ? '외인·기관 매수 동반' : 'AI 매수 + 펀더멘털 양호'
        return { cls: 'rec-buy', icon: '🎯', label: '선별 매수',
                 reason: why + (overheated ? ' (RSI 과열로 분할 매수)' : '') }
      }

      // 수급 부정 + 약세
      if (supplyNeg && fund != null && fund < 60) {
        return { cls: 'rec-defensive', icon: '🛡️', label: '회피',
                 reason: '수급 이탈 + 펀더멘털 약함' }
      }

      // 관망
      const reason = fund != null
        ? (fund >= 50 ? '펀더멘털 보통, 명확한 신호 부족' : '펀더멘털 약함, 진입 타이밍 대기')
        : 'AI 신호 부족 — 추가 데이터 확인'
      return { cls: 'rec-hold', icon: '👀', label: '관망', reason }
    }
  }
}
</script>

<style scoped>
.briefing-headline {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border-radius: 14px;
  margin-bottom: 12px;
  background: rgba(255,255,255,0.04);
  border-left: 4px solid rgba(255,255,255,0.2);
  flex-wrap: wrap;
}
.rec-icon { font-size: 30px; line-height: 1; }
.rec-content { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.rec-label { font-size: 19px; font-weight: 800; line-height: 1.2; }
.rec-reason { font-size: 13px; color: rgba(255,255,255,0.7); }

.briefing-headline.rec-strong {
  background: rgba(239,68,68,0.12);
  border-left-color: #ef4444;
}
.briefing-headline.rec-strong .rec-label { color: #ef4444; }
.briefing-headline.rec-buy {
  background: rgba(245,158,11,0.12);
  border-left-color: #f59e0b;
}
.briefing-headline.rec-buy .rec-label { color: #fbbf24; }
.briefing-headline.rec-hold {
  background: rgba(148,163,184,0.10);
  border-left-color: #94a3b8;
}
.briefing-headline.rec-hold .rec-label { color: #cbd5e1; }
.briefing-headline.rec-defensive {
  background: rgba(59,130,246,0.12);
  border-left-color: #3b82f6;
}
.briefing-headline.rec-defensive .rec-label { color: #60a5fa; }
.briefing-headline.rec-loading {
  background: rgba(148,163,184,0.06);
  border-left-color: rgba(255,255,255,0.15);
}
.briefing-headline.rec-loading .rec-label { color: rgba(255,255,255,0.55); }

.rec-cautions {
  display: flex; gap: 6px; flex-wrap: wrap;
  margin-left: auto;
}
.caution-chip {
  font-size: 11px;
  padding: 3px 8px;
  background: rgba(245,158,11,0.15);
  border: 1px solid rgba(245,158,11,0.3);
  color: #fbbf24;
  border-radius: 8px;
}

@media (max-width: 600px) {
  .briefing-headline { padding: 12px 14px; gap: 10px; }
  .rec-icon { font-size: 24px; }
  .rec-label { font-size: 16px; }
  .rec-reason { font-size: 12px; }
  .rec-cautions { margin-left: 0; width: 100%; }
  .caution-chip { font-size: 10px; }
}
</style>
