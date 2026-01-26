<template>
  <router-view />
  <ChatBot v-if="isAuthenticated" />
  <div class="app-wrapper">
    <div class="global-controls">
      <NotificationBell />
      <ThemeToggle />
    </div>
    <router-view />
    <ChatBot />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { onMounted } from 'vue';
import ChatBot from './components/ChatBot.vue';
import { TokenManager } from './utils/auth';

const router = useRouter();
const isAuthenticated = ref(TokenManager.hasToken());

// 라우터 변경 시 인증 상태 업데이트
watch(
  () => router.currentRoute.value,
  () => {
    isAuthenticated.value = TokenManager.hasToken();
  }
);
import ThemeToggle from './components/ThemeToggle.vue';
import NotificationBell from './components/NotificationBell.vue';

// 앱 시작 시 저장된 테마 적용
onMounted(() => {
  const savedTheme = localStorage.getItem('theme');
  if (savedTheme === 'dark') {
    document.documentElement.setAttribute('data-theme', 'dark');
  } else if (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    document.documentElement.setAttribute('data-theme', 'dark');
  }
});
</script>

<style>
@import './assets/css/common.css';

.app-wrapper {
  position: relative;
}

.global-controls {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 999;
  display: flex;
  gap: 10px;
  align-items: center;
}

@media (max-width: 768px) {
  .global-controls {
    top: 10px;
    right: 10px;
  }
}
</style>

