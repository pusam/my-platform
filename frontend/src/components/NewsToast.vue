<template>
  <Teleport to="body">
    <TransitionGroup name="toast-slide" tag="div" class="news-toast-container">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="news-toast"
        @click="openNews(toast)"
      >
        <div class="toast-icon">&#x1F525;</div>
        <div class="toast-body">
          <div class="toast-label">
            <span class="toast-badge">&#x26A1; &#xAE34;&#xAE09;</span>
            <span v-if="toast.sourceName" class="toast-source">{{ toast.sourceName }}</span>
          </div>
          <div class="toast-title">{{ toast.title }}</div>
        </div>
        <button class="toast-close" @click.stop="dismiss(toast.id)">&times;</button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { newsAPI } from '../utils/api'
import { TokenManager } from '../utils/auth'

const toasts = ref([])
let pollTimer = null
let toastIdCounter = 0
const seenIds = new Set()

const pollUrgentNews = async () => {
  if (!TokenManager.hasToken()) return
  try {
    const res = await newsAPI.pollNews(16) // 16분 = 15분 주기 + 1분 여유
    const items = res.data?.data || []
    const urgentItems = items.filter(item => item.urgent && !seenIds.has(item.id))

    urgentItems.forEach(item => {
      seenIds.add(item.id)
      const id = ++toastIdCounter
      toasts.value.push({ id, ...item })
      // 8초 후 자동 dismiss
      setTimeout(() => dismiss(id), 8000)
    })

    // seenIds가 너무 커지지 않도록 관리 (최대 200개)
    if (seenIds.size > 200) {
      const arr = [...seenIds]
      arr.slice(0, arr.length - 100).forEach(id => seenIds.delete(id))
    }
  } catch {
    // 폴링 실패는 무시
  }
}

const dismiss = (id) => {
  toasts.value = toasts.value.filter(t => t.id !== id)
}

const openNews = (toast) => {
  if (toast.sourceUrl) {
    window.open(toast.sourceUrl, '_blank')
  }
  dismiss(toast.id)
}

onMounted(() => {
  // 60초마다 폴링
  pollTimer = setInterval(pollUrgentNews, 60000)
  // 초기 1회 실행 (5초 후 - 앱 로딩 대기)
  setTimeout(pollUrgentNews, 5000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.news-toast-container {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 10000;
  display: flex;
  flex-direction: column-reverse;
  gap: 10px;
  max-width: 420px;
  pointer-events: none;
}

.news-toast {
  pointer-events: auto;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  background: linear-gradient(135deg, #1E1B4B, #312E81);
  border: 1px solid rgba(129, 140, 248, 0.3);
  border-left: 4px solid #EF4444;
  border-radius: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4), 0 0 12px rgba(239, 68, 68, 0.15);
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}
.news-toast:hover {
  transform: translateX(-4px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5), 0 0 20px rgba(239, 68, 68, 0.25);
}

.toast-icon {
  font-size: 24px;
  flex-shrink: 0;
  animation: fireGlow 1.5s ease-in-out infinite;
}
@keyframes fireGlow {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.8; transform: scale(1.1); }
}

.toast-body {
  flex: 1;
  min-width: 0;
}

.toast-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.toast-badge {
  background: linear-gradient(135deg, #DC2626, #B91C1C);
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.5px;
}
.toast-source {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
}

.toast-title {
  font-size: 13px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.toast-close {
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.3);
  font-size: 18px;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
  flex-shrink: 0;
  transition: color 0.2s;
}
.toast-close:hover { color: white; }

/* 애니메이션 */
.toast-slide-enter-active { transition: all 0.4s cubic-bezier(0.34, 1.56, 0.64, 1); }
.toast-slide-leave-active { transition: all 0.3s ease-in; }
.toast-slide-enter-from {
  opacity: 0;
  transform: translateX(100px) scale(0.9);
}
.toast-slide-leave-to {
  opacity: 0;
  transform: translateX(100px) scale(0.9);
}

@media (max-width: 768px) {
  .news-toast-container {
    bottom: 12px;
    right: 12px;
    left: 12px;
    max-width: none;
  }
}
</style>
