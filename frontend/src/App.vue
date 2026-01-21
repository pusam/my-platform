<template>
  <router-view />
  <ChatBot v-if="isAuthenticated" />
</template>

<script setup>
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
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
</script>

<style>
@import './assets/css/common.css';
</style>

