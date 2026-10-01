<template>
  <Teleport to="body">
    <TransitionGroup name="toast-pop" tag="div" class="app-toast-container">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="app-toast"
        :class="t.type"
      >
        <span class="toast-icon">{{ icons[t.type] || icons.info }}</span>
        <span class="toast-msg">{{ t.message }}</span>
        <button type="button" class="toast-dismiss" aria-label="닫기" @click="dismiss(t.id)">&times;</button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'

const toasts = ref([])
let idCounter = 0

const icons = {
  success: '✓',
  error: '✕',
  warning: '⚠',
  info: 'ℹ'
}

const show = (message, type = 'info', duration = 3500) => {
  const id = ++idCounter
  toasts.value.push({ id, message, type })
  setTimeout(() => dismiss(id), duration)
}

const dismiss = (id) => {
  toasts.value = toasts.value.filter(t => t.id !== id)
}

defineExpose({ show })
</script>

<style scoped>
.app-toast-container {
  position: fixed;
  top: 60px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10001;
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
  max-width: 420px;
  width: calc(100% - 32px);
}

.app-toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: 12px;
  backdrop-filter: blur(16px);
  box-shadow: 0 8px 32px rgba(0,0,0,0.4);
  font-size: 13px;
  font-weight: 600;
  color: #fff;
}

/* 흰 글자 대비 — 밝은 500 계열 90% 는 2.2(경고)~3.9(오류)였다. 한 단계 어두운 700 계열로 5.4~7.1 (2026-10-01) */
.app-toast.success {
  background: rgba(21,128,61,0.95);
  border: 1px solid rgba(34,197,94,0.5);
}
.app-toast.error {
  background: rgba(185,28,28,0.95);
  border: 1px solid rgba(239,68,68,0.5);
}
.app-toast.warning {
  background: rgba(180,83,9,0.95);
  border: 1px solid rgba(245,158,11,0.5);
}
.app-toast.info {
  background: rgba(29,78,216,0.95);
  border: 1px solid rgba(59,130,246,0.5);
}

.toast-icon {
  font-size: 16px;
  font-weight: 800;
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(255,255,255,0.2);
}

.toast-msg {
  flex: 1;
  line-height: 1.4;
}

.toast-dismiss {
  background: none;
  border: none;
  color: rgba(255,255,255,0.85);
  font-size: 18px;
  cursor: pointer;
  padding: 0 2px;
  flex-shrink: 0;
  transition: color 0.15s;
  /* 누르는 영역 24px 이상(WCAG 2.5.8) */
  min-width: 24px;
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.toast-dismiss:hover { color: #fff; }

/* animation */
.toast-pop-enter-active { transition: all 0.3s cubic-bezier(0.34,1.56,0.64,1); }
.toast-pop-leave-active { transition: all 0.2s ease-in; }
.toast-pop-enter-from { opacity: 0; transform: translateY(-16px) scale(0.95); }
.toast-pop-leave-to { opacity: 0; transform: translateY(-8px) scale(0.95); }
</style>
