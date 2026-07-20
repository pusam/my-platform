<template>
  <div class="volume-power-gauge">
    <div class="gauge-header" :title="tooltipText">
      <h3>체결강도</h3>
      <span class="power-value" :class="signalClass">{{ formattedPower }}%</span>
    </div>

    <div class="gauge-container">
      <div class="gauge-bar">
        <div class="gauge-fill" :style="{ width: fillWidth + '%' }" :class="barClass"></div>
        <div class="gauge-marker" :style="{ left: markerPosition + '%' }"></div>
        <div class="center-marker"></div>
      </div>

      <div class="gauge-labels">
        <span class="label-min">0%</span>
        <span class="label-center">100%</span>
        <span class="label-max">200%</span>
      </div>
    </div>

    <div class="signal-badge" :class="signalClass">
      {{ signalText }}
    </div>

    <div class="description">
      <p v-if="isPreMarket" class="pre-market-text">
        {{ noDataText }}
      </p>
      <p v-else-if="isAfterMarket && hasValidData" class="post-market-text">
        장 마감 (Today's Close) - 오늘의 최종 체결강도
      </p>
      <p v-else-if="volumePower >= 120">매수세가 매우 강합니다</p>
      <p v-else-if="volumePower >= 100">매수세가 우위입니다</p>
      <p v-else-if="volumePower >= 80">균형 상태입니다</p>
      <p v-else-if="volumePower >= 60">매도세가 우위입니다</p>
      <p v-else>매도세가 매우 강합니다</p>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue';

const props = defineProps({
  // 0 또는 null = 데이터 없음 (백엔드가 미수집을 100 으로 위장하지 않음 — "데이터 없음" 상태로 표시)
  volumePower: {
    type: Number,
    default: 0
  },
  signal: {
    type: String,
    default: 'NEUTRAL'
  },
  dataSource: {
    type: String,
    default: ''
  }
});

// 현재 시간 정보 — new Date() 만 읽는 computed 는 반응형 의존성이 없어 마운트 시점 값으로
// 영구 고정된다(탭을 열어두면 09:00 이 지나도 "거래 시작 대기" 유지). 1분 틱 ref 로 갱신.
const nowTick = ref(Date.now());
let tickTimer = null;
onMounted(() => { tickTimer = setInterval(() => { nowTick.value = Date.now() }, 60_000); });
onUnmounted(() => { if (tickTimer) { clearInterval(tickTimer); tickTimer = null; } });

const currentTimeMinutes = computed(() => {
  const now = new Date(nowTick.value);
  return now.getHours() * 60 + now.getMinutes();
});

// NXT(대체거래소) 반영: 08:00~20:00 사실상 거래.
//   프리 08:00~08:50 · KRX 정규 09:00~15:30 · 애프터 15:30~20:00
// 거래 시작 전 (08:00 이전 — NXT 프리마켓도 안 열림)
const isBeforeMarket = computed(() => {
  return currentTimeMinutes.value < 480; // 08:00 이전
});

// 거래 중 (08:00 ~ 20:00, NXT 프리·정규·애프터 포함)
const isMarketHours = computed(() => {
  return currentTimeMinutes.value >= 480 && currentTimeMinutes.value <= 1200;
});

// 거래 종료 후 (20:00 이후)
const isAfterMarket = computed(() => {
  return currentTimeMinutes.value > 1200; // 20:00 이후
});

// 데이터가 유효한지 확인 (0이거나 null이면 무효)
const hasValidData = computed(() => {
  return props.volumePower != null && props.volumePower > 0;
});

// 장 시작 대기 상태
const isPreMarket = computed(() => {
  // 백엔드에서 '장전(초기화)' 소스 → 무조건 대기 상태
  if (props.dataSource === '장전(초기화)') return true;
  // 장 마감 후에는 데이터가 있으면 표시
  if (isAfterMarket.value && hasValidData.value) return false;
  // 장중이면 데이터 있으면 표시
  if (isMarketHours.value && hasValidData.value) return false;
  // 장 시작 전이거나 데이터가 없으면 대기 상태
  return isBeforeMarket.value || !hasValidData.value;
});

// 데이터 없음 안내 — 시간대별로 정확한 문구 (장 마감 후 "수집 중" 표기는 오해 유발)
const noDataText = computed(() => {
  if (isBeforeMarket.value) return 'NXT 프리마켓(08:00) 이후 체결강도가 표시됩니다';
  if (isAfterMarket.value) return '장 마감 — 당일 체결강도 데이터가 없습니다';
  return '체결 데이터를 수집하고 있습니다...';
});

const formattedPower = computed(() => {
  if (isPreMarket.value) return '-';
  return props.volumePower?.toFixed(1) || '0.0';
});

// 게이지 채우기 너비 (0~200% -> 0~100%) — isPreMarket 가드 통과 시 hasValidData 보장
const fillWidth = computed(() => {
  if (isPreMarket.value) return 50; // 중앙에 위치
  const power = props.volumePower || 0;
  return Math.min(100, Math.max(0, power / 2));
});

// 마커 위치 (0~200% -> 0~100%)
const markerPosition = computed(() => {
  if (isPreMarket.value) return 50; // 중앙에 위치
  const power = props.volumePower || 0;
  return Math.min(100, Math.max(0, power / 2));
});

// 바 색상 클래스
const barClass = computed(() => {
  if (isPreMarket.value) return 'pre-market';
  const power = props.volumePower || 0;
  if (power >= 120) return 'strong-buy';
  if (power >= 100) return 'buy';
  if (power >= 80) return 'neutral';
  if (power >= 60) return 'sell';
  return 'strong-sell';
});

// 신호 텍스트
const signalText = computed(() => {
  // 장 시작 전이거나 데이터가 없으면 대기 상태 표시
  if (isPreMarket.value) {
    if (isBeforeMarket.value) return '거래 시작 대기 (NXT 08:00~)';
    if (isAfterMarket.value) return '데이터 없음';
    return '데이터 수집 중';
  }
  // 장 마감 후 데이터가 있으면 마감 상태 표시
  if (isAfterMarket.value && hasValidData.value) {
    const signalMap = {
      'STRONG_BUY': '강한 매수세',
      'BUY': '매수 우위',
      'NEUTRAL': '균형',
      'SELL': '매도 우위',
      'STRONG_SELL': '강한 매도세'
    };
    return `${signalMap[props.signal] || '균형'} (종가)`;
  }
  switch (props.signal) {
    case 'STRONG_BUY': return '강한 매수세';
    case 'BUY': return '매수 우위';
    case 'NEUTRAL': return '균형';
    case 'SELL': return '매도 우위';
    case 'STRONG_SELL': return '강한 매도세';
    default: return '균형';
  }
});

// 체결강도 툴팁 설명
const tooltipText = computed(() => {
  const power = props.volumePower;
  if (!power || power === 0) return '체결강도: 매수 체결량 / 매도 체결량 × 100\n100% 이상이면 매수세 우위';
  if (power >= 150) return `체결강도 ${power.toFixed(1)}%: 매수세가 매도세의 1.5배 이상. 강한 매수 압력`;
  if (power >= 120) return `체결강도 ${power.toFixed(1)}%: 매수세 우위. 상승 탄력 기대`;
  if (power >= 100) return `체결강도 ${power.toFixed(1)}%: 매수세가 소폭 우위. 균형~상승 구간`;
  if (power >= 80) return `체결강도 ${power.toFixed(1)}%: 매도세 소폭 우위. 관망 구간`;
  return `체결강도 ${power.toFixed(1)}%: 매도세가 매수세보다 강함. 하락 압력 주의`;
});

// 신호 클래스
const signalClass = computed(() => {
  if (isPreMarket.value) return 'pre-market';
  switch (props.signal) {
    case 'STRONG_BUY': return 'strong-buy';
    case 'BUY': return 'buy';
    case 'NEUTRAL': return 'neutral';
    case 'SELL': return 'sell';
    case 'STRONG_SELL': return 'strong-sell';
    default: return 'neutral';
  }
});
</script>

<style scoped>
.volume-power-gauge {
  /* 허브 카드와 같은 표면 토큰 — 독자 색/모서리로 튀지 않게 */
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border-radius: var(--surface-radius, 12px);
  padding: 20px;
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
}

.gauge-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.gauge-header h3 {
  margin: 0;
  color: #fff;
  font-size: 1.2rem;
}

.power-value {
  font-size: 2rem;
  font-weight: 700;
  font-family: 'Monaco', 'Consolas', monospace;
}

.gauge-container {
  margin-bottom: 20px;
}

.gauge-bar {
  position: relative;
  height: 24px;
  background: linear-gradient(to right,
    #3b82f6 0%,
    #60a5fa 20%,
    #a3a3a3 40%,
    #a3a3a3 60%,
    #f87171 80%,
    #ef4444 100%
  );
  border-radius: 12px;
  overflow: visible;
}

.gauge-fill {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  border-radius: 12px 0 0 12px;
  transition: width 0.5s ease;
}

.gauge-fill.strong-sell {
  background: linear-gradient(to right, #3b82f6, #60a5fa);
}

.gauge-fill.sell {
  background: linear-gradient(to right, #3b82f6, #93c5fd);
}

.gauge-fill.neutral {
  background: linear-gradient(to right, #3b82f6, #a3a3a3);
}

.gauge-fill.buy {
  background: linear-gradient(to right, #3b82f6, #a3a3a3, #f87171);
}

.gauge-fill.strong-buy {
  background: linear-gradient(to right, #3b82f6, #a3a3a3, #ef4444);
}

.gauge-marker {
  position: absolute;
  top: -4px;
  width: 4px;
  height: 32px;
  background: #fff;
  border-radius: 2px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
  transform: translateX(-50%);
  transition: left 0.5s ease;
  z-index: 2;
}

.center-marker {
  position: absolute;
  top: 0;
  left: 50%;
  width: 2px;
  height: 24px;
  background: rgba(255, 255, 255, 0.8);
  transform: translateX(-50%);
  z-index: 1;
}

.gauge-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  color: #888;
  font-size: 0.8rem;
}

.label-center {
  font-weight: 600;
  color: #fff;
}

.signal-badge {
  display: inline-block;
  padding: 8px 16px;
  border-radius: 20px;
  font-weight: 600;
  font-size: 0.9rem;
  margin-bottom: 12px;
}

.signal-badge.strong-buy {
  background: rgba(239, 68, 68, 0.2);
  color: var(--signal-strong-buy, #ef4444);
  border: 1px solid var(--signal-strong-buy, #ef4444);
}

.signal-badge.buy {
  background: rgba(248, 113, 113, 0.2);
  color: var(--signal-buy, #f87171);
  border: 1px solid var(--signal-buy, #f87171);
}

.signal-badge.neutral {
  background: rgba(163, 163, 163, 0.2);
  color: var(--signal-neutral, #a3a3a3);
  border: 1px solid var(--signal-neutral, #a3a3a3);
}

.signal-badge.sell {
  background: rgba(96, 165, 250, 0.2);
  color: var(--signal-sell, #60a5fa);
  border: 1px solid var(--signal-sell, #60a5fa);
}

.signal-badge.strong-sell {
  background: rgba(59, 130, 246, 0.2);
  color: var(--signal-strong-sell, #3b82f6);
  border: 1px solid var(--signal-strong-sell, #3b82f6);
}

/* 장 시작 대기 상태 */
.signal-badge.pre-market {
  background: rgba(113, 113, 122, 0.2);
  color: #71717a;
  border: 1px solid #71717a;
}

.power-value.strong-buy { color: var(--signal-strong-buy, #ef4444); }
.power-value.buy { color: var(--signal-buy, #f87171); }
.power-value.neutral { color: var(--signal-neutral, #a3a3a3); }
.power-value.sell { color: var(--signal-sell, #60a5fa); }
.power-value.strong-sell { color: var(--signal-strong-sell, #3b82f6); }
.power-value.pre-market { color: #71717a; }

.gauge-fill.pre-market {
  background: linear-gradient(to right, #52525b, #71717a);
  opacity: 0.5;
}

.description {
  color: #888;
  font-size: 0.9rem;
}

.description p {
  margin: 0;
}

.description .pre-market-text {
  color: #71717a;
  font-style: italic;
}

.description .post-market-text {
  color: #a78bfa;
  font-style: italic;
}
</style>
