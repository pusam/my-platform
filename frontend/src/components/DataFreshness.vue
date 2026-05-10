<template>
  <div class="data-freshness" :class="{ refreshing: isRefreshing }">
    <span class="dot" :class="{ live: isLive }"></span>
    <span class="text">
      <template v-if="!lastUpdated">대기 중...</template>
      <template v-else>
        <span class="elapsed">{{ elapsedText }}</span>
        <span class="sep">·</span>
        <span class="countdown">다음 {{ nextRefreshIn }}초</span>
      </template>
    </span>
    <button
      class="refresh-btn"
      :disabled="isRefreshing"
      @click="$emit('refresh')"
      :title="isRefreshing ? '갱신 중...' : '지금 갱신'"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           :class="{ spin: isRefreshing }">
        <polyline points="23 4 23 10 17 10"/>
        <polyline points="1 20 1 14 7 14"/>
        <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
      </svg>
    </button>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  lastUpdated: { type: [Date, null], default: null },
  isRefreshing: { type: Boolean, default: false },
  nextRefreshIn: { type: Number, default: 0 },
})

defineEmits(['refresh'])

// 경과 시간 1초마다 재계산용 tick
const now = ref(Date.now())
let tickTimer = null

onMounted(() => {
  tickTimer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer)
})

// 갱신 직후 점등 효과
const isLive = ref(false)
watch(() => props.lastUpdated, () => {
  isLive.value = true
  setTimeout(() => { isLive.value = false }, 1500)
})

const elapsedText = computed(() => {
  if (!props.lastUpdated) return '-'
  const sec = Math.max(0, Math.floor((now.value - props.lastUpdated.getTime()) / 1000))
  if (sec < 1) return '방금'
  if (sec < 60) return `${sec}초 전`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}분 전`
  const hr = Math.floor(min / 60)
  return `${hr}시간 전`
})
</script>

<style scoped>
.data-freshness {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.65);
  line-height: 1;
  user-select: none;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(148, 163, 184, 0.6);
  transition: background 0.2s, box-shadow 0.2s;
}
.dot.live {
  background: #4ade80;
  box-shadow: 0 0 0 3px rgba(74, 222, 128, 0.2);
}

.text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}
.elapsed { color: rgba(255, 255, 255, 0.85); font-weight: 600; }
.sep { opacity: 0.4; }
.countdown { color: rgba(255, 255, 255, 0.5); font-variant-numeric: tabular-nums; }

.refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.refresh-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}
.refresh-btn:disabled { opacity: 0.5; cursor: wait; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 라이트 테마 fallback — 다크 배경이 아닌 페이지 */
.data-freshness.light {
  background: rgba(0,0,0,0.04);
  border-color: rgba(0,0,0,0.08);
  color: #555;
}
.data-freshness.light .elapsed { color: #222; }
.data-freshness.light .countdown { color: #888; }
.data-freshness.light .refresh-btn {
  background: rgba(0,0,0,0.05);
  color: #555;
}

@media (max-width: 480px) {
  .data-freshness {
    padding: 5px 8px;
    font-size: 11px;
    gap: 6px;
  }
  .refresh-btn { width: 22px; height: 22px; }
  .countdown { display: none; } /* 모바일에서는 "Ns 전"만 */
}
</style>
