<template>
  <div class="volume-power-gauge">
    <div class="gauge-header">
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
        {{ isMarketHours ? '체결 데이터를 수집하고 있습니다...' : '09:00 장 시작 후 체결강도가 표시됩니다' }}
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
import { computed } from 'vue';

const props = defineProps({
  volumePower: {
    type: Number,
    default: 100
  },
  signal: {
    type: String,
    default: 'NEUTRAL'
  }
});

// 장 운영 시간 체크 (09:00 ~ 15:30)
const isMarketHours = computed(() => {
  const now = new Date();
  const hour = now.getHours();
  const minute = now.getMinutes();
  const currentTime = hour * 60 + minute;
  return currentTime >= 540 && currentTime <= 930; // 09:00 ~ 15:30
});

// 데이터가 유효한지 확인 (0이거나 null이면 무효)
const hasValidData = computed(() => {
  return props.volumePower != null && props.volumePower > 0;
});

// 장 시작 대기 상태인지 확인
const isPreMarket = computed(() => {
  return !isMarketHours.value || !hasValidData.value;
});

const formattedPower = computed(() => {
  if (isPreMarket.value) return '-';
  return props.volumePower?.toFixed(1) || '0.0';
});

// 게이지 채우기 너비 (0~200% -> 0~100%)
const fillWidth = computed(() => {
  if (isPreMarket.value) return 50; // 중앙에 위치
  const power = props.volumePower || 100;
  return Math.min(100, Math.max(0, power / 2));
});

// 마커 위치 (0~200% -> 0~100%)
const markerPosition = computed(() => {
  if (isPreMarket.value) return 50; // 중앙에 위치
  const power = props.volumePower || 100;
  return Math.min(100, Math.max(0, power / 2));
});

// 바 색상 클래스
const barClass = computed(() => {
  if (isPreMarket.value) return 'pre-market';
  const power = props.volumePower || 100;
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
    return isMarketHours.value ? '데이터 수집 중' : '장 시작 대기';
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
  background: #1a1a3a;
  border-radius: 16px;
  padding: 24px;
  border: 1px solid #2a2a4a;
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
  color: #ef4444;
  border: 1px solid #ef4444;
}

.signal-badge.buy {
  background: rgba(248, 113, 113, 0.2);
  color: #f87171;
  border: 1px solid #f87171;
}

.signal-badge.neutral {
  background: rgba(163, 163, 163, 0.2);
  color: #a3a3a3;
  border: 1px solid #a3a3a3;
}

.signal-badge.sell {
  background: rgba(96, 165, 250, 0.2);
  color: #60a5fa;
  border: 1px solid #60a5fa;
}

.signal-badge.strong-sell {
  background: rgba(59, 130, 246, 0.2);
  color: #3b82f6;
  border: 1px solid #3b82f6;
}

/* 장 시작 대기 상태 */
.signal-badge.pre-market {
  background: rgba(113, 113, 122, 0.2);
  color: #71717a;
  border: 1px solid #71717a;
}

.power-value.strong-buy { color: #ef4444; }
.power-value.buy { color: #f87171; }
.power-value.neutral { color: #a3a3a3; }
.power-value.sell { color: #60a5fa; }
.power-value.strong-sell { color: #3b82f6; }
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
</style>
