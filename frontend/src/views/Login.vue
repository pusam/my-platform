<template>
  <div class="login-page">
    <!-- Animated Background -->
    <div class="background-animation">
      <div class="shape shape-1"></div>
      <div class="shape shape-2"></div>
      <div class="shape shape-3"></div>
    </div>

    <!-- Login Card -->
    <div class="login-card">
      <div class="card-header">
        <div class="logo-container">
          <div class="logo-circle">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>
        <h1 class="title">Welcome Back</h1>
        <p class="subtitle">로그인하여 계속 진행하세요</p>
      </div>

      <!-- Error Message -->
      <div v-if="errorMessage" class="alert-error">
        <svg viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/>
        </svg>
        {{ errorMessage }}
      </div>

      <!-- Login Form -->
      <form class="login-form" @submit.prevent="handleLogin">
        <div class="form-group">
          <label for="username">
            <svg viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clip-rule="evenodd"/>
            </svg>
            아이디
          </label>
          <input
            type="text"
            id="username"
            v-model="username"
            placeholder="아이디를 입력하세요"
            required
            autocomplete="username"
          >
        </div>

        <div class="form-group">
          <label for="password">
            <svg viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clip-rule="evenodd"/>
            </svg>
            비밀번호
          </label>
          <input
            type="password"
            id="password"
            v-model="password"
            placeholder="비밀번호를 입력하세요"
            required
            autocomplete="current-password"
          >
        </div>

        <div class="form-options">
          <label class="checkbox-label">
            <input type="checkbox" v-model="remember">
            <span class="checkbox-custom"></span>
            <span class="checkbox-text">로그인 상태 유지</span>
          </label>
          <router-link to="/forgot-password" class="forgot-link">비밀번호 찾기</router-link>
        </div>

        <button type="submit" class="login-btn" :disabled="loading">
          <span v-if="!loading">로그인</span>
          <span v-else class="loading-spinner">
            <svg viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" opacity="0.25"/>
              <path d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" fill="currentColor"/>
            </svg>
            로그인 중...
          </span>
        </button>
      </form>

      <div class="card-footer">
        <p>계정이 없으신가요? <router-link to="/signup" class="signup-link">회원가입</router-link></p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authAPI } from '../utils/api'
import { TokenManager, UserManager } from '../utils/auth'

const router = useRouter()

const username = ref('')
const password = ref('')
const remember = ref(false)
const errorMessage = ref('')
const loading = ref(false)

const handleLogin = async () => {
  errorMessage.value = ''
  loading.value = true

  try {
    const response = await authAPI.login(username.value, password.value)
    const data = response.data

    if (data.success) {
      // JWT 토큰 저장
      TokenManager.setToken(data.token)

      // 사용자 정보 저장 (role 포함)
      UserManager.setUser({
        username: data.username,
        name: data.name,
        role: data.role
      })

      // 역할별 대시보드로 이동
      if (data.role === 'ADMIN') {
        await router.push('/admin')
      } else {
        await router.push('/user')
      }
    } else {
      errorMessage.value = data.message
    }
  } catch (error) {
    errorMessage.value = error.response?.data?.message || '로그인 처리 중 오류가 발생했습니다.'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
@import '../assets/css/login.css';
</style>

