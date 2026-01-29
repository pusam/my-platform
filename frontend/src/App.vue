<template>
  <div class="app-wrapper">
    <div class="global-controls">
      <NotificationBell />
    </div>
    <router-view />
    <ChatBot v-if="isAuthenticated" />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import ChatBot from './components/ChatBot.vue';
import NotificationBell from './components/NotificationBell.vue';
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

