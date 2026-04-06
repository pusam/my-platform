<template>
  <div class="app-wrapper">
    <div class="global-controls" v-if="!isStockPage">
      <NotificationBell />
    </div>
    <router-view />
    <NewsToast v-if="isAuthenticated" />
    <AppToast ref="appToast" />
    <BackToTop v-if="isStockPage" />
  </div>
</template>

<script setup>
import { ref, computed, watch, provide } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import NotificationBell from './components/NotificationBell.vue';
import NewsToast from './components/NewsToast.vue';
import AppToast from './components/AppToast.vue';
import BackToTop from './components/BackToTop.vue';
import { TokenManager } from './utils/auth';

const router = useRouter();
const route = useRoute();
const isAuthenticated = ref(TokenManager.hasToken());

const stockPaths = ['/stock-dashboard', '/stock/', '/global-futures'];
const isStockPage = computed(() => {
  return stockPaths.some(p => route.path.startsWith(p));
});

// 토스트 알림 글로벌 제공
const appToast = ref(null)
provide('toast', {
  success: (msg) => appToast.value?.show(msg, 'success'),
  error: (msg) => appToast.value?.show(msg, 'error', 5000),
  warning: (msg) => appToast.value?.show(msg, 'warning'),
  info: (msg) => appToast.value?.show(msg, 'info')
})

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
