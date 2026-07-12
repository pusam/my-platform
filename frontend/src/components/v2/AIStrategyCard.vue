<!--
  AI 매매 전략 카드 — StockDetailDashboard 에서 분리 (P-IA ③ 후속, 동작 변화 0).
  aiAnalysis(AI 분석 결과) + diagnosisData(점수차 코멘트용) + currentPrice(컨센서스 바 너비용)을 prop 으로 받아 표시만.
  ※ getRecommendationLabel 은 부모 헤더(단기 점수 뱃지)도 쓰므로 부모에도 동일 헬퍼가 남아있다(의도된 복제).
-->
<template>
  <div class="ai-strategy-section">
    <h2>AI 매매 전략</h2>

    <!-- 차트 시그널 칩 (규칙 기반 확정 신호) -->
    <div v-if="aiAnalysis?.chartSignals?.length" class="chart-signal-chips">
      <span
        v-for="sig in aiAnalysis.chartSignals"
        :key="sig.code"
        class="chip"
        :class="'chip-' + (sig.tone || 'neutral')"
        :title="sig.detail"
      >{{ sig.label }}</span>
    </div>

    <!-- AI 차트 해석 카드 -->
    <div v-if="aiAnalysis?.chartAnalysis" class="chart-analysis-card">
      <div class="chart-analysis-head">
        <span class="chart-analysis-icon">📈</span>
        <span class="chart-analysis-title">AI 차트 해석</span>
      </div>
      <p class="chart-analysis-body">{{ aiAnalysis.chartAnalysis }}</p>
    </div>

    <div class="strategy-box" :class="aiRecommendationClass">
      <div class="strategy-header">
        <span class="strategy-signal">{{ aiAnalysis?.technicalSignal || '-' }}</span>
        <span class="strategy-rec" :class="'rec-' + aiRecommendationClass">{{ getRecommendationLabel(aiAnalysis?.recommendation) }}</span>
      </div>
      <p class="strategy-text">{{ aiAnalysis?.strategy || '-' }}</p>

      <!-- 단기/장기 충돌 분석 -->
      <div v-if="aiAnalysis?.conflictAnalysis" class="conflict-analysis">
        <p>{{ aiAnalysis.conflictAnalysis }}</p>
      </div>

      <!-- 동적 가격 가이드 -->
      <div v-if="aiAnalysis?.priceGuide" class="price-guide">
        <span class="price-guide-icon">💰</span>
        <span class="price-guide-text">{{ aiAnalysis.priceGuide }}</span>
      </div>

      <!-- 목표주가 컨센서스 -->
      <div v-if="aiAnalysis?.consensusTargetPrice" class="consensus-section">
        <div class="consensus-header">
          <span class="consensus-label">증권사 목표주가</span>
          <span class="consensus-price">{{ formatPrice(aiAnalysis.consensusTargetPrice) }}원</span>
          <span class="consensus-upside" :class="aiAnalysis.targetUpside >= 0 ? 'positive' : 'negative'">
            {{ aiAnalysis.targetUpside >= 0 ? '+' : '' }}{{ aiAnalysis.targetUpside?.toFixed(1) }}%
          </span>
        </div>
        <div class="consensus-bar-wrap">
          <div class="consensus-bar-bg">
            <div class="consensus-bar-current"
                 :style="{ width: consensusBarWidth + '%' }"></div>
            <div class="consensus-bar-marker"
                 :style="{ left: consensusBarWidth + '%' }">
              <span class="marker-label">현재가</span>
            </div>
          </div>
          <div class="consensus-bar-labels">
            <span>0</span>
            <span>목표가</span>
          </div>
        </div>
        <div class="consensus-source" v-if="aiAnalysis.consensusSource">
          *{{ aiAnalysis.consensusSource }}
        </div>
      </div>

      <div class="reasons-section" v-if="aiAnalysis?.buyReasons?.length || aiAnalysis?.sellReasons?.length">
        <div class="buy-reasons" v-if="aiAnalysis?.buyReasons?.length">
          <h4>매수 근거</h4>
          <ul>
            <li v-for="(reason, i) in aiAnalysis.buyReasons.slice(0, 3)" :key="'buy-'+i">{{ reason }}</li>
          </ul>
        </div>
        <div class="sell-reasons" v-if="aiAnalysis?.sellReasons?.length">
          <h4>매도 근거</h4>
          <ul>
            <li v-for="(reason, i) in aiAnalysis.sellReasons.slice(0, 3)" :key="'sell-'+i">{{ reason }}</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- 한 줄 요약 가이드 (점수 차이 클 때만) -->
    <div v-if="scoreDiffComment" class="score-diff-comment">
      <p>{{ scoreDiffComment }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  aiAnalysis: { type: Object, default: null },
  diagnosisData: { type: Object, default: null },
  currentPrice: { type: Number, default: null }
});

const aiRecommendationClass = computed(() => {
  const rec = props.aiAnalysis?.recommendation;
  if (rec === 'BUY') return 'buy';
  if (rec === 'TRADING_BUY') return 'trading-buy';
  if (rec === 'WAIT_AND_BUY') return 'wait-buy';
  if (rec === 'SELL') return 'sell';
  return 'hold';
});

// 목표주가 컨센서스 바 너비 (현재가 / 목표가 비율)
const consensusBarWidth = computed(() => {
  const target = props.aiAnalysis?.consensusTargetPrice;
  const current = props.currentPrice;
  if (!target || !current || target <= 0) return 0;
  const ratio = (current / target) * 100;
  return Math.min(Math.max(ratio, 5), 100);
});

// 단기(AI)·중장기(펀더멘털) 점수 차가 클 때만 노출하는 한 줄 가이드
const scoreDiffComment = computed(() => {
  const trading = props.aiAnalysis?.overallScore;
  const fundamental = props.diagnosisData?.overallScore;
  if (!trading || !fundamental) return null;

  const guide = props.aiAnalysis?.priceGuide;
  const suffix = guide ? ` → ${guide}` : '';

  // 점수 차이가 클 때만 표시 (20점 이상 차이 또는 양쪽 극단)
  if (fundamental > trading + 20) {
    return `펀더멘털은 우수하나(${fundamental}점), 단기 수급 부진으로 인한 조정 주의(${trading}점)${suffix}`;
  }
  if (trading > fundamental + 20) {
    return `단기 모멘텀은 강하나(${trading}점), 펀더멘털 보강이 필요한 구간(${fundamental}점)${suffix}`;
  }
  if (trading >= 70 && fundamental >= 70) {
    return `단기(${trading}점)·중장기(${fundamental}점) 모두 양호 — 추세 추종 유효${suffix}`;
  }
  if (trading <= 40 && fundamental <= 40) {
    return `단기(${trading}점)·중장기(${fundamental}점) 모두 부진 — 신중한 접근 필요${suffix}`;
  }
  // 차이가 작고 극단도 아니면 숨김
  return null;
});

// ※ 부모 헤더(단기 점수 뱃지)도 동일 로직을 보유 — 의도된 복제.
const getRecommendationLabel = (rec) => {
  const map = {
    'BUY': 'BUY',
    'TRADING_BUY': 'Trading Buy',
    'WAIT_AND_BUY': 'Wait & Buy',
    'HOLD': 'HOLD',
    'SELL': 'SELL'
  };
  return map[rec] || rec || '-';
};

const formatPrice = (price) => {
  if (!price) return '-';
  return Number(price).toLocaleString('ko-KR');
};
</script>

<style scoped>
/* AI Strategy */
.ai-strategy-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--surface-panel-strong, #2a2a4a);
}

.ai-strategy-section h2 { font-size: 1rem; margin-bottom: 12px; }

/* 차트 시그널 칩 */
.chart-signal-chips {
  display: flex; flex-wrap: wrap; gap: 6px;
  margin-bottom: 10px;
}
.chip {
  font-size: 11px; font-weight: 600;
  padding: 3px 10px; border-radius: 12px;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.75);
  cursor: default;
}
.chip-positive {
  color: #4ade80;
  background: rgba(74,222,128,0.1);
  border-color: rgba(74,222,128,0.3);
}
.chip-negative {
  color: #f87171;
  background: rgba(248,113,113,0.1);
  border-color: rgba(248,113,113,0.3);
}
.chip-neutral {
  color: rgba(255,255,255,0.7);
}

/* AI 차트 해석 카드 */
.chart-analysis-card {
  padding: 12px 14px;
  background: rgba(99,102,241,0.08);
  border: 1px solid rgba(99,102,241,0.25);
  border-radius: 10px;
  margin-bottom: 12px;
}
.chart-analysis-head {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 6px;
}
.chart-analysis-icon { font-size: 14px; }
.chart-analysis-title {
  font-size: 12px; font-weight: 700;
  color: #a5b4fc;
  letter-spacing: 0.3px;
}
.chart-analysis-body {
  font-size: 13px;
  line-height: 1.55;
  color: rgba(255,255,255,0.82);
  white-space: pre-wrap;
}

.strategy-box {
  padding: 16px;
  border-radius: 10px;
  background: rgba(50, 50, 80, 0.5);
}

.strategy-box.buy { border: 2px solid #22c55e; }
.strategy-box.trading-buy { border: 2px solid #4ade80; }
.strategy-box.wait-buy { border: 2px solid #a78bfa; }
.strategy-box.sell { border: 2px solid #ef4444; }
.strategy-box.hold { border: 2px solid #6b7280; }

.strategy-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.strategy-signal {
  padding: 4px 10px;
  background: rgba(255,255,255,0.1);
  border-radius: 6px;
  font-size: 0.8rem;
}

.strategy-rec { font-size: 1.1rem; font-weight: 700; }
.strategy-box.buy .strategy-rec { color: #22c55e; }
.strategy-box.trading-buy .strategy-rec { color: #4ade80; }
.strategy-box.wait-buy .strategy-rec { color: #a78bfa; }
.strategy-box.sell .strategy-rec { color: #ef4444; }
.strategy-box.hold .strategy-rec { color: #6b7280; }

/* 충돌 분석 박스 */
.conflict-analysis {
  margin-top: 12px;
  padding: 10px 14px;
  background: rgba(167, 139, 250, 0.1);
  border: 1px solid rgba(167, 139, 250, 0.25);
  border-radius: 8px;
  font-size: 0.82rem;
  line-height: 1.5;
  color: #c4b5fd;
}

.conflict-analysis p {
  margin: 0;
}

.strategy-text { font-size: 0.9rem; color: #ccc; line-height: 1.5; margin: 0; }

.reasons-section {
  margin-top: 12px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.buy-reasons, .sell-reasons {
  padding: 10px;
  border-radius: 6px;
  background: rgba(0,0,0,0.2);
}

.buy-reasons h4 { color: #22c55e; margin: 0 0 6px 0; font-size: 0.8rem; }
.sell-reasons h4 { color: #ef4444; margin: 0 0 6px 0; font-size: 0.8rem; }

.reasons-section ul { margin: 0; padding-left: 14px; }
.reasons-section li { font-size: 0.75rem; color: #aaa; margin-bottom: 3px; }

/* 동적 가격 가이드 */
.price-guide {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 14px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: 10px;
}

.price-guide-icon {
  font-size: 1rem;
  flex-shrink: 0;
}

.price-guide-text {
  font-size: 0.8rem;
  color: #fbbf24;
  line-height: 1.5;
}

/* 목표주가 컨센서스 */
.consensus-section {
  margin-top: 12px;
  padding: 10px 12px;
  background: rgba(96, 165, 250, 0.08);
  border: 1px solid rgba(96, 165, 250, 0.2);
  border-radius: 10px;
}
.consensus-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.consensus-label {
  font-size: 0.75rem;
  color: rgba(255,255,255,0.6);
}
.consensus-price {
  font-size: 0.9rem;
  font-weight: 700;
  color: #60a5fa;
}
.consensus-upside {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
}
.consensus-upside.positive {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.15);
}
.consensus-upside.negative {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.15);
}
.consensus-bar-wrap {
  margin-bottom: 4px;
}
.consensus-bar-bg {
  position: relative;
  height: 8px;
  background: rgba(255,255,255,0.1);
  border-radius: 4px;
  overflow: visible;
}
.consensus-bar-current {
  height: 100%;
  background: linear-gradient(90deg, #22c55e, #60a5fa);
  border-radius: 4px;
  transition: width 0.6s ease;
}
.consensus-bar-marker {
  position: absolute;
  top: -16px;
  transform: translateX(-50%);
}
.marker-label {
  font-size: 0.6rem;
  color: rgba(255,255,255,0.5);
}
.consensus-bar-labels {
  display: flex;
  justify-content: space-between;
  font-size: 0.6rem;
  color: rgba(255,255,255,0.35);
  margin-top: 2px;
}
.consensus-source {
  font-size: 0.6rem;
  color: rgba(255,255,255,0.5);
  margin-top: 6px;
}

/* Score Diff Comment */
.score-diff-comment {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 16px;
  background: rgba(102, 126, 234, 0.1);
  border: 1px solid rgba(102, 126, 234, 0.3);
  border-radius: 10px;
}

.score-diff-comment p {
  margin: 0;
  font-size: 0.9rem;
  color: #a5b4fc;
  line-height: 1.4;
}

@media (max-width: 768px) {
  .reasons-section { grid-template-columns: 1fr; }
}
</style>
