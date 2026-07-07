<template>
  <!-- 핵심 요약 카드 (항상 고정) — StockDetailDashboard 에서 분리 (P2-10) -->
  <div v-if="hasData && !loading" class="quick-summary-bar">
    <div class="qs-item">
      <span class="qs-label">RSI</span>
      <span class="qs-value" :class="getQsRsiClass()">
        {{ diagnosisData?.technicalAnalysis?.rsi14 != null ? Number(diagnosisData.technicalAnalysis.rsi14).toFixed(0) : '-' }}
      </span>
      <span class="qs-badge" :class="getQsRsiClass()">
        {{ getQsRsiLabel() }}
      </span>
    </div>
    <div class="qs-item">
      <span class="qs-label">20일선</span>
      <span class="qs-value" :class="getQsMaClass()">
        {{ getQsMaPosition() }}
      </span>
      <span class="qs-sub">{{ getQsMaDisparity() }}</span>
    </div>
    <div class="qs-item">
      <span class="qs-label">외국인</span>
      <span class="qs-value" :class="diagnosisData?.supplyDemand?.foreignNet5Days >= 0 ? 'qs-positive' : 'qs-negative'">
        {{ getQsForeignLabel() }}
      </span>
      <span class="qs-sub">{{ getQsForeignAmount() }}</span>
      <span v-if="getQsForeignStreak()" class="qs-streak">{{ getQsForeignStreak() }}</span>
    </div>
    <div class="qs-item">
      <span class="qs-label">기관</span>
      <span class="qs-value" :class="diagnosisData?.supplyDemand?.instNet5Days >= 0 ? 'qs-positive' : 'qs-negative'">
        {{ getQsInstLabel() }}
      </span>
      <span class="qs-sub">{{ getQsInstAmount() }}</span>
      <span v-if="getQsInstStreak()" class="qs-streak">{{ getQsInstStreak() }}</span>
    </div>
    <div class="qs-item">
      <span class="qs-label">리스크</span>
      <span class="qs-badge" :class="getQsRiskClass()">
        {{ getQsRiskLabel() }}
      </span>
    </div>
    <div class="qs-item">
      <span class="qs-label">AI 점수</span>
      <span class="qs-value">{{ aiAnalysis?.overallScore || '-' }}</span>
      <span class="qs-badge" :class="'qs-rec-' + (aiAnalysis?.recommendation || 'hold').toLowerCase()">
        {{ getRecommendationLabel(aiAnalysis?.recommendation) }}
      </span>
    </div>
  </div>
  <div v-else-if="loading" class="quick-summary-bar skeleton">
    <div class="qs-item qs-skeleton" v-for="i in 6" :key="i"><div class="qs-skeleton-bar"></div></div>
  </div>
</template>

<script setup>
const props = defineProps({
  hasData: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  diagnosisData: { type: Object, default: null },
  aiAnalysis: { type: Object, default: null }
});

// getRecommendationLabel: 부모에도 다른 용도로 존재 — 순수 매핑이라 소형 복제(로직 동일).
const getRecommendationLabel = (rec) => ({
  BUY: 'BUY', TRADING_BUY: 'Trading Buy', WAIT_AND_BUY: 'Wait & Buy', HOLD: 'HOLD', SELL: 'SELL'
}[rec] || rec || '-');

// ===== 핵심 요약 카드 헬퍼 (StockDetailDashboard 에서 이동, 로직 동일) =====
const getQsRsiClass = () => {
  const rsi = props.diagnosisData?.technicalAnalysis?.rsi14;
  if (rsi == null) return '';
  if (rsi >= 70) return 'qs-danger';
  if (rsi <= 30) return 'qs-cold';
  return 'qs-neutral';
};
const getQsRsiLabel = () => {
  const rsi = props.diagnosisData?.technicalAnalysis?.rsi14;
  if (rsi == null) return '';
  if (rsi >= 70) return '과매수';
  if (rsi <= 30) return '과매도';
  return '중립';
};
const getQsMaClass = () => {
  const d = props.diagnosisData?.technicalAnalysis?.disparity20;
  if (d == null) return '';
  return d >= 0 ? 'qs-positive' : 'qs-negative';
};
const getQsMaPosition = () => {
  const d = props.diagnosisData?.technicalAnalysis?.disparity20;
  if (d == null) return '-';
  return d >= 0 ? '위' : '아래';
};
const getQsMaDisparity = () => {
  const d = props.diagnosisData?.technicalAnalysis?.disparity20;
  if (d == null) return '';
  return (d >= 0 ? '+' : '') + Number(d).toFixed(1) + '%';
};
const getQsForeignLabel = () => {
  const v = props.diagnosisData?.supplyDemand?.foreignNet5Days;
  if (v == null) return '-';
  return v >= 0 ? '순매수' : '순매도';
};
const getQsForeignAmount = () => {
  const v = props.diagnosisData?.supplyDemand?.foreignNet5Days;
  if (v == null) return '';
  return (v >= 0 ? '+' : '') + Number(v).toFixed(0) + '억';
};
const getQsInstLabel = () => {
  const v = props.diagnosisData?.supplyDemand?.instNet5Days;
  if (v == null) return '-';
  return v >= 0 ? '순매수' : '순매도';
};
const getQsInstAmount = () => {
  const v = props.diagnosisData?.supplyDemand?.instNet5Days;
  if (v == null) return '';
  return (v >= 0 ? '+' : '') + Number(v).toFixed(0) + '억';
};
// 연속 순매수일 배지 — 2일 이상 연속일 때만 "N일 연속"(참고 톤, 산식 미편입). null/0/1 은 미표시.
const streakLabel = (v) => (v != null && Number(v) >= 2 ? `${Number(v)}일 연속` : '');
const getQsForeignStreak = () => streakLabel(props.diagnosisData?.supplyDemand?.foreignBuyStreak);
const getQsInstStreak = () => streakLabel(props.diagnosisData?.supplyDemand?.institutionBuyStreak);
const getQsRiskClass = () => {
  const score = props.diagnosisData?.overallScore;
  if (score == null) return 'qs-neutral';
  if (score >= 70) return 'qs-safe';
  if (score >= 40) return 'qs-warning';
  return 'qs-danger';
};
const getQsRiskLabel = () => {
  const score = props.diagnosisData?.overallScore;
  if (score == null) return '-';
  if (score >= 70) return 'SAFE';
  if (score >= 40) return 'WARNING';
  return 'DANGER';
};
</script>

<style scoped>
/* 핵심 요약 카드 */
.quick-summary-bar {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  margin-bottom: 12px;
  padding: 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px;
}
.quick-summary-bar.skeleton { opacity: 0.5; }
.qs-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 4px;
}
.qs-label { font-size: 10px; color: rgba(255,255,255,0.6); font-weight: 600; }
.qs-value { font-size: 16px; font-weight: 800; color: rgba(255,255,255,0.9); }
.qs-sub { font-size: 10px; color: rgba(255,255,255,0.6); }
.qs-streak {
  font-size: 9.5px; font-weight: 700; margin-top: 1px;
  padding: 0 5px; border-radius: 4px;
  background: rgba(239,68,68,0.14); color: #f87171;
}
.qs-badge {
  font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px;
  background: rgba(107,114,128,0.2); color: #9ca3af;
}
.qs-positive { color: #ef4444; }
.qs-negative { color: #3b82f6; }
.qs-neutral { color: rgba(255,255,255,0.7); }
.qs-danger { color: #ef4444; }
.qs-danger.qs-badge { background: rgba(239,68,68,0.15); color: #ef4444; }
.qs-cold { color: #3b82f6; }
.qs-cold.qs-badge { background: rgba(59,130,246,0.15); color: #3b82f6; }
.qs-safe { background: rgba(34,197,94,0.15); color: #22c55e; }
.qs-warning { background: rgba(245,158,11,0.15); color: #f59e0b; }
.qs-rec-buy { background: rgba(239,68,68,0.15); color: #ef4444; }
.qs-rec-trading_buy { background: rgba(239,68,68,0.1); color: #f87171; }
.qs-rec-hold { background: rgba(107,114,128,0.15); color: #9ca3af; }
.qs-rec-sell { background: rgba(59,130,246,0.15); color: #3b82f6; }
.qs-rec-wait_and_buy { background: rgba(245,158,11,0.15); color: #f59e0b; }
.qs-skeleton { height: 50px; }
.qs-skeleton-bar {
  width: 60%; height: 14px; border-radius: 4px;
  background: rgba(255,255,255,0.08);
  animation: skeleton-pulse 1.5s infinite;
}
@keyframes skeleton-pulse { 0%,100% { opacity: 0.5; } 50% { opacity: 0.2; } }

@media (max-width: 768px) {
  .quick-summary-bar { grid-template-columns: repeat(3, 1fr); }
  .qs-value { font-size: 14px; }
}
@media (max-width: 480px) {
  .quick-summary-bar { grid-template-columns: repeat(2, 1fr); gap: 6px; padding: 10px; }
  .qs-value { font-size: 13px; }
  .qs-label { font-size: 9.5px; }
  .qs-sub { font-size: 9.5px; }
  .qs-badge { font-size: 9.5px; padding: 1px 5px; }
}
</style>
