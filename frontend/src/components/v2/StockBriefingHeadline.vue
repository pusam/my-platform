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
      const v = this.diagnosisData?.supplyDemand?.institutionNet5Days
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
      // 5일 합 기준 — '동반 매도'는 지금도 판다는 말로 읽혔다(2026-10-07, 삼성전자 기관은 최근 3일 순매수)
      if (this.isSupplyNegative) list.push('외인·기관 5일 누적 순매도')
      const warnings = this.diagnosisData?.warnings
      if (Array.isArray(warnings) && warnings.length) {
        // 펀더멘털 경고가 3개 이상이면 첫 1개만 헤드라인에 노출
        list.push(warnings[0].slice(0, 30))
      }
      return list.slice(0, 2)
    },
    recommendation() {
      // 자체 매수·매도 판정은 내지 않는다(2026-10-03). 예전엔 진단 점수(기본값 50 이 섞인 종합)·AI 키워드 점수('매수'라는
      // 단어만 있으면 70)·5일 수급(부호 오류로 순매도가 순매수)을 엮어 '🚀 적극 매수 / 🎯 선별 매수 / 🛡️ 회피 / 👀 관망'을
      // 만들었고, 바로 위 결론 카드(검증 규칙 55/75)와 다른 말을 했다(삼성전기: 카드 '매수' · 헤드라인 '관망').
      // 판단은 결론 카드 하나 — 여기는 관측된 주의 사항만 남긴다.
      if (this.fundScore == null && !this.aiRec && !this.cautions.length) {
        return { cls: 'rec-loading', icon: '⏳', label: '분석 중', reason: '데이터를 수집하고 있습니다' }
      }
      return { cls: 'rec-hold', icon: '📋', label: '참고', reason: '매수·매도 판단은 위 결론 카드(검증 규칙)를 따릅니다' }
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
  .caution-chip { font-size: 11px; }
}
</style>
