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
      router.back()
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
  padding: 8px 16px;
  background: transparent;
  border: 1px solid #ddd;
  border-radius: 6px;
  color: #666;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.back-button:hover {
  background: #f5f5f5;
  border-color: #ccc;
  color: #333;
}

.back-button svg {
  flex-shrink: 0;
}

/* 다크 모드 스타일 */
.back-button.dark {
  border-color: #4a4a8a;
  color: #aaa;
  background: rgba(74, 74, 138, 0.2);
}

.back-button.dark:hover {
  background: #4a4a8a;
  border-color: #5a5a9a;
  color: #fff;
  transform: translateX(-3px);
}

/* 시스템 다크 테마 지원 */
@media (prefers-color-scheme: dark) {
  .back-button:not(.dark) {
    border-color: #444;
    color: #aaa;
  }

  .back-button:not(.dark):hover {
    background: #333;
    border-color: #555;
    color: #fff;
  }
}
</style>
