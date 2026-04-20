<template>
  <button @click="goBack" class="back-button" :class="{ dark: dark }">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M19 12H5M12 19l-7-7 7-7"/>
    </svg>
    <span>돌아가기</span>
  </button>
</template>

<script>
import { useRouter } from 'vue-router'

export default {
  name: 'BackButton',
  props: {
    dark: {
      type: Boolean,
      default: false
    }
  },
  setup() {
    const router = useRouter()

    const goBack = () => {
      if (window.history.length > 1) {
        router.back()
      } else {
        router.push('/stock-dashboard')
      }
    }

    return { goBack }
  }
}
</script>

<style scoped>
.back-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 36px;
  height: 36px;
  justify-content: center;
  padding: 0;
  background: var(--bg-page, #f5f7fa);
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: 10px;
  color: var(--text-muted, #718096);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.back-button span {
  display: none;
}

.back-button:hover {
  background: var(--primary-start, var(--primary-start));
  border-color: var(--primary-start, var(--primary-start));
  color: #fff;
  transform: translateX(-2px);
}

.back-button svg {
  flex-shrink: 0;
}

/* 다크 모드 스타일 */
.back-button.dark {
  border-color: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.6);
  background: rgba(255, 255, 255, 0.08);
}

.back-button.dark:hover {
  background: rgba(255, 255, 255, 0.15);
  border-color: rgba(255, 255, 255, 0.25);
  color: #fff;
  transform: translateX(-2px);
}
</style>
