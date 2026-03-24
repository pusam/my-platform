<template>
  <Teleport to="body">
    <Transition name="btt-fade">
      <button v-if="visible" class="back-to-top" @click="scrollToTop" aria-label="맨 위로 이동">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="18 15 12 9 6 15"/>
        </svg>
      </button>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const visible = ref(false)

const onScroll = () => {
  visible.value = window.scrollY > 400
}

const scrollToTop = () => {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => window.removeEventListener('scroll', onScroll))
</script>

<style scoped>
.back-to-top {
  position: fixed;
  bottom: 24px;
  left: 24px;
  z-index: 900;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid rgba(255,255,255,0.15);
  background: rgba(15,15,26,0.85);
  backdrop-filter: blur(12px);
  color: rgba(255,255,255,0.7);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  box-shadow: 0 4px 16px rgba(0,0,0,0.3);
}
.back-to-top:hover {
  background: rgba(102,126,234,0.25);
  border-color: rgba(102,126,234,0.4);
  color: #fff;
  transform: translateY(-2px);
}

.btt-fade-enter-active { transition: all 0.25s ease-out; }
.btt-fade-leave-active { transition: all 0.2s ease-in; }
.btt-fade-enter-from, .btt-fade-leave-to { opacity: 0; transform: translateY(8px); }

@media (max-width: 768px) {
  .back-to-top {
    bottom: 72px; /* 모바일 하단 탭바 위 */
    left: 16px;
    width: 40px;
    height: 40px;
  }
}
</style>
