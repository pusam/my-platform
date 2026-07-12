<template>
  <section v-if="patterns && patterns.length > 0" class="chart-patterns-section">
    <div class="cps-header">
      <span class="cps-header-icon">📊</span>
      <h3 class="cps-title">차트 패턴 검출</h3>
      <InfoTooltip title="차트에서 자주 보이는 모양">
        <p><strong>↑ 상승 신호</strong>: 더블바텀 · 역헤드앤숄더 · 컵앤핸들 · 상승삼각형</p>
        <p><strong>↓ 하락 신호</strong>: 더블탑 · 헤드앤숄더 · 하락삼각형</p>
        <p><strong>관찰</strong>: 대칭삼각형 — 방향 결정 임박</p>
        <div class="tip-row" style="margin-top:8px"><b>주의</b></div>
        <div class="tip-row">모든 패턴은 <em>참고용</em>. 단독 매수/매도 X</div>
        <div class="tip-row">신뢰도 높음 + 거래량 동반 시 의미</div>
        <div class="tip-row">지지/저항 + Volume Profile 과 함께 보면 유용</div>
      </InfoTooltip>
      <span class="cps-disclaimer">참고용 · 자동매매 신호 아님</span>
    </div>
    <div class="cps-list">
      <div v-for="(p, idx) in patterns" :key="idx"
           class="cps-card" :class="'sig-' + (p.signal || 'NEUTRAL').toLowerCase()">
        <div class="cps-card-head">
          <span class="cps-card-icon">{{ getCpsIcon(p.type) }}</span>
          <span class="cps-card-label">{{ p.label }}</span>
          <span class="cps-badge cps-confidence" :class="'cf-' + (p.confidence || 'MEDIUM').toLowerCase()">
            신뢰도 {{ getCpsConfidenceLabel(p.confidence) }}
          </span>
          <span class="cps-badge cps-signal" :class="'sg-' + (p.signal || 'NEUTRAL').toLowerCase()">
            {{ getCpsSignalLabel(p.signal) }}
          </span>
        </div>
        <p class="cps-card-desc">{{ p.description }}</p>
        <div class="cps-dates" v-if="p.startDate && p.endDate">
          {{ p.startDate }} ~ {{ p.endDate }}
          <span v-if="p.referencePrice" class="cps-ref">· 기준가 {{ Number(p.referencePrice).toLocaleString() }}원</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import InfoTooltip from '../InfoTooltip.vue';

defineProps({
  patterns: { type: Array, default: () => [] }
});

const PATTERN_ICONS = {
  DOUBLE_TOP: '📉', DOUBLE_BOTTOM: '📈',
  HEAD_AND_SHOULDERS: '🏔️', INVERSE_HEAD_AND_SHOULDERS: '🪨',
  TRIANGLE_ASCENDING: '📈', TRIANGLE_DESCENDING: '📉', TRIANGLE_SYMMETRIC: '⚖️',
  CUP_AND_HANDLE: '☕'
};
const getCpsIcon = (type) => PATTERN_ICONS[type] || '📊';
const getCpsConfidenceLabel = (c) => ({ HIGH: '높음', MEDIUM: '보통', LOW: '낮음' }[c] || c || '보통');
const getCpsSignalLabel = (s) => ({ BULLISH: '상승 신호', BEARISH: '하락 신호', NEUTRAL: '관찰' }[s] || s || '중립');
</script>

<style scoped>
.chart-patterns-section {
  margin: 16px;
  padding: 14px 16px;
  background: var(--surface-card-soft, rgba(20, 24, 38, 0.6));
  border-radius: 10px;
}
.cps-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.cps-header-icon { font-size: 18px; }
.cps-title { margin: 0; font-size: 15px; flex: 1; }
.cps-disclaimer {
  font-size: 11px;
  opacity: 0.6;
  padding: 2px 8px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 4px;
}
.cps-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
}
.cps-card {
  background: rgba(255, 255, 255, 0.04);
  border-radius: 8px;
  padding: 10px 12px;
  border-left: 3px solid #888;
}
.cps-card.sig-bullish { border-left-color: #22c55e; }
.cps-card.sig-bearish { border-left-color: #ef4444; }
.cps-card.sig-neutral { border-left-color: #eab308; }
.cps-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.cps-card-icon { font-size: 16px; }
.cps-card-label { font-weight: 600; font-size: 13px; }
.cps-badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
}
.cf-high   { background: rgba(34, 197, 94, 0.18); color: #86efac; }
.cf-medium { background: rgba(234, 179, 8, 0.18); color: #fcd34d; }
.cf-low    { background: rgba(239, 68, 68, 0.18); color: #fca5a5; }
.sg-bullish { background: rgba(34, 197, 94, 0.18); color: #86efac; }
.sg-bearish { background: rgba(239, 68, 68, 0.18); color: #fca5a5; }
.sg-neutral { background: rgba(234, 179, 8, 0.18); color: #fcd34d; }
.cps-card-desc { margin: 4px 0; font-size: 12px; opacity: 0.85; line-height: 1.4; }
.cps-dates { font-size: 11px; opacity: 0.6; }
.cps-ref { margin-left: 6px; }
</style>
